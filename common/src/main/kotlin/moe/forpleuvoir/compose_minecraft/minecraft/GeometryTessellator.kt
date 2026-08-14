package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.MinecraftPath
import androidx.compose.ui.graphics.PointMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// 几何三角化器(第一版)
//
// 把 Compose 绘制命令的几何(局部坐标,场景 px,密度 1)三角化为
// [x, y] 平铺顶点序列 + 每顶点 coverage,交给 GuiTriangleRenderState
// 以 TRIANGLES 拓扑提交。
//
// 覆盖:
// - 填充:圆 / 椭圆 / 弧(useCenter 扇形、!useCenter 弓形)/ 圆角矩形(凸,
//   扇形即可)/ Path(简单多边形,耳切;自交、洞、EvenOdd 环绕规则第一版不支持)
// - 描边:圆 / 椭圆 / 弧 / 圆角矩形 / Path / 线段 —— 轮廓带展开(圆 join,
//   butt cap;与 Skia 的 miter join 视觉差异见类注释)
// - 点:PointMode.Points(方形点)/ Lines(线段)/ Polygon(多边形填充)
//
// 抗锯齿(Skia 同款 "coverage" 方案):
// - coverage = 顶点「到所属三角形轮廓边的屏幕像素距离」;轮廓边两端顶点为 0,
//   对侧顶点为到轮廓边的距离(× [Sink.aaScale] 换算成屏幕像素);
// - 片元着色器 clamp(coverage, 0, 1) 作 alpha 渐变 —— 边缘 1px 平滑;
// - 内部三角形 / 含 2 条以上轮廓边的三角形(角部)为实心(无 AA),
//   与 Skia 的 edge-triangle 处理一致,角部轻微锯齿可接受;
// - 方形点(Points mode)四边全为轮廓,按实心处理。
//
// 已知简化(与 Skia 语义差异,文档记录):
// - join 统一用圆 join(避免 miter 尖刺;Paint 快照未携带 strokeJoin);
// - cap 统一 butt(Paint 快照未携带 strokeCap;Compose Paint 默认即 Butt);
// - strokeWidth 在局部坐标展开,矩阵缩放会等比影响线宽(未做设备空间补偿);
// - strokeWidth == 0 按 hairline 1px 处理;
// - 三角形绕序统一视觉顺时针;pipeline 侧已关闭背面剔除,不依赖绕序正确性;
// - aaScale 用矩阵最大轴缩放 × guiScale,非均匀缩放下 AA 宽度在次轴略偏。
// ─────────────────────────────────────────────────────────────────────────────

internal object GeometryTessellator {

    /** 内部(无轮廓边)三角形的 coverage 大数:clamp 后保持实心 */
    private const val OPAQUE = 1e4f

    /** 扁平 [x, y] 顶点缓冲 + 逐顶点 coverage(像素距离) */
    class Sink {
        /**
         * 局部坐标 → 屏幕像素的缩放系数(矩阵最大轴缩放 × guiScale),
         * 由回放端在每命令前设置;coverage 距离按它换算。
         */
        var aaScale: Float = 1f

        private val vertices = ArrayList<Float>(64)
        private val coverage = ArrayList<Float>(32)

        val vertexCount: Int get() = vertices.size / 2

        fun vertex(x: Float, y: Float, cov: Float = OPAQUE) {
            vertices.add(x)
            vertices.add(y)
            coverage.add(cov)
        }

        /** 实心三角形(内部或无 AA 需求) */
        fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
            vertex(x0, y0); vertex(x1, y1); vertex(x2, y2)
        }

        /** 带 coverage 的三角形(轮廓边 AA) */
        fun triangleAA(
            ax: Float, ay: Float, ac: Float,
            bx: Float, by: Float, bc: Float,
            cx: Float, cy: Float, cc: Float,
        ) {
            vertex(ax, ay, ac); vertex(bx, by, bc); vertex(cx, cy, cc)
        }

        /** 实心四边形(两个三角形) */
        fun quad(
            ax: Float, ay: Float,
            bx: Float, by: Float,
            cx: Float, cy: Float,
            dx: Float, dy: Float,
        ) {
            triangle(ax, ay, bx, by, cx, cy)
            triangle(ax, ay, cx, cy, dx, dy)
        }

        /** 实心凸多边形扇形(以 [cx, cy] 为中心,顶点序列 [points] 平铺 [x,y],闭合循环) */
        fun fan(cx: Float, cy: Float, points: FloatArray) {
            val n = points.size / 2
            if (n < 3) return
            for (j in 1..n) {
                val a = (j - 1) % n
                val b = j % n
                triangle(cx, cy, points[a * 2], points[a * 2 + 1], points[b * 2], points[b * 2 + 1])
            }
        }

        /**
         * 凸多边形轮廓扇形(带 AA)。
         *
         * 锚点取**顶点平均**(凸多边形内部点,避免锚点落在轮廓上导致
         * 相邻两条轮廓边失去 AA);闭合循环保证每条轮廓边(含首尾闭合边)
         * 都对应一个「两端 coverage 0、锚点为到边距离」的 AA 三角形。
         * 另对每个凸轮廓顶点生成角帽,填补顶点处 coverage=0 的 1px 缺口。
         */
        fun fanAA(points: FloatArray) {
            val n = points.size / 2
            if (n < 3) return
            var cx = 0f
            var cy = 0f
            for (i in 0 until n) {
                cx += points[i * 2]
                cy += points[i * 2 + 1]
            }
            cx /= n
            cy /= n

            // 凸角角帽:顶点像素缺口填补(coverage 从顶点 1 渐变到外侧 0)
            for (i in 0 until n) {
                val prev = (i - 1 + n) % n
                val next = (i + 1) % n
                val nIn = outerNormal(
                    points[i * 2], points[i * 2 + 1],
                    points[prev * 2], points[prev * 2 + 1],
                    cx, cy,
                )
                val nOut = outerNormal(
                    points[i * 2], points[i * 2 + 1],
                    points[next * 2], points[next * 2 + 1],
                    cx, cy,
                )
                cornerCap(points[i * 2], points[i * 2 + 1], nIn, nOut, this)
            }

            for (j in 1..n) {
                val a = (j - 1) % n
                val b = j % n
                val ax = points[a * 2]
                val ay = points[a * 2 + 1]
                val bx = points[b * 2]
                val by = points[b * 2 + 1]
                val d = distToLine(cx, cy, ax, ay, bx, by) * aaScale
                triangleAA(ax, ay, 0f, bx, by, 0f, cx, cy, d)
            }
        }

        fun toArray(): FloatArray = vertices.toFloatArray()

        fun toCoverageArray(): FloatArray = coverage.toFloatArray()

        fun clear() {
            vertices.clear()
            coverage.clear()
        }
    }

    // ── 圆 / 椭圆 ──────────────────────────────────────────────────────────

    /** 圆。细分段数按屏幕像素自适应(每段弦长约 6 屏幕像素) */
    fun circle(cx: Float, cy: Float, radius: Float, fill: Boolean, strokeWidth: Float, sink: Sink) {
        val r = radius.coerceAtLeast(0f)
        if (r <= 0f) return
        val segments = circleSegments(r, sink.aaScale)
        if (fill) {
            val pts = FloatArray(segments * 2)
            for (i in 0 until segments) {
                val a = i * 2.0 * PI / segments
                pts[i * 2] = cx + r * cos(a).toFloat()
                pts[i * 2 + 1] = cy + r * sin(a).toFloat()
            }
            sink.fanAA(pts)
        } else {
            val h = effectiveWidth(strokeWidth) / 2f
            ring(cx, cy, r + h, r + h, r - h, r - h, 0.0, 2.0 * PI, segments, sink)
        }
    }

    /** 椭圆(轴对齐)。 */
    fun oval(left: Float, top: Float, right: Float, bottom: Float, fill: Boolean, strokeWidth: Float, sink: Sink) {
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        if (rx <= 0f || ry <= 0f) return
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val segments = circleSegments(max(rx, ry), sink.aaScale)
        if (fill) {
            val pts = FloatArray(segments * 2)
            for (i in 0 until segments) {
                val a = i * 2.0 * PI / segments
                pts[i * 2] = cx + rx * cos(a).toFloat()
                pts[i * 2 + 1] = cy + ry * sin(a).toFloat()
            }
            sink.fanAA(pts)
        } else {
            val h = effectiveWidth(strokeWidth) / 2f
            ring(cx, cy, rx + h, ry + h, rx - h, ry - h, 0.0, 2.0 * PI, segments, sink)
        }
    }

    // ── 弧 ─────────────────────────────────────────────────────────────────

    /**
     * 椭圆弧。startAngle/sweepAngle 单位为度,0° = 3 点钟,正 sweep 顺时针(y-down)。
     * fill + useCenter → 扇形;fill + !useCenter → 弓形(弦闭合,凸多边形);
     * stroke → 弧带(两端 butt cap)。
     */
    fun arc(
        left: Float, top: Float, right: Float, bottom: Float,
        startAngleDeg: Float, sweepAngleDeg: Float, useCenter: Boolean,
        fill: Boolean, strokeWidth: Float, sink: Sink,
    ) {
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        if (rx <= 0f || ry <= 0f || sweepAngleDeg == 0f) return
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val sweep = sweepAngleDeg * PI / 180.0
        val segments = max(4, ceil(abs(sweep) / (2.0 * PI) * circleSegments(max(rx, ry), sink.aaScale)).toInt())

        if (fill) {
            if (useCenter) {
                // 扇形:中心 + 弧点(含首尾)闭合多边形;全部边(弧段 + 两条半径线)经 fanAA AA
                val pts = FloatArray((segments + 2) * 2)
                for (i in 0..segments) {
                    val a = sweep * i / segments
                    pts[i * 2] = cx + rx * cos(a).toFloat()
                    pts[i * 2 + 1] = cy + ry * sin(a).toFloat()
                }
                pts[(segments + 1) * 2] = cx
                pts[(segments + 1) * 2 + 1] = cy
                sink.fanAA(pts)
            } else {
                // 弓形:弧点(含首尾)闭合多边形;全部边(弧段 + 弦)经 fanAA AA
                val pts = FloatArray((segments + 1) * 2)
                for (i in 0..segments) {
                    val a = sweep * i / segments
                    pts[i * 2] = cx + rx * cos(a).toFloat()
                    pts[i * 2 + 1] = cy + ry * sin(a).toFloat()
                }
                sink.fanAA(pts)
            }
        } else {
            val h = effectiveWidth(strokeWidth) / 2f
            ring(
                cx, cy, rx + h, ry + h, rx - h, ry - h,
                0.0, sweep, segments, sink,
            )
        }
    }

    // ── 圆角矩形 ───────────────────────────────────────────────────────────

    /**
     * 圆角矩形(半径钳制到半宽/半高)。fill → 凸轮廓扇形(AA);
     * stroke → 轮廓带(圆角处自然由细分提供圆 join,带 AA)。
     */
    fun roundRect(
        left: Float, top: Float, right: Float, bottom: Float,
        radiusX: Float, radiusY: Float,
        fill: Boolean, strokeWidth: Float, sink: Sink,
    ) {
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return
        val rx = radiusX.coerceIn(0f, w / 2f)
        val ry = radiusY.coerceIn(0f, h / 2f)
        if (rx <= 0f || ry <= 0f) {
            // 退化为矩形(与既有 DrawRectCommand 路径一致)
            sink.quad(left, top, right, top, right, bottom, left, bottom)
            return
        }

        // 轮廓点:从 (left + rx, top) 出发,顺时针(视觉),四段直线 + 四个 1/4 椭圆;
        // 圆角细分按屏幕像素(每段弦 ~6 屏幕像素)
        val cornerSegments = max(2, ceil(max(rx, ry) * sink.aaScale / 6f).toInt())
        val pts = ArrayList<Float>((cornerSegments * 4 + 4) * 2)

        fun corner(cx: Float, cy: Float, startAngle: Double) {
            for (i in 1..cornerSegments) {
                val a = startAngle + PI / 2.0 * i / cornerSegments
                pts.add(cx + rx * cos(a).toFloat())
                pts.add(cy + ry * sin(a).toFloat())
            }
        }

        pts.add(left + rx); pts.add(top)                 // 顶边起点
        pts.add(right - rx); pts.add(top)                // 顶边终点
        corner(right - rx, top + ry, -PI / 2.0)          // 右上角
        pts.add(right); pts.add(bottom - ry)             // 右边
        corner(right - rx, bottom - ry, 0.0)             // 右下角
        pts.add(left + rx); pts.add(bottom)              // 底边
        corner(left + rx, bottom - ry, PI / 2.0)         // 左下角
        pts.add(left); pts.add(top + ry)                 // 左边
        corner(left + rx, top + ry, PI)                  // 左上角

        val outline = pts.toFloatArray()
        if (fill) {
            // 凸多边形轮廓扇形(闭合,末点 == 首点):fanAA 逐边 AA + 角帽
            sink.fanAA(outline)
        } else {
            // 描边:去掉重复的闭合末点,保证闭合 join 完整
            strokeRing(outline.copyOfRange(0, outline.size - 2), closed = true, strokeWidth, sink)
        }
    }

    // ── 线段 ───────────────────────────────────────────────────────────────

    /** 线段:线宽展开(butt cap,与 Compose Paint 默认 StrokeCap.Butt 一致),带 AA + 端帽 */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, strokeWidth: Float, sink: Sink) {
        val w = effectiveWidth(strokeWidth)
        val h = w / 2f
        val dx = x2 - x1
        val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 1e-6f) {
            // 退化为点:按方形点处理
            squarePoint(x1, y1, h, sink)
            return
        }
        val ux = dx / len
        val uy = dy / len
        val nx = -uy * h
        val ny = ux * h
        val wPx = w * sink.aaScale
        // 四边形 (p0+n, p1+n, p1-n, p0-n):上/下两条轮廓边
        sink.triangleAA(x1 + nx, y1 + ny, 0f, x2 + nx, y2 + ny, 0f, x2 - nx, y2 - ny, wPx)
        sink.triangleAA(x2 - nx, y2 - ny, 0f, x1 - nx, y1 - ny, 0f, x1 + nx, y1 + ny, wPx)
        // 端帽(butt 端点凸角缺口):两端各两个角帽(轮廓法线 + 端线外法线)
        val unx = -uy
        val uny = ux
        cornerCap(x1 + nx, y1 + ny, floatArrayOf(unx, uny), floatArrayOf(-ux, -uy), sink)
        cornerCap(x1 - nx, y1 - ny, floatArrayOf(-unx, -uny), floatArrayOf(-ux, -uy), sink)
        cornerCap(x2 + nx, y2 + ny, floatArrayOf(unx, uny), floatArrayOf(ux, uy), sink)
        cornerCap(x2 - nx, y2 - ny, floatArrayOf(-unx, -uny), floatArrayOf(ux, uy), sink)
    }

    // ── Path ───────────────────────────────────────────────────────────────

    /**
     * Path 绘制。segments 为扁平化段(Move/Line/Quadratic/Cubic/Close)。
     * fill → 每子路径独立填充:凸多边形用 [Sink.fanAA](锚点内部,密集细分点
     *   也稳定);凹多边形用 [earClip](自交/洞/EvenOdd 第一版不支持)。
     *   平台适配点:earClip 的耳判定对「贝塞尔细分出的近共线密集点」会因叉积
     *   符号抖动而失败(找不到耳 → 兜底扇形输出错误三角形),因此凸路径必须
     *   走 fanAA —— 视觉形状不变,只换三角化策略;
     * stroke → 全部子路径的轮廓带(开放子路径 butt cap,闭合子路径圆 join)。
     */
    fun path(
        segments: List<MinecraftPath.PathSegmentData>,
        fill: Boolean,
        strokeWidth: Float,
        sink: Sink,
    ) {
        val subpaths = flatten(segments, sink.aaScale)
        if (fill) {
            for (subpath in subpaths) {
                if (subpath.points.size < 6) continue
                if (isConvex(subpath.points)) {
                    sink.fanAA(subpath.points)
                } else {
                    earClip(subpath.points, sink)
                }
            }
        } else {
            for (subpath in subpaths) {
                strokeRing(subpath.points, subpath.closed, strokeWidth, sink)
            }
        }
    }

    // ── 点 ─────────────────────────────────────────────────────────────────

    /** 点绘制(PointMode)。方形点(默认 Butt cap 语义,实心);Lines 两两成段;Polygon 闭合填充 */
    fun points(mode: PointMode, points: List<Offset>, strokeWidth: Float, sink: Sink) {
        if (points.isEmpty()) return
        val h = effectiveWidth(strokeWidth) / 2f
        when (mode) {
            PointMode.Points -> for (p in points) squarePoint(p.x, p.y, h, sink)
            PointMode.Lines -> {
                var i = 0
                while (i + 1 < points.size) {
                    val a = points[i]
                    val b = points[i + 1]
                    line(a.x, a.y, b.x, b.y, strokeWidth, sink)
                    i += 2
                }
            }
            PointMode.Polygon -> {
                if (points.size < 3) return
                val pts = FloatArray(points.size * 2)
                for (i in points.indices) {
                    pts[i * 2] = points[i].x
                    pts[i * 2 + 1] = points[i].y
                }
                earClip(pts, sink)
            }
        }
    }

    // ── 内部:细分 / 展开 / 耳切 ────────────────────────────────────────────

    private data class SubPath(val points: FloatArray, val closed: Boolean)

    /** 段序列 → 子路径点序列(二次/三次贝塞尔按 ~8 屏幕像素弦长自适应细分;圆弧已由 Path 预细分) */
    private fun flatten(segments: List<MinecraftPath.PathSegmentData>, aaScale: Float): List<SubPath> {
        val result = ArrayList<SubPath>()
        var current = ArrayList<Float>()
        var startX = 0f
        var startY = 0f
        var lastX = 0f
        var lastY = 0f
        var hasPoint = false

        fun flush(closed: Boolean) {
            if (current.size >= 6) {
                result.add(SubPath(current.toFloatArray(), closed))
            }
            current = ArrayList()
            hasPoint = false
        }

        for (segment in segments) {
            val p = segment.points
            when (segment.type) {
                MinecraftPath.PathSegmentType.Move -> {
                    flush(closed = false)
                    startX = p[0]; startY = p[1]
                    current.add(startX); current.add(startY)
                    lastX = startX; lastY = startY
                    hasPoint = true
                }
                MinecraftPath.PathSegmentType.Line -> {
                    if (!hasPoint) {
                        current.add(lastX); current.add(lastY); hasPoint = true
                    }
                    lastX = p[0]; lastY = p[1]
                    current.add(lastX); current.add(lastY)
                }
                MinecraftPath.PathSegmentType.Quadratic -> {
                    if (!hasPoint) {
                        current.add(lastX); current.add(lastY); hasPoint = true
                    }
                    val steps = curveSteps(lastX, lastY, p[0], p[1], p[2], p[3], aaScale)
                    for (i in 1..steps) {
                        val t = i.toFloat() / steps
                        val mt = 1f - t
                        val x = mt * mt * lastX + 2 * mt * t * p[0] + t * t * p[2]
                        val y = mt * mt * lastY + 2 * mt * t * p[1] + t * t * p[3]
                        current.add(x); current.add(y)
                    }
                    lastX = p[2]; lastY = p[3]
                }
                MinecraftPath.PathSegmentType.Cubic -> {
                    if (!hasPoint) {
                        current.add(lastX); current.add(lastY); hasPoint = true
                    }
                    val steps = curveSteps(lastX, lastY, p[0], p[1], p[2], p[3], aaScale)
                    for (i in 1..steps) {
                        val t = i.toFloat() / steps
                        val mt = 1f - t
                        val a = mt * mt * mt
                        val b = 3 * mt * mt * t
                        val c = 3 * mt * t * t
                        val d = t * t * t
                        current.add(a * lastX + b * p[0] + c * p[2] + d * p[4])
                        current.add(a * lastY + b * p[1] + c * p[3] + d * p[5])
                    }
                    lastX = p[4]; lastY = p[5]
                }
                MinecraftPath.PathSegmentType.Close -> {
                    // 平台适配点:仅在当前位置 != 起点时补闭合边;
                    // 若已回到起点,不添加重复点 —— 否则零长度闭合边会使
                    // strokeRing 闭合 join 的法线退化为零向量,首尾 join 丢失(裂缝)。
                    if (hasPoint && (lastX != startX || lastY != startY)) {
                        current.add(startX)
                        current.add(startY)
                    }
                    flush(closed = true)
                    lastX = startX
                    lastY = startY
                }
            }
        }
        flush(closed = false)
        return result
    }

    /** 贝塞尔细分段数:按控制多边形长度自适应(每段 ~8 屏幕像素) */
    private fun curveSteps(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, aaScale: Float): Int {
        val len = (dist(x0, y0, x1, y1) + dist(x1, y1, x2, y2)) * aaScale
        return max(1, ceil(len / 8f).toInt())
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))

    /** 点 [px, py] 到线段 (ax, ay)-(bx, by) 所在直线的距离 */
    private fun distToLine(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 1e-6f) return 0f
        return abs(dx * (ay - py) - dy * (ax - px)) / len
    }

    /**
     * 凸角角帽:覆盖轮廓顶点外侧的 1px 楔形,coverage 从顶点(1)渐变到
     * 外侧(0),填补「轮廓边端点 coverage=0」造成的顶点像素缺口。
     * [nIn]/[nOut] 为入边/出边的外侧单位法线(角平分线方向由二者确定)。
     */
    private fun cornerCap(
        px: Float, py: Float,
        nIn: FloatArray, nOut: FloatArray,
        sink: Sink,
    ) {
        val w = 1f / sink.aaScale
        if (w <= 0f) return
        val ax = px + nIn[0] * w
        val ay = py + nIn[1] * w
        val bx = px + nOut[0] * w
        val by = py + nOut[1] * w
        if (dist(ax, ay, bx, by) < 1e-4f) {
            // 入/出法线同向(直线顶点):单侧楔形(沿法线 + 垂直方向)
            sink.triangleAA(
                px, py, 1f,
                ax, ay, 0f,
                px - nIn[1] * w, py + nIn[0] * w, 0f,
            )
        } else {
            sink.triangleAA(px, py, 1f, ax, ay, 0f, bx, by, 0f)
        }
    }

    /** 顶点 [px, py] 处,边 (p→q) 的外侧单位法线(与径向 [anchor] 同向的候选) */
    private fun outerNormal(
        px: Float, py: Float, qx: Float, qy: Float,
        anchorX: Float, anchorY: Float,
    ): FloatArray {
        var dx = qx - px
        var dy = qy - py
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return floatArrayOf(0f, 0f)
        dx /= len
        dy /= len
        var nx = -dy
        var ny = dx
        val rx = px - anchorX
        val ry = py - anchorY
        if (nx * rx + ny * ry < 0f) {
            nx = -nx
            ny = -ny
        }
        return floatArrayOf(nx, ny)
    }

    /** 边 (p→q) 的外侧单位法线:[orientation] > 0 视觉顺时针(外侧 = (dy,-dx)),反之取反 */
    private fun edgeOuterNormal(px: Float, py: Float, qx: Float, qy: Float, orientation: Int): FloatArray {
        val dx = qx - px
        val dy = qy - py
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return floatArrayOf(0f, 0f)
        val s = if (orientation >= 0) 1f else -1f
        return floatArrayOf(dy / len * s, -dx / len * s)
    }

    private fun neg(n: FloatArray): FloatArray = floatArrayOf(-n[0], -n[1])

    /** 闭合圆/椭圆环:内外两条曲线之间的带(相邻点对四边形,不重叠;两端自然闭合) */
    private fun ring(
        cx: Float, cy: Float,
        outerRx: Float, outerRy: Float,
        innerRx: Float, innerRy: Float,
        startAngle: Double, sweep: Double,
        segments: Int,
        sink: Sink,
    ) {
        // 外轮廓与内轮廓点序列(segments + 1 点,首尾同角度)
        val outer = FloatArray((segments + 1) * 2)
        val inner = FloatArray((segments + 1) * 2)
        for (i in 0..segments) {
            val a = startAngle + sweep * i / segments
            outer[i * 2] = cx + outerRx * cos(a).toFloat()
            outer[i * 2 + 1] = cy + outerRy * sin(a).toFloat()
            inner[i * 2] = cx + innerRx * cos(a).toFloat()
            inner[i * 2 + 1] = cy + innerRy * sin(a).toFloat()
        }
        for (i in 0 until segments) {
            val j = i + 1
            // 四边形 (o_i, o_j, i_j, i_i):外轮廓边 o_i-o_j,内轮廓边 i_j-i_i
            val d1 = distToLine(inner[j * 2], inner[j * 2 + 1], outer[i * 2], outer[i * 2 + 1], outer[j * 2], outer[j * 2 + 1]) * sink.aaScale
            val d2 = distToLine(outer[i * 2], outer[i * 2 + 1], inner[j * 2], inner[j * 2 + 1], inner[i * 2], inner[i * 2 + 1]) * sink.aaScale
            sink.triangleAA(
                outer[i * 2], outer[i * 2 + 1], 0f,
                outer[j * 2], outer[j * 2 + 1], 0f,
                inner[j * 2], inner[j * 2 + 1], d1,
            )
            sink.triangleAA(
                outer[i * 2], outer[i * 2 + 1], d2,
                inner[j * 2], inner[j * 2 + 1], 0f,
                inner[i * 2], inner[i * 2 + 1], 0f,
            )
        }
        // 凸角角帽:填补分段点缺口(闭合环 0 until segments;开放弧带 0..segments 含两端)
        val closed = abs(sweep) >= 2.0 * PI - 1e-4
        val capCount = if (closed) segments else segments + 1
        for (i in 0 until capCount) {
            val ip = if (closed) (i - 1 + segments) % segments else i - 1
            val jp = min(i + 1, segments)
            val oi = i * 2
            val oj = jp * 2
            // 外轮廓角帽:入/出边外侧法线(选与径向同向);开放端点用 cap 边替代入边
            val nInO = if (ip >= 0) {
                outerNormal(outer[oi], outer[oi + 1], outer[ip * 2], outer[ip * 2 + 1], cx, cy)
            } else {
                outerNormal(outer[oi], outer[oi + 1], inner[oi], inner[oi + 1], cx, cy)
            }
            val nOutO = outerNormal(outer[oi], outer[oi + 1], outer[oj], outer[oj + 1], cx, cy)
            cornerCap(outer[oi], outer[oi + 1], nInO, nOutO, sink)
            // 内轮廓角帽(外侧 = 指向环带 = 径向向外)
            val nInI = if (ip >= 0) {
                outerNormal(inner[oi], inner[oi + 1], inner[ip * 2], inner[ip * 2 + 1], cx, cy)
            } else {
                outerNormal(inner[oi], inner[oi + 1], outer[oi], outer[oi + 1], cx, cy)
            }
            val nOutI = outerNormal(inner[oi], inner[oi + 1], inner[oj], inner[oj + 1], cx, cy)
            cornerCap(inner[oi], inner[oi + 1], nInI, nOutI, sink)
        }
    }

    /**
     * 轮廓带展开(任意多边形,圆 join,闭合路径无 cap,开放路径 butt cap)。
     * [pts] 为 [x,y] 平铺轮廓顶点;带的两条长边为轮廓边(AA),端边为内部连接边。
     */
    private fun strokeRing(pts: FloatArray, closed: Boolean, strokeWidth: Float, sink: Sink) {
        val n = pts.size / 2
        if (n < 2) return
        val h = effectiveWidth(strokeWidth) / 2f

        // 边法线(单位)
        val normals = Array(n) { FloatArray(2) }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = pts[j * 2] - pts[i * 2]
            val dy = pts[j * 2 + 1] - pts[i * 2 + 1]
            val len = sqrt(dx * dx + dy * dy)
            if (len > 1e-6f) {
                normals[i][0] = -dy / len
                normals[i][1] = dx / len
            }
        }

        var lastA: FloatArray? = null
        var lastB: FloatArray? = null

        fun emit(a: FloatArray, b: FloatArray) {
            val la = lastA
            val lb = lastB
            if (la != null && lb != null) {
                // 四边形 (la, a, b, lb):轮廓边 la-a(外)与 b-lb(内)
                val d1 = distToLine(b[0], b[1], la[0], la[1], a[0], a[1]) * sink.aaScale
                val d2 = distToLine(la[0], la[1], b[0], b[1], lb[0], lb[1]) * sink.aaScale
                sink.triangleAA(la[0], la[1], 0f, a[0], a[1], 0f, b[0], b[1], d1)
                sink.triangleAA(la[0], la[1], d2, b[0], b[1], 0f, lb[0], lb[1], 0f)
            }
            lastA = a
            lastB = b
        }

        fun vertexBand(px: Float, py: Float, nIn: FloatArray?, nOut: FloatArray?) {
            val n0 = nIn ?: nOut ?: return
            val n1 = nOut ?: nIn ?: return
            // 从 n0 旋转到 n1 的弧(k 段),每方向生成内外点对
            val cross = (n0[0] * n1[1] - n0[1] * n1[0]).toDouble()
            val dot = (n0[0] * n1[0] + n0[1] * n1[1]).toDouble()
            var angle = atan2(cross, dot)
            // 归一化到 (-π, π],避免整圈旋转
            if (angle > PI) angle -= 2.0 * PI
            if (angle < -PI) angle += 2.0 * PI
            val k = if (nIn != null && nOut != null) {
                // join 圆弧按屏幕像素弧长细分(每段 ~6 屏幕像素)
                max(1, ceil(abs(angle) * h * sink.aaScale / 6.0).toInt())
            } else {
                0
            }
            // 转向(crossZ):左转 → +n(a)侧外凸;右转 → -n(b)侧外凸
            // 法线 n = (-dy, dx) 左法线 → 方向 d = (n.y, -n.x)
            val dInX = n0[1]
            val dInY = -n0[0]
            val dOutX = n1[1]
            val dOutY = -n1[0]
            val crossZ = dInX * dOutY - dInY * dOutX
            val normalsSeq = ArrayList<FloatArray>(k + 1)
            for (i in 0..k) {
                val t = if (k == 0) 0f else i.toFloat() / k
                val a = angle * t
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val nx = n0[0] * ca - n0[1] * sa
                val ny = n0[0] * sa + n0[1] * ca
                normalsSeq.add(floatArrayOf(nx, ny))
                emit(
                    floatArrayOf(px + nx * h, py + ny * h),
                    floatArrayOf(px - nx * h, py - ny * h),
                )
            }
            // 凸角角帽:每个点对(轮廓分段点)处,填补 coverage=0 缺口;
            // 点对序列按环处理(开放路径端点法线略偏,视觉无碍)
            if (k >= 1) {
                val size = normalsSeq.size
                for (j in 0 until size) {
                    val pn = normalsSeq[(j - 1 + size) % size]
                    val nn = normalsSeq[(j + 1) % size]
                    val nx = normalsSeq[j][0]
                    val ny = normalsSeq[j][1]
                    if (crossZ > 0f) {
                        // a 侧(+n)外凸
                        cornerCap(px + nx * h, py + ny * h, pn, nn, sink)
                    } else if (crossZ < 0f) {
                        // b 侧(-n)外凸
                        cornerCap(px - nx * h, py - ny * h, neg(pn), neg(nn), sink)
                    }
                }
            }
        }

        if (closed) {
            // 闭合:每个顶点都是入/出法线之间的 join;首顶点入法线 = 最后边
            for (i in 0 until n) {
                val nIn = normals[(i - 1 + n) % n]
                val nOut = normals[i]
                vertexBand(pts[i * 2], pts[i * 2 + 1], nIn, nOut)
            }
        } else {
            // 开放:首顶点只有出法线(butt),末顶点只有入法线(butt)
            vertexBand(pts[0], pts[1], null, normals[0])
            for (i in 1 until n - 1) {
                vertexBand(pts[i * 2], pts[i * 2 + 1], normals[i - 1], normals[i])
            }
            vertexBand(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], normals[n - 2], null)
        }
    }

    /** 方形点(Points mode,Butt cap 语义):axis-aligned 正方形,四边全轮廓 → 实心(无 AA) */
    private fun squarePoint(x: Float, y: Float, h: Float, sink: Sink) {
        if (h <= 0f) return
        sink.quad(x - h, y - h, x + h, y - h, x + h, y + h, x - h, y + h)
    }

    /** strokeWidth == 0 → hairline 1px(Compose 语义) */
    private fun effectiveWidth(strokeWidth: Float): Float =
        if (strokeWidth <= 0f) 1f else strokeWidth

    /**
     * 凸性检测(带近共线容差):所有顶点转向同号(含近共线,忽略 |sin| < 0.01)。
     * 用于 Path 填充分流 —— 凸路径走 fanAA(稳定),凹路径走 earClip。
     */
    private fun isConvex(pts: FloatArray): Boolean {
        val n = pts.size / 2
        if (n < 3) return true
        var sign = 0
        for (i in 0 until n) {
            val a = (i - 1 + n) % n
            val b = i
            val c = (i + 1) % n
            val abx = pts[b * 2] - pts[a * 2]
            val aby = pts[b * 2 + 1] - pts[a * 2 + 1]
            val bcx = pts[c * 2] - pts[b * 2]
            val bcy = pts[c * 2 + 1] - pts[b * 2 + 1]
            val cross = abx * bcy - aby * bcx
            val len = sqrt((abx * abx + aby * aby) * (bcx * bcx + bcy * bcy))
            if (len < 1e-6f) continue
            val s = cross / len
            if (abs(s) < 0.01f) continue // 近共线(细分密集点),不影响凸性
            val thisSign = if (s > 0f) 1 else -1
            if (sign == 0) sign = thisSign
            else if (sign != thisSign) return false
        }
        return true
    }

    /**
     * 耳切三角化(简单多边形,可非凸;自交/洞不支持)。
     * 维护边的「原始轮廓」标记:剪耳三角形恰好含一条原始边时对该边做 AA,
     * 含 0 或 2+ 条原始边(内部/角部)时实心 —— 与 Skia edge-triangle 一致。
     * O(n²),GUI 规模足够。退化(共线/重复点)自动剔除。
     */
    private fun earClip(pts: FloatArray, sink: Sink) {
        var n = pts.size / 2
        if (n < 3) return

        // 剔除连续重复点与共线点
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var m = 0
        for (i in 0 until n) {
            val x = pts[i * 2]
            val y = pts[i * 2 + 1]
            val prevX = pts[((i - 1 + n) % n) * 2]
            val prevY = pts[((i - 1 + n) % n) * 2 + 1]
            if (dist(x, y, prevX, prevY) < 1e-4f) continue
            xs[m] = x; ys[m] = y; m++
        }
        if (m < 3) return
        // 去掉首尾重复
        while (m > 3 && dist(xs[0], ys[0], xs[m - 1], ys[m - 1]) < 1e-4f) m--
        if (m < 3) return

        val prev = IntArray(m)
        val next = IntArray(m)
        for (i in 0 until m) {
            prev[i] = (i - 1 + m) % m
            next[i] = (i + 1) % m
        }
        // 边 i → next[i] 是否为原始轮廓边(初始全 true;剪耳产生的对角线为 false)
        val edgeOrig = BooleanArray(m) { true }

        // 多边形方向:叉积符号统一
        var area2 = 0f
        for (i in 0 until m) {
            val j = (i + 1) % m
            area2 += xs[i] * ys[j] - xs[j] * ys[i]
        }
        val orientation = if (area2 >= 0f) 1 else -1

        // 凸角角帽:原始轮廓的凸顶点处填补 coverage=0 缺口(尖角像素);
        // 近共线(细分密集点)|sin| < 0.01 视为直线,不加角帽也不影响凸性
        for (i in 0 until m) {
            val pv = (i - 1 + m) % m
            val nx = (i + 1) % m
            val cross = (xs[i] - xs[pv]) * (ys[nx] - ys[i]) - (ys[i] - ys[pv]) * (xs[nx] - xs[i])
            val len = sqrt(
                ((xs[i] - xs[pv]) * (xs[i] - xs[pv]) + (ys[i] - ys[pv]) * (ys[i] - ys[pv])) *
                    ((xs[nx] - xs[i]) * (xs[nx] - xs[i]) + (ys[nx] - ys[i]) * (ys[nx] - ys[i]))
            )
            if (len < 1e-6f) continue
            if (cross / len * orientation > 0.01f) {
                val nIn = edgeOuterNormal(xs[pv], ys[pv], xs[i], ys[i], orientation)
                val nOut = edgeOuterNormal(xs[i], ys[i], xs[nx], ys[nx], orientation)
                cornerCap(xs[i], ys[i], nIn, nOut, sink)
            }
        }

        fun emitEar(a: Int, b: Int, c: Int) {
            val ab = edgeOrig[a]
            val bc = edgeOrig[b]
            val ca = edgeOrig[c]
            val count = (if (ab) 1 else 0) + (if (bc) 1 else 0) + (if (ca) 1 else 0)
            when {
                count == 1 && ab -> {
                    val d = distToLine(xs[c], ys[c], xs[a], ys[a], xs[b], ys[b]) * sink.aaScale
                    sink.triangleAA(xs[a], ys[a], 0f, xs[b], ys[b], 0f, xs[c], ys[c], d)
                }
                count == 1 && bc -> {
                    val d = distToLine(xs[a], ys[a], xs[b], ys[b], xs[c], ys[c]) * sink.aaScale
                    sink.triangleAA(xs[b], ys[b], 0f, xs[c], ys[c], 0f, xs[a], ys[a], d)
                }
                count == 1 && ca -> {
                    val d = distToLine(xs[b], ys[b], xs[c], ys[c], xs[a], ys[a]) * sink.aaScale
                    sink.triangleAA(xs[c], ys[c], 0f, xs[a], ys[a], 0f, xs[b], ys[b], d)
                }
                else -> sink.triangle(xs[a], ys[a], xs[b], ys[b], xs[c], ys[c])
            }
        }

        var remaining = m
        var guard = 0
        val maxGuard = m * m * 2
        var i = 0
        while (remaining > 3 && guard++ < maxGuard) {
            val a = prev[i]
            val b = i
            val c = next[i]
            if (isEar(a, b, c, orientation, xs, ys, prev, next, remaining)) {
                emitEar(a, b, c)
                next[a] = c
                prev[c] = a
                edgeOrig[a] = false // 新边 a→c 为对角线
                remaining--
            }
            i = next[i]
        }
        if (remaining >= 3) {
            // 收尾:剩余环按耳切规则输出(正常路径不会走到多三角形兜底)
            var idx = i
            var count = 0
            while (count < remaining - 2) {
                val a = prev[idx]
                val b = idx
                val c = next[idx]
                emitEar(a, b, c)
                next[a] = c
                prev[c] = a
                edgeOrig[a] = false
                idx = c
                count++
            }
        }
    }

    /** 顶点 b 是否为凸耳:局部凸(含近共线容差)+ 三角形内无其他顶点 */
    private fun isEar(
        a: Int, b: Int, c: Int, orientation: Int,
        xs: FloatArray, ys: FloatArray,
        prev: IntArray, next: IntArray,
        remaining: Int,
    ): Boolean {
        val cross = (xs[b] - xs[a]) * (ys[c] - ys[a]) - (ys[b] - ys[a]) * (xs[c] - xs[a])
        val len = sqrt(
            ((xs[b] - xs[a]) * (xs[b] - xs[a]) + (ys[b] - ys[a]) * (ys[b] - ys[a])) *
                ((xs[c] - xs[a]) * (xs[c] - xs[a]) + (ys[c] - ys[a]) * (ys[c] - ys[a]))
        )
        if (len < 1e-6f) return false
        if (cross / len * orientation <= 0.01f) return false
        var count = 0
        var i = next[c]
        while (i != a && count++ <= remaining) {
            if (i != a && i != b && i != c && pointInTriangle(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b], xs[c], ys[c])) {
                return false
            }
            i = next[i]
        }
        return true
    }

    private fun pointInTriangle(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNeg && hasPos)
    }

    private fun circleSegments(radius: Float, aaScale: Float): Int =
        max(12, (2.0 * PI * radius * aaScale / 6.0).roundToInt())
}
