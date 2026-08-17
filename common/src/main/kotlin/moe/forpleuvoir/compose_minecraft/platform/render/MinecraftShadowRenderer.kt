package moe.forpleuvoir.compose_minecraft.platform.render

import androidx.compose.ui.graphics.MinecraftPath
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.state.gui.GuiRenderState
import org.joml.Matrix3x2f
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * GPU 距离场软阴影渲染器(T.14 重构版)—— 完全参照 Skia SkShadowUtils
 * 的参数语义,模糊在 GPU 完成:
 *
 * - **双阴影**:ambient(环境光,无偏移)+ spot(点光,投影偏移),分别提交;
 * - **σ = elevation × lightRadius / lightHeight / 2**(默认 800/600 → 0.667e);
 * - **alpha = kAmbientAlpha(0.039) | kSpotAlpha(0.19) × (1 - elevation/lightHeight)**;
 * - **spot 偏移** = -(lightXY - center) × zRatio,zRatio = e/(lightHeight - e),
 *   由 GraphicsLayer.drawShadow 计算后传入;
 * - **模糊 = gui_shadow 片元着色器的 erfc 解析解**(半平面高斯卷积):
 *   CPU 只做形状三角化 + 每顶点距离场([GeometryTessellator.shadowFill]),
 *   无离屏渲染、无纹理上传、无 CPU 卷积;
 * - **网格 LRU 缓存**:形状(轮廓 + σ + 偏移)不变时每帧零 CPU;
 * - 渲染顺序:GuiRenderStateMixin 把阴影元素排到列表最前(最底层),
 *   内容后画盖住重叠 —— 等价于官方"先画阴影、后画内容"。
 *
 * 平台适配点:
 * - 顶点在局部坐标,几何变换由 pose(命令矩阵 2D 部分,行主序语义见
 *   MinecraftRenderContext.toMatrix3x2f 注释)在 GPU 端完成;
 * - 阴影元素 bounds(本体外扩模糊带 → pose 变换 → 与 scissor 求交)非 null,
 *   是 GuiRenderer.findAppropriateNode 接受元素的前提。
 */
internal object MinecraftShadowRenderer {

    // ── Skia SkShadowUtils 参数(kAmbientAlpha / kSpotAlpha / kLightHeight / kLightRadius)──
    private const val LIGHT_HEIGHT = 600f
    private const val LIGHT_RADIUS = 800f
    private const val AMBIENT_ALPHA = 0.039f
    private const val SPOT_ALPHA = 0.19f

    /** 模糊衰减覆盖:外扩 3σ(erfc(3/√2·σ/σ) ≈ 0.17%,alpha 归零) */
    private const val BLUR_COVER = 3f

    /** 内部饱和距离(px):距轮廓 ≥ 该值的内部 alpha 饱和,不参与衰减 */
    private const val INNER_SATURATE = 1.5f

    /** sqrt(2) 归一化常量(erfc 自变量 d/(σ√2)) */
    private const val SQRT2 = 1.41421356f

    // ── 网格缓存(形状轮廓 + σ + 偏移 → 顶点数组)─────────────────────────

    private class MeshKey(
        val kind: Int,                       // 0=rect, 1=roundRect, 2=path
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val offX: Float, val offY: Float,
        val blurPx: Float, val norm: Float,
        val segHash: Int,                    // path 段 hash(kind=2)
    ) {
        override fun equals(other: Any?): Boolean = other is MeshKey &&
            kind == other.kind && left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom &&
            offX == other.offX && offY == other.offY &&
            blurPx == other.blurPx && norm == other.norm && segHash == other.segHash

        override fun hashCode(): Int {
            var h = kind
            h = h * 31 + left.toRawBits()
            h = h * 31 + top.toRawBits()
            h = h * 31 + right.toRawBits()
            h = h * 31 + bottom.toRawBits()
            h = h * 31 + offX.toRawBits()
            h = h * 31 + offY.toRawBits()
            h = h * 31 + blurPx.toRawBits()
            h = h * 31 + norm.toRawBits()
            h = h * 31 + segHash
            return h
        }
    }

    private val meshCache = object : LinkedHashMap<MeshKey, FloatArray>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MeshKey, FloatArray>?): Boolean =
            size > 96
    }

    // ── 主入口 ────────────────────────────────────────────────────────────

    /**
     * 渲染一条阴影命令:形状(矩形/圆角矩形/Path)生成两帧网格 ——
     * ambient(无偏移,颜色 [ambientColorArgb])+ spot(偏移 [offsetX, offsetY],
     * 颜色 [spotColorArgb]),各提交一个 [GuiShadowRenderState](RGB = 阴影颜色,
     * alpha = 颜色 alpha × 各自阴影强度;默认全黑 = 官方默认行为)。
     */
    fun renderShadow(
        renderState: GuiRenderState,
        matrix: FloatArray,
        left: Float, top: Float, right: Float, bottom: Float,
        elevation: Float,
        offsetX: Float, offsetY: Float,
        cornerRadius: Float,
        pathSegments: List<MinecraftPath.PathSegmentData>?,
        ambientColorArgb: Int,
        spotColorArgb: Int,
        scissor: ScreenRectangle?,
    ) {
        if (elevation <= 0f) return
        val pts = shapePoints(pathSegments, left, top, right, bottom, cornerRadius) ?: return
        if (pts.size < 6) return

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < pts.size) {
            val x = pts[i]; val y = pts[i + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            i += 2
        }

        val sigma = elevation * LIGHT_RADIUS / LIGHT_HEIGHT / 2f
        val blurPx = sigma * BLUR_COVER
        val norm = 1f / (sigma * SQRT2)
        val fade = (1f - elevation / LIGHT_HEIGHT).coerceIn(0f, 1f)
        val segHash = pathSegments?.let { hashSegments(it) } ?: 0
        val kind = if (pathSegments != null) 2 else if (cornerRadius > 0f) 1 else 0

        // ambient:无偏移(环境光,均匀包围)
        emit(renderState, matrix, pts, minX, minY, maxX, maxY,
            0f, 0f, blurPx, norm, AMBIENT_ALPHA * fade, ambientColorArgb,
            kind, segHash, scissor)
        // spot:投影偏移(点光方向)
        emit(renderState, matrix, pts, minX, minY, maxX, maxY,
            offsetX, offsetY, blurPx, norm, SPOT_ALPHA * fade, spotColorArgb,
            kind, segHash, scissor)
    }

    // ── 单阴影元素 ───────────────────────────────────────────────────────

    private fun emit(
        renderState: GuiRenderState,
        matrix: FloatArray,
        pts: FloatArray,
        minX: Float, minY: Float, maxX: Float, maxY: Float,
        offX: Float, offY: Float,
        blurPx: Float, norm: Float,
        alpha: Float,
        colorArgb: Int,
        kind: Int, segHash: Int,
        scissor: ScreenRectangle?,
    ) {
        if (alpha <= 0f) return
        val key = MeshKey(kind, minX, minY, maxX, maxY, offX, offY, blurPx, norm, segHash)
        val vertices = meshCache.getOrPut(key) {
            val moved = FloatArray(pts.size) { i -> if (i % 2 == 0) pts[i] + offX else pts[i] + offY }
            val sink = GeometryTessellator.Sink()
            GeometryTessellator.shadowFill(moved, blurPx, norm, sink)
            sink.toArray()
        }
        if (vertices.size < 9) return

        // 屏幕 bounds:本体(偏移后)外扩模糊带 → 命令矩阵变换 → 与 scissor 求交
        val l0 = minX + offX - blurPx
        val t0 = minY + offY - blurPx
        val r0 = maxX + offX + blurPx
        val b0 = maxY + offY + blurPx
        val sx0 = floor(matrix[0] * l0 + matrix[4] * t0 + matrix[12]).toInt()
        val sy0 = floor(matrix[1] * l0 + matrix[5] * t0 + matrix[13]).toInt()
        val sx1 = ceil(matrix[0] * r0 + matrix[4] * b0 + matrix[12]).toInt()
        val sy1 = ceil(matrix[1] * r0 + matrix[5] * b0 + matrix[13]).toInt()
        val bounds = ScreenRectangle(sx0, sy0, sx1 - sx0, sy1 - sy0)
        val clipped = scissor?.intersection(bounds) ?: bounds
        if (clipped.width <= 0 || clipped.height <= 0) return

        // 最终阴影 alpha = 颜色 alpha × Skia 阴影强度(0.039|0.19 × fade),
        // RGB = 颜色 RGB(Skia 语义:阴影颜色调制,默认黑 = 原行为)。
        val colorAlpha = ((colorArgb ushr 24) and 0xFF) / 255f
        val finalAlpha = (colorAlpha * alpha * 255f).toInt().coerceIn(0, 255)

        renderState.addGuiElement(
            GuiShadowRenderState(
                pose = Matrix3x2f(matrix[0], matrix[1], matrix[4], matrix[5], matrix[12], matrix[13]),
                shadowColorArgb = (colorArgb and 0xFFFFFF) or (finalAlpha shl 24),
                scissor = scissor,
                vertices = vertices,
                elementBounds = clipped,
            )
        )
    }

    // ── 形状 → 轮廓点序列(局部坐标,逆时针)───────────────────────────────

    private fun shapePoints(
        pathSegments: List<MinecraftPath.PathSegmentData>?,
        left: Float, top: Float, right: Float, bottom: Float,
        cornerRadius: Float,
    ): FloatArray? {
        if (pathSegments != null) {
            val out = ArrayList<Float>()
            var startX = 0f; var startY = 0f
            var lastX = 0f; var lastY = 0f
            var hasPoint = false
            for (seg in pathSegments) {
                when (seg.type) {
                    MinecraftPath.PathSegmentType.Move -> {
                        startX = seg.points[0]; startY = seg.points[1]
                        lastX = startX; lastY = startY; hasPoint = true
                    }
                    MinecraftPath.PathSegmentType.Line -> {
                        addSeg(out, lastX, lastY, seg.points[0], seg.points[1])
                        lastX = seg.points[0]; lastY = seg.points[1]
                    }
                    MinecraftPath.PathSegmentType.Quadratic -> {
                        val p0x = lastX; val p0y = lastY
                        val p1x = seg.points[0]; val p1y = seg.points[1]
                        val p2x = seg.points[2]; val p2y = seg.points[3]
                        var px = p0x; var py = p0y
                        for (k in 1..8) {
                            val t = k / 8f
                            val it = 1f - t
                            val x = it * it * p0x + 2f * it * t * p1x + t * t * p2x
                            val y = it * it * p0y + 2f * it * t * p1y + t * t * p2y
                            addSeg(out, px, py, x, y)
                            px = x; py = y
                        }
                        lastX = p2x; lastY = p2y
                    }
                    MinecraftPath.PathSegmentType.Cubic -> {
                        val p0x = lastX; val p0y = lastY
                        val p1x = seg.points[0]; val p1y = seg.points[1]
                        val p2x = seg.points[2]; val p2y = seg.points[3]
                        val p3x = seg.points[4]; val p3y = seg.points[5]
                        var px = p0x; var py = p0y
                        for (k in 1..12) {
                            val t = k / 12f
                            val it = 1f - t
                            val x = it * it * it * p0x + 3f * it * it * t * p1x + 3f * it * t * t * p2x + t * t * t * p3x
                            val y = it * it * it * p0y + 3f * it * it * t * p1y + 3f * it * t * t * p2y + t * t * t * p3y
                            addSeg(out, px, py, x, y)
                            px = x; py = y
                        }
                        lastX = p3x; lastY = p3y
                    }
                    MinecraftPath.PathSegmentType.Close -> {
                        if (hasPoint && (lastX != startX || lastY != startY)) {
                            addSeg(out, lastX, lastY, startX, startY)
                        }
                        lastX = startX; lastY = startY
                    }
                }
            }
            return dedupe(out)
        }
        if (cornerRadius > 0f) {
            // 圆角矩形:4 直线段 + 4 圆弧(每弧 8 段),逆时针
            val r = cornerRadius.coerceIn(0f, min((right - left) * 0.5f, (bottom - top) * 0.5f))
            val out = ArrayList<Float>()
            addSeg(out, left + r, top, right - r, top)
            arc(out, right - r, top + r, r, -90f, 0f)
            addSeg(out, right, top + r, right, bottom - r)
            arc(out, right - r, bottom - r, r, 0f, 90f)
            addSeg(out, right - r, bottom, left + r, bottom)
            arc(out, left + r, bottom - r, r, 90f, 180f)
            addSeg(out, left, bottom - r, left, top + r)
            arc(out, left + r, top + r, r, 180f, 270f)
            return dedupe(out)
        }
        // 矩形
        return floatArrayOf(left, top, right, top, right, bottom, left, bottom)
    }

    private fun addSeg(out: ArrayList<Float>, x0: Float, y0: Float, x1: Float, y1: Float) {
        out.add(x0); out.add(y0); out.add(x1); out.add(y1)
    }

    /** 圆弧采样(圆心 [cx, cy],半径 [r],角度 [startDeg] → [endDeg],8 段,逆时针) */
    private fun arc(out: ArrayList<Float>, cx: Float, cy: Float, r: Float, startDeg: Float, endDeg: Float) {
        for (k in 1..8) {
            val a = Math.toRadians((startDeg + (endDeg - startDeg) * k / 8f).toDouble())
            out.add((cx + r * cos(a)).toFloat())
            out.add((cy + r * sin(a)).toFloat())
        }
    }

    /** 去除相邻重复点(线段端点重复)+ 首尾闭合去重 */
    private fun dedupe(out: ArrayList<Float>): FloatArray {
        if (out.size < 4) return FloatArray(0)
        val res = ArrayList<Float>(out.size)
        var i = 0
        while (i < out.size) {
            val x = out[i]; val y = out[i + 1]
            if (res.size < 2 || res[res.size - 2] != x || res[res.size - 1] != y) {
                res.add(x); res.add(y)
            }
            i += 2
        }
        if (res.size >= 4) {
            val fx = res[0]; val fy = res[1]
            val lx = res[res.size - 2]; val ly = res[res.size - 1]
            if (fx == lx && fy == ly) {
                res.removeAt(res.size - 1)
                res.removeAt(res.size - 1)
            }
        }
        return res.toFloatArray()
    }

    /** Path 段 hash(缓存 key 用;碰撞概率极低,可接受) */
    private fun hashSegments(segments: List<MinecraftPath.PathSegmentData>): Int {
        var h = 0
        for (seg in segments) {
            h = h * 31 + seg.type.ordinal
            for (p in seg.points) h = h * 31 + p.toRawBits()
        }
        return h
    }
}
