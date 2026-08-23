package moe.forpleuvoir.compose_minecraft.platform.render.backend

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.DrawCustomCommand
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.MinecraftCanvas.DrawArcCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCircleCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawGradientRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawImageRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawLineCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawOvalCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPathCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPointsCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRoundRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawShadowCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawTextCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawVerticesCommand
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.VertexMode
import moe.forpleuvoir.compose_minecraft.platform.render.paint.ColorEvaluator
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.GeometryTessellator
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.GeometryTessellator.Sink
import moe.forpleuvoir.compose_minecraft.platform.render.text.GlyphCache
import moe.forpleuvoir.compose_minecraft.platform.render.text.TextRenderBackend
import moe.forpleuvoir.compose_minecraft.platform.render.text.TextRenderConfig
import moe.forpleuvoir.compose_minecraft.platform.render.text.TrueTypeFontManager
import moe.forpleuvoir.compose_minecraft.platform.render.text.TrueTypeTextWriter
import moe.forpleuvoir.compose_minecraft.platform.render.paint.RasterGradientSampler
import moe.forpleuvoir.compose_minecraft.platform.render.paint.toArgbInt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CPU 光栅化后端(P2,自 `GraphicsLayerRasterizer` 原样搬移,2025-08)。
 *
 * 把 [DrawCommand] 直接光栅化到 CPU 像素缓冲(0xAARRGGBB),支撑
 * `GraphicsLayer.toImageBitmap` —— 图层内容快照,无渲染上下文依赖。
 *
 * 策略(与 GPU 回放同一套几何):
 * - 形状命令统一走 [GeometryTessellator] 三角化,逐三角形重心判定 +
 *   coverage 插值(顶点 coverage 是距轮廓的有符号距离,插值后 clamp 0..1
 *   即得与 GPU 一致的边缘软过渡抗锯齿);
 * - 图片命令经命令矩阵 2D 逆变换采样(最近邻 / 双线性按 filterQuality);
 * - 渐变矩形用两个三角形 + 顶点色重心插值。
 *
 * 不支持(静默跳过,快照中缺失,调用方自行注意):
 * - [DrawShadowCommand]:阴影是 GPU 距离场渲染,快照不含阴影;
 * - 文本的部分能力:P3③ 已实现 TTF 字形/装饰线/渐变/混淆/粗体/斜体的 CPU
 *   光栅化([drawText]),但缺字字符无原版字形可兜底(跳过墨迹保留推进)、
 *   阴影与 GUI 端一致未绘制、[TextRenderBackend.VANILLA] 定向 run 跳过。
 */
internal class RasterBackend(
    private val out: IntArray,
    private val width: Int,
    private val height: Int,
) : GeometryBackend {

    /** 纯色矩形 quad 输出缓冲(命令间复用) */
    private val sink = Sink()

    // ── GeometryBackend ─────────────────────────────────────────────────

    override fun drawRect(cmd: DrawRectCommand) {
        val paint = cmd.paint
        if (paint.shader != null) {
            // 渐变矩形必须走着色器顶点色三角化(与 GPU 回放一致);
            // 退化为矩形的 roundRect 内部会用 rectGrid 细分,
            // 避免径向渐变因只有 4 个角点而退化为纯色。
            tessellate(out, width, height, cmd) { s ->
                GeometryTessellator.roundRect(
                    cmd.left, cmd.top, cmd.right, cmd.bottom,
                    0f, 0f,
                    fill = paint.style == PaintingStyle.Fill,
                    strokeWidth = paint.strokeWidth,
                    sink = s,
                )
            }
        } else {
            sink.clear()
            sink.quad(
                cmd.left, cmd.top,
                cmd.right, cmd.top,
                cmd.right, cmd.bottom,
                cmd.left, cmd.bottom,
            )
            fillShape(out, width, height, cmd.matrix, cmd.clip, sink, paint.color, paint.alpha)
        }
    }

    override fun drawRoundRect(cmd: DrawRoundRectCommand) {
        tessellate(out, width, height, cmd) { s ->
            GeometryTessellator.roundRect(
                cmd.left, cmd.top, cmd.right, cmd.bottom,
                cmd.radiusX, cmd.radiusY,
                fill = cmd.paint.style == PaintingStyle.Fill,
                strokeWidth = cmd.paint.strokeWidth,
                sink = s,
            )
        }
    }

    override fun drawOval(cmd: DrawOvalCommand) {
        tessellate(out, width, height, cmd) { s ->
            GeometryTessellator.oval(
                cmd.left, cmd.top, cmd.right, cmd.bottom,
                fill = cmd.paint.style == PaintingStyle.Fill,
                strokeWidth = cmd.paint.strokeWidth,
                sink = s,
            )
        }
    }

    override fun drawCircle(cmd: DrawCircleCommand) {
        tessellate(out, width, height, cmd) { s ->
            GeometryTessellator.circle(
                cmd.centerX, cmd.centerY, cmd.radius,
                fill = cmd.paint.style == PaintingStyle.Fill,
                strokeWidth = cmd.paint.strokeWidth,
                sink = s,
            )
        }
    }

    override fun drawArc(cmd: DrawArcCommand) {
        tessellate(out, width, height, cmd) { s ->
            GeometryTessellator.arc(
                cmd.left, cmd.top, cmd.right, cmd.bottom,
                cmd.startAngle, cmd.sweepAngle, cmd.useCenter,
                fill = cmd.paint.style == PaintingStyle.Fill,
                strokeWidth = cmd.paint.strokeWidth,
                sink = s,
            )
        }
    }

    override fun drawLine(cmd: DrawLineCommand) {
        tessellate(out, width, height, cmd) { s ->
            GeometryTessellator.line(
                cmd.p1x, cmd.p1y, cmd.p2x, cmd.p2y,
                cmd.paint.strokeWidth,
                cmd.paint.strokeCap,
                sink = s,
            )
        }
    }

    override fun drawPath(cmd: DrawPathCommand) {
        tessellate(out, width, height, cmd) { s ->
            GeometryTessellator.path(
                cmd.segments,
                fill = cmd.paint.style == PaintingStyle.Fill,
                strokeWidth = cmd.paint.strokeWidth,
                cap = cmd.paint.strokeCap,
                sink = s,
            )
        }
    }

    override fun drawPoints(cmd: DrawPointsCommand) {
        tessellate(out, width, height, cmd) { s ->
            GeometryTessellator.points(
                cmd.pointMode, cmd.points,
                cmd.paint.strokeWidth,
                cmd.paint.strokeCap,
                sink = s,
            )
        }
    }

    /**
     * P3③ CPU 文本路径:直接 stb 光栅化字形并逆映射混入像素缓冲。
     *
     * - **不经 [GlyphCache]/图集**:二者绑定 GPU 纹理上传(渲染线程约束),
     *   而 `toImageBitmap` 快照可能不在渲染线程回放 —— 此处每次直接
     *   `TrueTypeFont.rasterize`(MemoryStack 栈内存,线程安全);
     * - 排版/样式语义与 GUI 端([TrueTypeTextWriter])严格同源:advance+kern、
     *   混淆确定性种子、粗体膨胀合成、斜体剪切、装饰线几何与颜色采样点一致;
     * - 差异(文档化):缺字字符无原版字形可兜底 → 跳过墨迹保留推进;
     *   阴影与 GUI 端一致未绘制;[TextRenderBackend.VANILLA] 定向 run 跳过。
     */
    override fun drawText(cmd: DrawTextCommand) {
        if (cmd.backend == TextRenderBackend.VANILLA) return
        val binding = cmd.font ?: moe.forpleuvoir.compose_minecraft.platform.render.text.FontResolver.resolveNative(cmd.style)
        if (binding.font.channel != moe.forpleuvoir.compose_minecraft.platform.render.text.FontChannel.STB_VECTOR) return
        val chain = TrueTypeFontManager.regularChain()
        if (chain.isEmpty()) return
        val metrics = binding.metrics

        val style = cmd.style
        // 取色语义与 TrueTypeTextWriter 一致:样式色补 alpha,渐变按采样点逐字形取色
        val alphaByte = (cmd.alpha * 255f).roundToInt().coerceIn(0, 255)
        val baseColor = style.color?.value?.or(0xFF000000.toInt()) ?: 0xFFFFFFFF.toInt()
        val solidColor = (baseColor and 0x00FFFFFF) or (alphaByte shl 24)
        fun colorAt(x: Float, y: Float): Int = cmd.shader?.let {
            ColorEvaluator.sampleGradient(ColorEvaluator.gradientTAt(x, y, it), it, cmd.alpha)
        } ?: solidColor

        // 光栅化字号 = baseSizePx × 姿态矩阵缩放(量化 0.25px),与 GUI 端同式
        val rasterScale = max(TrueTypeTextWriter.MIN_RASTER_SCALE, matrixScale(cmd.matrix))
        val sizePx = GlyphCache.quantize(binding.emPx * rasterScale)

        val baselineY = cmd.y + binding.baselineFromTopPx
        var penX = cmd.x

        // 混淆:确定性随机槽位 + 字符序号种子(与 GUI 端同一公式,快照观感一致)
        val obfuscated = style.isObfuscated
        val obfuscateSlot = System.currentTimeMillis() /
            TextRenderConfig.obfuscatedUpdateIntervalMs.coerceAtLeast(1L)

        val bold = style.isBold
        val italic = style.isItalic
        val boldChainFonts = if (bold) TrueTypeFontManager.boldChain() else emptyList()

        var charIndex = 0
        var i = 0
        val n = cmd.text.length
        var prevCp = -1
        while (i < n) {
            val cp = cmd.text.codePointAt(i)
            i += Character.charCount(cp)
            charIndex++

            var drawCp = cp
            if (obfuscated && cp != ' '.code) {
                chain.first().randomObfuscationCandidate(cp, charIndex * 1000003L + obfuscateSlot)
                    ?.let { drawCp = it }
            }

            val renderFont = boldChainFonts.firstOrNull { it.hasGlyph(drawCp) }
                ?: chain.firstOrNull { it.hasGlyph(drawCp) }
            if (renderFont == null) {
                // 缺字:CPU 快照无原版字形可兜底 → 跳过墨迹、保留推进
                penX += metrics.advance(cp)
                prevCp = cp
                continue
            }
            val syntheticBold = bold && renderFont !in boldChainFonts
            val embolden =
                if (syntheticBold) (sizePx * TextRenderConfig.boldEmboldenRatio).coerceIn(0.5f, 2f) else 0f
            val glyph = renderFont.rasterize(drawCp, sizePx, embolden)

            if (glyph != null && glyph.width > 0 && glyph.height > 0) {
                // 位图像素 → 1x 局部坐标(GUI 端 rasterDiv 同源公式)
                val rasterDiv = sizePx / renderFont.baseSizePx
                val wLocal = glyph.width / rasterDiv
                val hLocal = glyph.height / rasterDiv
                val leftRaw = penX + glyph.bearingX / rasterDiv
                val topRaw = baselineY + glyph.bearingTop / rasterDiv
                val left = TrueTypeTextWriter.snap(leftRaw, rasterScale)
                val top = TrueTypeTextWriter.snap(topRaw, rasterScale)
                val right = left + wLocal
                val bottom = top + hLocal
                val color = colorAt(left + wLocal * 0.5f, top + hLocal * 0.5f)
                // 斜体剪切(TrueTypeTextWriter.addGlyphQuad 同公式:水平偏移 ∝ 基线距)
                val shearTop = if (italic) TrueTypeTextWriter.ITALIC_SHEAR * (baselineY - top) else 0f
                val shearBottom = if (italic) TrueTypeTextWriter.ITALIC_SHEAR * (baselineY - bottom) else 0f
                blitGlyphQuad(
                    out, width, height, cmd.matrix, cmd.clip,
                    tlx = left + shearTop, tly = top,
                    trx = right + shearTop, tryY = top,
                    blx = left + shearBottom, bly = bottom,
                    brx = right + shearBottom, bry = bottom,
                    bytes = glyph.bytes, bw = glyph.width, bh = glyph.height,
                    rgb = color and 0x00FFFFFF, alphaByte = alphaByte,
                )
            }
            penX += metrics.advance(cp) + metrics.kern(prevCp, cp)
            prevCp = cp
        }

        // 装饰线(下划线/删除线):几何/颜色采样点对齐 TrueTypeTextWriter
        if (penX > cmd.x && (style.isUnderlined || style.isStrikethrough)) {
            val thickness = max(1f, metrics.lineHeight / 9f)
            val decorColor = colorAt((cmd.x + penX) * 0.5f, baselineY)
            if (style.isUnderlined) {
                fillDecorRect(
                    out, width, height, cmd.matrix, cmd.clip,
                    cmd.x, cmd.y + metrics.lineHeight - thickness,
                    penX, cmd.y + metrics.lineHeight,
                    decorColor,
                )
            }
            if (style.isStrikethrough) {
                val center = cmd.y + metrics.lineHeight * 0.5f
                fillDecorRect(
                    out, width, height, cmd.matrix, cmd.clip,
                    cmd.x, center - thickness * 0.5f,
                    penX, center + thickness * 0.5f,
                    decorColor,
                )
            }
        }
    }

    /**
     * 字形位图逆映射混入:四角(已含斜体剪切,为平行四边形)经命令矩阵变换到
     * 图层空间,包围盒内像素中心逆解 (u,v),双线性采样 coverage × alpha 后
     * SrcOver 混合。矩阵列主序语义与 [fillShapeTriangles] 一致。
     */
    private fun blitGlyphQuad(
        out: IntArray, width: Int, height: Int,
        matrix: FloatArray, clip: Rect?,
        tlx: Float, tly: Float, trx: Float, tryY: Float,
        blx: Float, bly: Float, brx: Float, bry: Float,
        bytes: ByteArray, bw: Int, bh: Int,
        rgb: Int, alphaByte: Int,
    ) {
        fun tx(x: Float, y: Float): Float = x * matrix[0] + y * matrix[4] + matrix[12]
        fun ty(x: Float, y: Float): Float = x * matrix[1] + y * matrix[5] + matrix[13]
        val ax = tx(tlx, tly); val ay = ty(tlx, tly)
        val bx = tx(trx, tryY); val by = ty(trx, tryY)
        val dx = tx(blx, bly); val dy = ty(blx, bly)
        // BR 仅参与包围盒(平行四边形由 U/V 两边张成)
        val cx = tx(brx, bry); val cy = ty(brx, bry)

        val exU = bx - ax; val eyU = by - ay
        val exV = dx - ax; val eyV = dy - ay
        val det = exU * eyV - eyU * exV
        if (det == 0f) return
        val invDet = 1f / det
        val invUx = eyV * invDet; val invUy = -exV * invDet
        val invVx = -eyU * invDet; val invVy = exU * invDet

        val minX = max(0, min(ax, min(bx, min(cx, dx))).toInt())
        val maxX = min(width - 1, max(ax, max(bx, max(cx, dx))).toInt())
        val minY = max(0, min(ay, min(by, min(cy, dy))).toInt())
        val maxY = min(height - 1, max(ay, max(by, max(cy, dy))).toInt())
        if (minX > maxX || minY > maxY) return

        for (py in minY..maxY) {
            val y = py + 0.5f
            var idx = py * width + minX
            for (px in minX..maxX) {
                val x = px + 0.5f
                if (clip != null && (x < clip.left || x > clip.right || y < clip.top || y > clip.bottom)) {
                    idx++
                    continue
                }
                val rx = x - ax; val ry = y - ay
                val u = rx * invUx + ry * invUy
                val v = rx * invVx + ry * invVy
                if (u < 0f || v < 0f || u > 1f || v > 1f) {
                    idx++
                    continue
                }
                val cov = coverageAt(bytes, bw, bh, u, v)
                val a = (cov * alphaByte + 0.5f).toInt().coerceIn(0, 255)
                if (a > 0) {
                    out[idx] = blend(out[idx], (a shl 24) or rgb)
                }
                idx++
            }
        }
    }

    /** coverage 双线性采样(u,v ∈ 0..1 → 位图纹素中心空间) */
    private fun coverageAt(bytes: ByteArray, w: Int, h: Int, u: Float, v: Float): Float {
        val x = (u * w - 0.5f).coerceIn(0f, (w - 1).toFloat())
        val y = (v * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = x - x0; val fy = y - y0
        val c00 = bytes[y0 * w + x0].toInt() and 0xFF
        val c10 = bytes[y0 * w + x1].toInt() and 0xFF
        val c01 = bytes[y1 * w + x0].toInt() and 0xFF
        val c11 = bytes[y1 * w + x1].toInt() and 0xFF
        val top = c00 + (c10 - c00) * fx
        val bottom = c01 + (c11 - c01) * fx
        return (top + (bottom - top) * fy) / 255f
    }

    /** 装饰线矩形:两三角形经命令矩阵变换填充(复用实心三角形管线;coverage=1 实心) */
    private fun fillDecorRect(
        out: IntArray, width: Int, height: Int,
        matrix: FloatArray, clip: Rect?,
        l: Float, t: Float, r: Float, b: Float,
        argb: Int,
    ) {
        val data = floatArrayOf(
            l, t, 1f, r, t, 1f, r, b, 1f,
            l, t, 1f, r, b, 1f, l, b, 1f,
        )
        fillShapeTriangles(out, width, height, matrix, clip, data, 6, argb, 1f)
    }

    /** 命令矩阵(列主序 4x4)2D 部分的最大轴缩放(与 GUI 端同式) */
    private fun matrixScale(m: FloatArray): Float {
        val scaleX = sqrt(m[0] * m[0] + m[1] * m[1])
        val scaleY = sqrt(m[4] * m[4] + m[5] * m[5])
        return max(scaleX, scaleY)
    }

    override fun drawGradientRect(cmd: DrawGradientRectCommand) {
        fillGradient(out, width, height, cmd)
    }

    override fun drawShadow(cmd: DrawShadowCommand) = Unit // 不支持,跳过

    override fun drawImageRect(cmd: DrawImageRectCommand) {
        fillImage(out, width, height, cmd)
    }

    override fun drawVertices(cmd: DrawVerticesCommand) {
        fillVertices(out, width, height, cmd)
    }

    override fun drawCustom(cmd: DrawCustomCommand) = Unit

    // ── 形状:三角化 → 逐三角形重心填充 ─────────────────────────────────────

    private fun tessellate(
        out: IntArray, width: Int, height: Int,
        cmd: androidx.compose.ui.graphics.MinecraftCanvas.DrawCommand,
        tessellate: (Sink) -> Unit,
    ) {
        val sink = Sink()
        tessellate(sink)
        val data = sink.toArray()
        val paint = cmd.paint ?: return
        val shader = paint.shader
        if (shader != null) {
            val vertexColors = RasterGradientSampler.gradientVertexColors(shader, data, sink.vertexCount, paint.alpha)
            fillShapeTrianglesWithColors(
                out, width, height,
                cmd.matrix, cmd.clip, data, sink.vertexCount,
                vertexColors,
            )
        } else {
            fillShapeTriangles(
                out, width, height,
                cmd.matrix, cmd.clip, data, sink.vertexCount,
                paint.color.toArgbInt(), paint.alpha,
            )
        }
    }

    private fun fillShape(
        out: IntArray, width: Int, height: Int,
        matrix: FloatArray, clip: Rect?, sink: Sink,
        color: androidx.compose.ui.graphics.Color, alpha: Float,
    ) {
        val data = sink.toArray()
        fillShapeTriangles(out, width, height, matrix, clip, data, sink.vertexCount, color.toArgbInt(), alpha)
    }

    private fun fillShapeTriangles(
        out: IntArray, width: Int, height: Int,
        matrix: FloatArray, clip: Rect?,
        data: FloatArray, vertexCount: Int,
        colorArgb: Int, alphaMul: Float,
    ) {
        if (vertexCount < 3) return
        val m00 = matrix[0]
        val m10 = matrix[1]
        val m01 = matrix[4]
        val m11 = matrix[5]
        val m20 = matrix[12]
        val m21 = matrix[13]

        var i = 0
        val n = vertexCount / 3
        repeat(n) {
            val ax = data[i] * m00 + data[i + 1] * m01 + m20
            val ay = data[i] * m10 + data[i + 1] * m11 + m21
            val ac = data[i + 2]
            val bx = data[i + 3] * m00 + data[i + 4] * m01 + m20
            val by = data[i + 3] * m10 + data[i + 4] * m11 + m21
            val bc = data[i + 5]
            val cx = data[i + 6] * m00 + data[i + 7] * m01 + m20
            val cy = data[i + 6] * m10 + data[i + 7] * m11 + m21
            val cc = data[i + 8]
            i += 9

            // 包围盒(裁剪到目标缓冲)
            val minX = max(0, min(ax, min(bx, cx)).toInt())
            val maxX = min(width - 1, max(ax, max(bx, cx)).toInt())
            val minY = max(0, min(ay, min(by, cy)).toInt())
            val maxY = min(height - 1, max(ay, max(by, cy)).toInt())
            if (minX > maxX || minY > maxY) return@repeat

            // 重心坐标预计算(顶点 A 为原点)
            val v0x = cx - ax
            val v0y = cy - ay
            val v1x = bx - ax
            val v1y = by - ay
            val dot00 = v0x * v0x + v0y * v0y
            val dot01 = v0x * v1x + v0y * v1y
            val dot11 = v1x * v1x + v1y * v1y
            val denom = dot00 * dot11 - dot01 * dot01
            if (denom == 0f) return@repeat
            val invDenom = 1f / denom

            for (py in minY..maxY) {
                val y = py + 0.5f
                var idx = py * width + minX
                for (px in minX..maxX) {
                    val x = px + 0.5f
                    val v2x = x - ax
                    val v2y = y - ay
                    val dot02 = v0x * v2x + v0y * v2y
                    val dot12 = v1x * v2x + v1y * v2y
                    // u = C 权重,v = B 权重,w = A 权重
                    val u = (dot11 * dot02 - dot01 * dot12) * invDenom
                    val v = (dot00 * dot12 - dot01 * dot02) * invDenom
                    if (u < 0f || v < 0f || u + v > 1f) {
                        idx++
                        continue
                    }
                    val w = 1f - u - v
                    // clip:像素中心在记录坐标(= 图层坐标)直接判定
                    if (clip != null && (x < clip.left || x > clip.right || y < clip.top || y > clip.bottom)) {
                        idx++
                        continue
                    }
                    // coverage 插值 → alpha(与 GPU 一致的 AA 过渡)
                    val cov = ac * w + bc * v + cc * u
                    val a = (cov.coerceIn(0f, 1f) * alphaMul * 255f + 0.5f).toInt().coerceIn(0, 255)
                    if (a > 0) {
                        out[idx] = blend(out[idx], (colorArgb and 0x00FFFFFF) or (a shl 24))
                    }
                    idx++
                }
            }
        }
    }

    // ── 渐变着色器顶点色填充 ───────────────────────────────────────────────

    private fun fillShapeTrianglesWithColors(
        out: IntArray, width: Int, height: Int,
        matrix: FloatArray, clip: Rect?,
        data: FloatArray, vertexCount: Int,
        vertexColors: IntArray,
    ) {
        if (vertexCount < 3) return
        val m00 = matrix[0]
        val m10 = matrix[1]
        val m01 = matrix[4]
        val m11 = matrix[5]
        val m20 = matrix[12]
        val m21 = matrix[13]

        var i = 0
        var vi = 0
        val n = vertexCount / 3
        repeat(n) {
            val ax = data[i] * m00 + data[i + 1] * m01 + m20
            val ay = data[i] * m10 + data[i + 1] * m11 + m21
            val ac = data[i + 2]
            val acol = vertexColors[vi]
            val bx = data[i + 3] * m00 + data[i + 4] * m01 + m20
            val by = data[i + 3] * m10 + data[i + 4] * m11 + m21
            val bc = data[i + 5]
            val bcol = vertexColors[vi + 1]
            val cx = data[i + 6] * m00 + data[i + 7] * m01 + m20
            val cy = data[i + 6] * m10 + data[i + 7] * m11 + m21
            val cc = data[i + 8]
            val ccol = vertexColors[vi + 2]
            i += 9
            vi += 3

            val minX = max(0, min(ax, min(bx, cx)).toInt())
            val maxX = min(width - 1, max(ax, max(bx, cx)).toInt())
            val minY = max(0, min(ay, min(by, cy)).toInt())
            val maxY = min(height - 1, max(ay, max(by, cy)).toInt())
            if (minX > maxX || minY > maxY) return@repeat

            val v0x = cx - ax
            val v0y = cy - ay
            val v1x = bx - ax
            val v1y = by - ay
            val dot00 = v0x * v0x + v0y * v0y
            val dot01 = v0x * v1x + v0y * v1y
            val dot11 = v1x * v1x + v1y * v1y
            val denom = dot00 * dot11 - dot01 * dot01
            if (denom == 0f) return@repeat
            val invDenom = 1f / denom

            val ar = acol shr 16 and 0xFF
            val ag = acol shr 8 and 0xFF
            val ab = acol and 0xFF
            val aa = acol ushr 24 and 0xFF
            val br = bcol shr 16 and 0xFF
            val bg = bcol shr 8 and 0xFF
            val bb = bcol and 0xFF
            val ba = bcol ushr 24 and 0xFF
            val cr = ccol shr 16 and 0xFF
            val cg = ccol shr 8 and 0xFF
            val cb = ccol and 0xFF
            val ca = ccol ushr 24 and 0xFF

            for (py in minY..maxY) {
                val y = py + 0.5f
                var idx = py * width + minX
                for (px in minX..maxX) {
                    val x = px + 0.5f
                    val v2x = x - ax
                    val v2y = y - ay
                    val dot02 = v0x * v2x + v0y * v2y
                    val dot12 = v1x * v2x + v1y * v2y
                    val u = (dot11 * dot02 - dot01 * dot12) * invDenom
                    val v = (dot00 * dot12 - dot01 * dot02) * invDenom
                    if (u < 0f || v < 0f || u + v > 1f) {
                        idx++
                        continue
                    }
                    val w = 1f - u - v
                    if (clip != null && (x < clip.left || x > clip.right || y < clip.top || y > clip.bottom)) {
                        idx++
                        continue
                    }
                    val cov = ac * w + bc * v + cc * u
                    val a = (cov.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    if (a == 0) {
                        idx++
                        continue
                    }
                    val r = (ar * w + br * v + cr * u).toInt().coerceIn(0, 255)
                    val g = (ag * w + bg * v + cg * u).toInt().coerceIn(0, 255)
                    val bl = (ab * w + bb * v + cb * u).toInt().coerceIn(0, 255)
                    out[idx] = blend(out[idx], (a shl 24) or (r shl 16) or (g shl 8) or bl)
                    idx++
                }
            }
        }
    }

    // ── 图片:逆矩阵采样 ─────────────────────────────────────────────────────

    private fun fillImage(out: IntArray, width: Int, height: Int, cmd: DrawImageRectCommand) {
        val m = cmd.matrix
        val m00 = m[0]
        val m10 = m[1]
        val m01 = m[4]
        val m11 = m[5]
        val m20 = m[12]
        val m21 = m[13]
        val det = m00 * m11 - m01 * m10
        if (det == 0f) return
        val inv00 = m11 / det
        val inv01 = -m01 / det
        val inv10 = -m10 / det
        val inv11 = m00 / det
        val inv20 = (m01 * m21 - m11 * m20) / det
        val inv21 = (m10 * m20 - m00 * m21) / det

        val src = cmd.image.buffer
        val imgW = cmd.image.width
        val imgH = cmd.image.height
        val x0 = cmd.dstOffsetX.toFloat()
        val y0 = cmd.dstOffsetY.toFloat()
        val x1 = (cmd.dstOffsetX + cmd.dstWidth).toFloat()
        val y1 = (cmd.dstOffsetY + cmd.dstHeight).toFloat()
        val dw = x1 - x0
        val dh = y1 - y0
        if (dw <= 0f || dh <= 0f) return
        val srcX = cmd.srcOffsetX
        val srcY = cmd.srcOffsetY
        val srcW = cmd.srcWidth
        val srcH = cmd.srcHeight
        val alphaMul = cmd.paint.alpha
        val linear = cmd.paint.filterQuality != FilterQuality.None
        val clip = cmd.clip

        for (py in 0 until height) {
            val y = py + 0.5f
            var idx = py * width
            for (px in 0 until width) {
                val x = px + 0.5f
                if (clip != null && (x < clip.left || x > clip.right || y < clip.top || y > clip.bottom)) {
                    idx++
                    continue
                }
                // 逆变换到命令局部坐标
                val lx = inv00 * x + inv01 * y + inv20
                val ly = inv10 * x + inv11 * y + inv21
                if (lx < x0 || lx >= x1 || ly < y0 || ly >= y1) {
                    idx++
                    continue
                }
                val u = (lx - x0) / dw * srcW + srcX
                val v = (ly - y0) / dh * srcH + srcY
                val pixel =
                    if (linear) {
                        bilinear(src, imgW, imgH, u, v)
                    } else {
                        nearest(src, imgW, imgH, u, v)
                    }
                val a = (((pixel ushr 24) and 0xFF) * alphaMul + 0.5f).toInt().coerceIn(0, 255)
                if (a > 0) {
                    out[idx] = blend(out[idx], (pixel and 0x00FFFFFF) or (a shl 24))
                }
                idx++
            }
        }
    }

    private fun nearest(src: IntArray, imgW: Int, imgH: Int, u: Float, v: Float): Int {
        val ix = u.toInt().coerceIn(0, imgW - 1)
        val iy = v.toInt().coerceIn(0, imgH - 1)
        return src[iy * imgW + ix]
    }

    private fun bilinear(src: IntArray, imgW: Int, imgH: Int, u: Float, v: Float): Int {
        val x = u.coerceIn(0f, (imgW - 1).toFloat())
        val y = v.coerceIn(0f, (imgH - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, imgW - 1)
        val y1 = min(y0 + 1, imgH - 1)
        val fx = x - x0
        val fy = y - y0
        val p00 = src[y0 * imgW + x0]
        val p10 = src[y0 * imgW + x1]
        val p01 = src[y1 * imgW + x0]
        val p11 = src[y1 * imgW + x1]
        val a00 = (1f - fx) * (1f - fy)
        val a10 = fx * (1f - fy)
        val a01 = (1f - fx) * fy
        val a11 = fx * fy
        val r = (p00 ushr 16 and 0xFF) * a00 + (p10 ushr 16 and 0xFF) * a10 +
                (p01 ushr 16 and 0xFF) * a01 + (p11 ushr 16 and 0xFF) * a11
        val g = (p00 ushr 8 and 0xFF) * a00 + (p10 ushr 8 and 0xFF) * a10 +
                (p01 ushr 8 and 0xFF) * a01 + (p11 ushr 8 and 0xFF) * a11
        val b = (p00 and 0xFF) * a00 + (p10 and 0xFF) * a10 +
                (p01 and 0xFF) * a01 + (p11 and 0xFF) * a11
        val a = (p00 ushr 24 and 0xFF) * a00 + (p10 ushr 24 and 0xFF) * a10 +
                (p01 ushr 24 and 0xFF) * a01 + (p11 ushr 24 and 0xFF) * a11
        return (a.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    // ── 顶点渐变:按 vertexMode + 索引展开三角形,逐顶点色重心插值 ───────────

    private fun fillVertices(out: IntArray, width: Int, height: Int, cmd: DrawVerticesCommand) {
        val positions = cmd.positions
        val srcColors = cmd.colors
        val indices = cmd.indices
        val vc = positions.size / 2
        if (vc < 3) return
        // 顶点色 × paint.alpha(与 GPU 回放 scaleAlpha 一致)
        val alphaMul = cmd.paint?.alpha ?: 1f
        fun scaled(argb: Int): Int {
            val a = (((argb ushr 24) and 0xFF) * alphaMul + 0.5f).toInt().coerceIn(0, 255)
            return (argb and 0x00FFFFFF) or (a shl 24)
        }

        // 三角形顶点索引展开(与 GPU 回放同一套逻辑)
        val tris = ArrayList<Int>(vc)
        when (cmd.vertexMode) {
            VertexMode.Triangles     -> {
                if (indices.isNotEmpty()) {
                    var i = 0
                    while (i + 2 < indices.size) {
                        tris += indices[i].toInt(); tris += indices[i + 1].toInt(); tris += indices[i + 2].toInt()
                        i += 3
                    }
                } else {
                    var i = 0
                    while (i + 2 < vc) {
                        tris += i; tris += i + 1; tris += i + 2
                        i += 3
                    }
                }
            }

            VertexMode.TriangleStrip -> {
                for (i in 0 until vc - 2) {
                    tris += i; tris += i + 1; tris += i + 2
                }
            }

            VertexMode.TriangleFan   -> {
                for (i in 1 until vc - 1) {
                    tris += 0; tris += i; tris += i + 1
                }
            }
        }
        if (tris.size < 9) return

        val m = cmd.matrix
        val m00 = m[0];
        val m10 = m[1];
        val m01 = m[4];
        val m11 = m[5];
        val m20 = m[12];
        val m21 = m[13]
        val clip = cmd.clip
        var k = 0
        while (k + 2 < tris.size) {
            val ai = tris[k];
            val bi = tris[k + 1];
            val ci = tris[k + 2]; k += 3
            val ax = positions[ai * 2] * m00 + positions[ai * 2 + 1] * m01 + m20
            val ay = positions[ai * 2] * m10 + positions[ai * 2 + 1] * m11 + m21
            val bx = positions[bi * 2] * m00 + positions[bi * 2 + 1] * m01 + m20
            val by = positions[bi * 2] * m10 + positions[bi * 2 + 1] * m11 + m21
            val cx = positions[ci * 2] * m00 + positions[ci * 2 + 1] * m01 + m20
            val cy = positions[ci * 2] * m10 + positions[ci * 2 + 1] * m11 + m21
            fillGradientTriangle(
                out, width, height, clip,
                ax, ay, scaled(srcColors[ai]),
                bx, by, scaled(srcColors[bi]),
                cx, cy, scaled(srcColors[ci]),
            )
        }
    }

    // ── 渐变矩形:双三角形 + 顶点色重心插值 ─────────────────────────────────

    private fun fillGradient(out: IntArray, width: Int, height: Int, cmd: DrawGradientRectCommand) {
        val m = cmd.matrix
        val m00 = m[0]
        val m10 = m[1]
        val m01 = m[4]
        val m11 = m[5]
        val m20 = m[12]
        val m21 = m[13]
        val clip = cmd.clip
        val l = cmd.left
        val t = cmd.top
        val r = cmd.right
        val b = cmd.bottom

        // 顶点(局部)→ 图层坐标;颜色:顶部/底部
        val ax = l * m00 + t * m01 + m20
        val ay = l * m10 + t * m11 + m21
        val bx = r * m00 + t * m01 + m20
        val by = r * m10 + t * m11 + m21
        val cx = r * m00 + b * m01 + m20
        val cy = r * m10 + b * m11 + m21
        val dx = l * m00 + b * m01 + m20
        val dy = l * m10 + b * m11 + m21
        val cTop = cmd.topColorArgb
        val cBottom = cmd.bottomColorArgb

        // 三角形 1:(a,b,c) 三角形 2:(a,c,d);顶点色按重心插值
        fillGradientTriangle(out, width, height, clip, ax, ay, cTop, bx, by, cTop, cx, cy, cBottom)
        fillGradientTriangle(out, width, height, clip, ax, ay, cTop, cx, cy, cBottom, dx, dy, cBottom)
    }

    private fun fillGradientTriangle(
        out: IntArray, width: Int, height: Int, clip: Rect?,
        ax: Float, ay: Float, ac: Int,
        bx: Float, by: Float, bc: Int,
        cx: Float, cy: Float, cc: Int,
    ) {
        val minX = max(0, min(ax, min(bx, cx)).toInt())
        val maxX = min(width - 1, max(ax, max(bx, cx)).toInt())
        val minY = max(0, min(ay, min(by, cy)).toInt())
        val maxY = min(height - 1, max(ay, max(by, cy)).toInt())
        if (minX > maxX || minY > maxY) return

        val v0x = cx - ax
        val v0y = cy - ay
        val v1x = bx - ax
        val v1y = by - ay
        val dot00 = v0x * v0x + v0y * v0y
        val dot01 = v0x * v1x + v0y * v1y
        val dot11 = v1x * v1x + v1y * v1y
        val denom = dot00 * dot11 - dot01 * dot01
        if (denom == 0f) return
        val invDenom = 1f / denom

        val ar = ac shr 16 and 0xFF
        val ag = ac shr 8 and 0xFF
        val ab = ac and 0xFF
        val aa = ac ushr 24 and 0xFF
        val br = bc shr 16 and 0xFF
        val bg = bc shr 8 and 0xFF
        val bb = bc and 0xFF
        val ba = bc ushr 24 and 0xFF
        val cr = cc shr 16 and 0xFF
        val cg = cc shr 8 and 0xFF
        val cb = cc and 0xFF
        val ca = cc ushr 24 and 0xFF

        for (py in minY..maxY) {
            val y = py + 0.5f
            var idx = py * width + minX
            for (px in minX..maxX) {
                val x = px + 0.5f
                val v2x = x - ax
                val v2y = y - ay
                val dot02 = v0x * v2x + v0y * v2y
                val dot12 = v1x * v2x + v1y * v2y
                val u = (dot11 * dot02 - dot01 * dot12) * invDenom
                val v = (dot00 * dot12 - dot01 * dot02) * invDenom
                if (u < 0f || v < 0f || u + v > 1f) {
                    idx++
                    continue
                }
                val w = 1f - u - v
                if (clip != null && (x < clip.left || x > clip.right || y < clip.top || y > clip.bottom)) {
                    idx++
                    continue
                }
                val a = (aa * w + ba * v + ca * u).toInt().coerceIn(0, 255)
                if (a == 0) {
                    idx++
                    continue
                }
                val r = (ar * w + br * v + cr * u).toInt().coerceIn(0, 255)
                val g = (ag * w + bg * v + cg * u).toInt().coerceIn(0, 255)
                val bl = (ab * w + bb * v + cb * u).toInt().coerceIn(0, 255)
                out[idx] = blend(out[idx], (a shl 24) or (r shl 16) or (g shl 8) or bl)
                idx++
            }
        }
    }

    // ── 混合 ────────────────────────────────────────────────────────────────

    /** SRC_OVER(straight alpha):dst = src 覆盖 dst */
    private fun blend(dst: Int, src: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0) return dst
        if (sa == 255) return src
        val da = (dst ushr 24) and 0xFF
        val sr = (src ushr 16) and 0xFF
        val sg = (src ushr 8) and 0xFF
        val sb = src and 0xFF
        val dr = (dst ushr 16) and 0xFF
        val dg = (dst ushr 8) and 0xFF
        val db = dst and 0xFF
        val oa = sa + da * (255 - sa) / 255
        val inv = da * (255 - sa) / 255
        val or = (sr * sa + dr * inv) / max(oa, 1)
        val og = (sg * sa + dg * inv) / max(oa, 1)
        val ob = (sb * sa + db * inv) / max(oa, 1)
        return (oa shl 24) or (or shl 16) or (og shl 8) or ob
    }
}