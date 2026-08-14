package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.MinecraftPath
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// 几何三角化器(抗锯齿版)
//
// 把 Compose 绘制命令的几何(局部坐标,场景 px,密度 1)三角化为
// [x, y] 平铺顶点序列 + 每顶点 coverage,交给 GuiTriangleRenderState
// 以 TRIANGLES 拓扑提交。
//
// coverage 语义(与片元着色器 gui_triangles.fsh 配合):
// - **有符号屏幕像素距离**:到「最近真实轮廓边」的带符号距离(× Sink.aaScale,
//   其中 aaScale = 矩阵最大轴缩放 × guiScale,即局部坐标 → 物理像素);
//   轮廓外侧为负、真实轮廓上为 0、轮廓内侧为正;
// - 每个轮廓边在真实轮廓外额外外扩约 1 物理像素宽的 AA fringe,
//   fringe 顶点 coverage = -1,轮廓顶点 = 0,内部顶点为正距离;
// - 内部实心三角形(不邻接任何轮廓边)coverage = OPAQUE 大数 → 完全覆盖;
// - 片元着色器:smoothstep(-0.5·fwidth(d), 0.5·fwidth(d), d) 得到约 1 物理
//   像素宽的过渡带(跨真实轮廓两侧),与 GUI scale / 矩阵缩放无关。
//
// 覆盖:
// - 填充:圆 / 椭圆 / 弧(useCenter 扇形、!useCenter 弓形)/ 圆角矩形(凸,
//   扇形即可)/ Path(简单多边形,耳切;自交、洞、EvenOdd 环绕规则第一版不支持)
// - 描边:圆 / 椭圆 / 弧 / 圆角矩形 / Path / 线段 —— 轮廓带展开(圆 join,
//   butt/round cap;与 Skia 的 miter join 视觉差异见类注释)
// - 点:PointMode.Points(按 cap:Round → 圆,其他 → 方形)/ Lines(两两成段,
//   奇数点忽略末点)/ Polygon(按输入顺序的连续折线,不填充、不闭合)
//
// 已知简化(与 Skia 语义差异,文档记录):
// - join 统一用圆 join(避免 miter 尖刺;Paint 快照未携带 strokeJoin,
//   DrawScope.drawPoints 传入的默认 Miter 不展开);
// - strokeWidth 在局部坐标展开,矩阵缩放会等比影响线宽(未做设备空间补偿);
// - strokeWidth == 0 按 hairline 1px 处理;
// - 三角形绕序统一视觉顺时针;pipeline 侧已关闭背面剔除,不依赖绕序正确性;
// - aaScale 用矩阵最大轴缩放 × guiScale,非均匀缩放下 AA 宽度在次轴略偏;
// - 凹多边形(earClip)的角部三角形按实心处理(线性插值无法表达"三点全在轮廓、
//   内部为正"的距离场),角部轮廓边由外侧 fringe 提供 AA,角像素略硬。
// ─────────────────────────────────────────────────────────────────────────────

internal object GeometryTessellator {

    /** 内部(无轮廓边)三角形的 coverage 大数:smoothstep 后保持完全不透明 */
    private const val OPAQUE = 1e4f

    /** 扁平 [x, y] 顶点缓冲 + 逐顶点 coverage(有符号屏幕像素距离) */
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

        /** 带 coverage 的三角形(轮廓边 AA / 内部带符号距离) */
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
         * 轮廓边 (a→b) 的外扩 AA fringe:真实轮廓外 ~1 物理像素宽的带。
         * coverage:轮廓上 0 → fringe 外侧 -1(有符号屏幕像素距离)。
         * [nx, ny] 为指向轮廓外侧的单位法线(远离形状内部)。
         */
        fun edgeFringe(ax: Float, ay: Float, bx: Float, by: Float, nx: Float, ny: Float) {
            val f = 1f / aaScale
            if (f <= 0f) return
            val ox = nx * f
            val oy = ny * f
            triangleAA(ax, ay, 0f, bx, by, 0f, bx + ox, by + oy, -1f)
            triangleAA(ax, ay, 0f, bx + ox, by + oy, -1f, ax + ox, ay + oy, -1f)
        }

        /**
         * 凸多边形轮廓扇形(带 AA)。
         *
         * 锚点取**顶点平均**(凸多边形内部点);每个轮廓边发射:
         * - 内部三角 (a, b, 锚点):coverage 0(轮廓)→ 锚点处「到该边的距离」(正);
         * - 外侧 fringe(0 → -1);
         * 另对每个凸轮廓顶点发射角帽,填补相邻两条 fringe 之间的外部楔形
         * (coverage 顶点 0 → fringe -1)。
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

            // 凸角角帽:相邻两条轮廓边 fringe 之间的外部楔形(0 → -1)
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
                // 外侧 fringe:外法线 = 远离锚点(内部)
                val n = outerNormal(ax, ay, bx, by, cx, cy)
                edgeFringe(ax, ay, bx, by, n[0], n[1])
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
            strokeRing(outline.copyOfRange(0, outline.size - 2), closed = true, strokeWidth, StrokeCap.Butt, sink)
        }
    }

    // ── 线段 ───────────────────────────────────────────────────────────────

    /**
     * 线段:线宽展开为「轮廓多边形」(butt → 矩形 4 角;round → 胶囊:两端半圆 +
     * 两条直边),经 [Sink.fanAA] 三角化 —— 全部边均为真实轮廓边,coverage 在
     * 轮廓上为 0、内侧为正,端帽(butt 方形 / round 半圆)自然带 AA,无端边硬切。
     */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, strokeWidth: Float, cap: StrokeCap, sink: Sink) {
        val w = effectiveWidth(strokeWidth)
        val h = w / 2f
        val dx = x2 - x1
        val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 1e-6f) {
            // 零长度线段 → 按 cap 画一个点
            if (cap == StrokeCap.Round) {
                circle(x1, y1, h, fill = true, strokeWidth = 0f, sink)
            } else {
                squarePoint(x1, y1, h, sink)
            }
            return
        }
        val ux = dx / len
        val uy = dy / len
        val nx = -uy * h
        val ny = ux * h

        val outline = ArrayList<Float>(24)
        if (cap == StrokeCap.Round) {
            // 胶囊轮廓:p0 半圆(+n 经 -u 到 -n)→ 直边 → p1 半圆(-n 经 +u 到 +n)→ 直边闭合
            roundCapOutline(x1, y1, nx, ny, -ux, -uy, h, sink.aaScale, outline)
            roundCapOutline(x2, y2, -nx, -ny, ux, uy, h, sink.aaScale, outline)
        } else {
            outline.add(x1 + nx); outline.add(y1 + ny)
            outline.add(x2 + nx); outline.add(y2 + ny)
            outline.add(x2 - nx); outline.add(y2 - ny)
            outline.add(x1 - nx); outline.add(y1 - ny)
        }
        sink.fanAA(outline.toFloatArray())
    }

    /**
     * 追加一个端点半圆到 [outline]:从 [sn] 方向(单位半宽法线,已含 h)经 [pe](指向带外,
     * 单位)旋转到 -[sn]。[sn] 应为 ±(n·h),[pe] 应为 ∓u(与 -sn 同向半圆的顶点方向)。
     */
    private fun roundCapOutline(
        px: Float, py: Float,
        snx: Float, sny: Float,
        pex: Float, pey: Float,
        h: Float, aaScale: Float,
        outline: ArrayList<Float>,
    ) {
        val segments = max(4, ceil(PI * h * aaScale / 6.0).toInt())
        // 从 sn 到 -sn 的半圆,旋转方向取经过 pe 的那一侧
        val base = atan2(sny, snx)
        val ccw = (pex * (-sny) + pey * snx) > 0f
        for (i in 0..segments) {
            val t = if (ccw) base + PI * i / segments else base - PI * i / segments
            outline.add(px + h * cos(t).toFloat())
            outline.add(py + h * sin(t).toFloat())
        }
    }

    // ── Path ───────────────────────────────────────────────────────────────

    /**
     * Path 绘制。segments 为扁平化段(Move/Line/Quadratic/Cubic/Close)。
     * fill → 每子路径独立填充:凸多边形用 [Sink.fanAA](锚点内部,密集细分点
     *   也稳定);凹多边形用 [earClip](自交/洞/EvenOdd 第一版不支持)。
     *   平台适配点:earClip 的耳判定对「贝塞尔细分出的近共线密集点」会因叉积
     *   符号抖动而失败(找不到耳 → 兜底扇形输出错误三角形),因此凸路径必须
     *   走 fanAA —— 视觉形状不变,只换三角化策略;
     * stroke → 全部子路径的轮廓带(开放子路径按 [cap] 端帽,闭合子路径圆 join)。
     */
    fun path(
        segments: List<MinecraftPath.PathSegmentData>,
        fill: Boolean,
        strokeWidth: Float,
        cap: StrokeCap,
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
                strokeRing(subpath.points, subpath.closed, strokeWidth, cap, sink)
            }
        }
    }

    // ── 点 ─────────────────────────────────────────────────────────────────

    /**
     * 点绘制(PointMode)。语义与 AndroidX DrawScope.drawPoints 一致:
     * - Points:每个点独立绘制 —— StrokeCap.Round → 直径为 strokeWidth 的圆,
     *   其他 cap → 边长为 strokeWidth 的方形;
     * - Lines:每两个点构成一条独立线段(p0→p1, p2→p3, ...),点数为奇数时忽略末点;
     * - Polygon:按输入顺序生成连续折线(p0→p1→p2→...),不填充内部,
     *   不自动闭合回起点;每段按法线方向展开成描边四边形,顶点处圆 join,
     *   两端按 [cap] 端帽。
     */
    fun points(mode: PointMode, points: List<Offset>, strokeWidth: Float, cap: StrokeCap, sink: Sink) {
        if (points.isEmpty()) return
        val w = effectiveWidth(strokeWidth)
        when (mode) {
            PointMode.Points -> for (p in points) {
                if (cap == StrokeCap.Round) {
                    circle(p.x, p.y, w / 2f, fill = true, strokeWidth = 0f, sink)
                } else {
                    squarePoint(p.x, p.y, w / 2f, sink)
                }
            }
            PointMode.Lines -> {
                var i = 0
                while (i + 1 < points.size) {
                    val a = points[i]
                    val b = points[i + 1]
                    line(a.x, a.y, b.x, b.y, w, cap, sink)
                    i += 2
                }
            }
            PointMode.Polygon -> {
                if (points.size < 2) return
                // 过滤连续重复点(零长度段),避免法线退化
                val pts = ArrayList<Float>(points.size * 2)
                var lastX: Float? = null
                var lastY: Float? = null
                for (p in points) {
                    val px = lastX
                    val py = lastY
                    if (px == null || py == null || dist(px, py, p.x, p.y) > 1e-4f) {
                        pts.add(p.x)
                        pts.add(p.y)
                        lastX = p.x
                        lastY = p.y
                    }
                }
                if (pts.size < 4) {
                    // 全部重合 → 画一个点
                    if (pts.size == 2) {
                        if (cap == StrokeCap.Round) {
                            circle(pts[0], pts[1], w / 2f, fill = true, strokeWidth = 0f, sink)
                        } else {
                            squarePoint(pts[0], pts[1], w / 2f, sink)
                        }
                    }
                    return
                }
                // 连续折线:不闭合
                strokeRing(pts.toFloatArray(), closed = false, w, cap, sink)
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
     * 凸角角帽:覆盖轮廓顶点外侧(两条相邻轮廓边 fringe 之间的外部楔形)。
     * coverage:顶点 0(轮廓)→ fringe -1,与相邻 fringe 无缝衔接。
     * [nIn]/[nOut] 为入边/出边的外侧单位法线。
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
                px, py, 0f,
                ax, ay, -1f,
                px - nIn[1] * w, py + nIn[0] * w, -1f,
            )
        } else {
            sink.triangleAA(px, py, 0f, ax, ay, -1f, bx, by, -1f)
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

    /** 圆形端帽:以 (px,py) 为圆心、半径 [h] 的半圆,从 +n 经 +e 到 -n */
    private fun roundCap(
        px: Float, py: Float,
        nx: Float, ny: Float,
        ex: Float, ey: Float,
        h: Float,
        sink: Sink,
    ) {
        val segments = max(4, ceil(PI * h * sink.aaScale / 6.0).toInt())
        val base = atan2(ny, nx)
        // 旋转方向:使弧经过 +e(e ⊥ n)
        val ccw = (ex * (-ny) + ey * nx) > 0f
        var prevX = px + nx * h
        var prevY = py + ny * h
        for (i in 1..segments) {
            val t = if (ccw) base + PI * i / segments else base - PI * i / segments
            val ax = px + h * cos(t).toFloat()
            val ay = py + h * sin(t).toFloat()
            // 扇三角 (圆心, 弧前点, 弧后点):coverage 圆心 = +h·aaScale,弧上 = 0
            sink.triangleAA(px, py, h * sink.aaScale, prevX, prevY, 0f, ax, ay, 0f)
            // 弧段径向 fringe
            val rnx = (ax - px) / h
            val rny = (ay - py) / h
            sink.edgeFringe(prevX, prevY, ax, ay, rnx, rny)
            prevX = ax
            prevY = ay
        }
    }

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
            // 外轮廓外侧 fringe(远离圆心)
            val nOut = outerNormal(outer[i * 2], outer[i * 2 + 1], outer[j * 2], outer[j * 2 + 1], cx, cy)
            sink.edgeFringe(outer[i * 2], outer[i * 2 + 1], outer[j * 2], outer[j * 2 + 1], nOut[0], nOut[1])
            // 内轮廓外侧 fringe(指向圆心 = 环带外侧)
            val nIn = neg(outerNormal(inner[i * 2], inner[i * 2 + 1], inner[j * 2], inner[j * 2 + 1], cx, cy))
            sink.edgeFringe(inner[i * 2], inner[i * 2 + 1], inner[j * 2], inner[j * 2 + 1], nIn[0], nIn[1])
        }
        // 凸角角帽:填补分段点缺口(闭合环 0 until segments;开放弧带两端另做端帽)
        val closed = abs(sweep) >= 2.0 * PI - 1e-4
        if (closed) {
            for (i in 0 until segments) {
                val ip = (i - 1 + segments) % segments
                val jp = (i + 1) % segments
                capAt(outer, i, ip, jp, cx, cy, towardCenter = false, sink)
                capAt(inner, i, ip, jp, cx, cy, towardCenter = true, sink)
            }
        } else {
            for (i in 1 until segments) {
                capAt(outer, i, i - 1, i + 1, cx, cy, towardCenter = false, sink)
                capAt(inner, i, i - 1, i + 1, cx, cy, towardCenter = true, sink)
            }
            // 两端:端边(径向)外侧 fringe + 端角帽
            endRingCap(outer, inner, 0, startAngle, sweep, segments, sign = -1, cx, cy, sink)
            endRingCap(outer, inner, segments, startAngle, sweep, segments, sign = +1, cx, cy, sink)
        }
    }

    /** 环带分段点角帽:[towardCenter] = 内轮廓(法线指向圆心) */
    private fun capAt(
        pts: FloatArray,
        i: Int, ip: Int, jp: Int,
        cx: Float, cy: Float,
        towardCenter: Boolean,
        sink: Sink,
    ) {
        var nIn = outerNormal(pts[i * 2], pts[i * 2 + 1], pts[ip * 2], pts[ip * 2 + 1], cx, cy)
        var nOut = outerNormal(pts[i * 2], pts[i * 2 + 1], pts[jp * 2], pts[jp * 2 + 1], cx, cy)
        if (towardCenter) {
            nIn = neg(nIn)
            nOut = neg(nOut)
        }
        cornerCap(pts[i * 2], pts[i * 2 + 1], nIn, nOut, sink)
    }

    /**
     * 开放弧带端点(径向端边)处理:端边 = (outer[k] → inner[k]),
     * 其外侧单位法线 = 端点处单位切向 × 端点方向符号 × sweep 符号。
     * [sign] = -1 表示起点端(向外 = -T),+1 表示终点端(向外 = +T)。
     */
    private fun endRingCap(
        outer: FloatArray, inner: FloatArray,
        k: Int, startAngle: Double, sweep: Double, segments: Int,
        sign: Int,
        ringCx: Float, ringCy: Float,
        sink: Sink,
    ) {
        val a = startAngle + sweep * k / segments
        val tx = -sin(a).toFloat()
        val ty = cos(a).toFloat()
        val sweepSign = if (sweep >= 0) 1 else -1
        val ex = sign * sweepSign * tx
        val ey = sign * sweepSign * ty
        // 端边 fringe
        sink.edgeFringe(outer[k * 2], outer[k * 2 + 1], inner[k * 2], inner[k * 2 + 1], ex, ey)
        // 端角帽:外轮廓角 = 相邻外轮廓边外法线 + 端法线;内轮廓角 = 相邻内轮廓边外法线(指向圆心)+ 端法线
        val outerAdj = if (k == 0) 1 else segments - 1
        val innerAdj = if (k == 0) 1 else segments - 1
        val nOutC = outerNormal(outer[k * 2], outer[k * 2 + 1], outer[outerAdj * 2], outer[outerAdj * 2 + 1], ringCx, ringCy)
        val nInC = neg(outerNormal(inner[k * 2], inner[k * 2 + 1], inner[innerAdj * 2], inner[innerAdj * 2 + 1], ringCx, ringCy))
        cornerCap(outer[k * 2], outer[k * 2 + 1], nOutC, floatArrayOf(ex, ey), sink)
        cornerCap(inner[k * 2], inner[k * 2 + 1], nInC, floatArrayOf(ex, ey), sink)
    }

    /**
     * 轮廓带展开(任意多边形,圆 join,闭合路径无端帽,开放路径按 [cap] 端帽)。
     * [pts] 为 [x,y] 平铺轮廓顶点;带的两条长边为轮廓边(AA),端边为内部连接边。
     */
    private fun strokeRing(pts: FloatArray, closed: Boolean, strokeWidth: Float, cap: StrokeCap, sink: Sink) {
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
                // 外轮廓边 fringe(外法线 = 远离带中心)
                val nOut = bandOuterNormal(la[0], la[1], a[0], a[1], lb[0], lb[1], b[0], b[1])
                sink.edgeFringe(la[0], la[1], a[0], a[1], nOut[0], nOut[1])
                // 内轮廓边 fringe(外法线 = 远离带中心,朝内)
                val nIn = bandOuterNormal(b[0], b[1], lb[0], lb[1], a[0], a[1], la[0], la[1])
                sink.edgeFringe(b[0], b[1], lb[0], lb[1], nIn[0], nIn[1])
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
            // 凸角角帽:每个点对(轮廓分段点)处,填补外部楔形(coverage 0 → -1)
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
            // 开放:首端只有出法线(端帽),末顶点只有入法线(端帽)
            val f = 1f / sink.aaScale // 端帽内缩宽度(1 物理像素)
            val u0x = normals[0][1]
            val u0y = -normals[0][0]
            if (cap == StrokeCap.Round) {
                // round 首端:端点对 + 半圆端帽(带外方向 = -u0)
                vertexBand(pts[0], pts[1], null, normals[0])
                roundCap(pts[0], pts[1], normals[0][0], normals[0][1], -u0x, -u0y, h, sink)
            } else {
                // butt 首端:端帽梯形(端边 + 内缩 1px 边)+ 端边 fringe + 角帽;
                // 带从内缩点开始(避免最后一段四边形把端边中点覆盖成 width/2)
                val a0x = pts[0] + normals[0][0] * h
                val a0y = pts[1] + normals[0][1] * h
                val b0x = pts[0] - normals[0][0] * h
                val b0y = pts[1] - normals[0][1] * h
                endCapButt(a0x, a0y, b0x, b0y, normals[0][0], normals[0][1], -u0x, -u0y, f, sink)
                lastA = floatArrayOf(a0x + u0x * f, a0y + u0y * f)
                lastB = floatArrayOf(b0x + u0x * f, b0y + u0y * f)
            }
            for (i in 1 until n - 1) {
                vertexBand(pts[i * 2], pts[i * 2 + 1], normals[i - 1], normals[i])
            }
            val ulx = normals[n - 2][1]
            val uly = -normals[n - 2][0]
            if (cap == StrokeCap.Round) {
                // round 末端:端点对 + 半圆端帽(带外方向 = +u_last)
                vertexBand(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], normals[n - 2], null)
                roundCap(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], normals[n - 2][0], normals[n - 2][1], ulx, uly, h, sink)
            } else {
                // butt 末端:先发射内缩点对(闭合最后一段带),再补端帽梯形
                val ax = pts[(n - 1) * 2] + normals[n - 2][0] * h
                val ay = pts[(n - 1) * 2 + 1] + normals[n - 2][1] * h
                val bx = pts[(n - 1) * 2] - normals[n - 2][0] * h
                val by = pts[(n - 1) * 2 + 1] - normals[n - 2][1] * h
                emit(
                    floatArrayOf(ax - ulx * f, ay - uly * f),
                    floatArrayOf(bx - ulx * f, by - uly * f),
                )
                endCapButt(ax, ay, bx, by, normals[n - 2][0], normals[n - 2][1], ulx, uly, f, sink)
            }
        }
    }

    /**
     * butt 端帽:端边 (a→b) 与内缩 1px 边 (a'→b') 之间的梯形,coverage =
     * 到端边的有符号距离(端边 0 → 内缩边 +1),加端边外侧 fringe 与两端角帽。
     * [(nx, ny)] 为端点处带的单位法线,[(ex, ey)] 为指向带外的单位方向(端边外法线)。
     */
    private fun endCapButt(
        ax: Float, ay: Float, bx: Float, by: Float,
        nx: Float, ny: Float,
        ex: Float, ey: Float,
        f: Float,
        sink: Sink,
    ) {
        // 内缩边 = 端边向带内移 f(即 -e 方向)
        val a2x = ax - ex * f
        val a2y = ay - ey * f
        val b2x = bx - ex * f
        val b2y = by - ey * f
        // 梯形 (a, b, b', a'):coverage 端边 0 → 内缩边 +1(1 物理像素内)
        sink.triangleAA(ax, ay, 0f, bx, by, 0f, b2x, b2y, 1f)
        sink.triangleAA(ax, ay, 0f, b2x, b2y, 1f, a2x, a2y, 1f)
        // 端边外侧 fringe
        sink.edgeFringe(ax, ay, bx, by, ex, ey)
        // 两端角帽:带法线(±n)与端法线(e)之间的外部楔形
        cornerCap(ax, ay, floatArrayOf(nx, ny), floatArrayOf(ex, ey), sink)
        cornerCap(bx, by, floatArrayOf(-nx, -ny), floatArrayOf(ex, ey), sink)
    }

    /** 带四边形 (la, a, b, lb) 的外轮廓边 (p→q) 的外法线:垂直于边、远离带中心 */
    private fun bandOuterNormal(px: Float, py: Float, qx: Float, qy: Float, ox: Float, oy: Float, rx: Float, ry: Float): FloatArray {
        var dx = qx - px
        var dy = qy - py
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return floatArrayOf(0f, 0f)
        dx /= len
        dy /= len
        var nx = -dy
        var ny = dx
        // 带中心(四条边的平均)与边中点连线决定外侧方向
        val cx = (px + qx + ox + rx) / 4f
        val cy = (py + qy + oy + ry) / 4f
        val mx = (px + qx) / 2f
        val my = (py + qy) / 2f
        if (nx * (mx - cx) + ny * (my - cy) < 0f) {
            nx = -nx
            ny = -ny
        }
        return floatArrayOf(nx, ny)
    }

    /** 方形点(Points mode,非 Round cap):边长为 2h 的方形,带 AA(fanAA) */
    private fun squarePoint(x: Float, y: Float, h: Float, sink: Sink) {
        if (h <= 0f) return
        sink.fanAA(floatArrayOf(x - h, y - h, x + h, y - h, x + h, y + h, x - h, y + h))
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
     * 维护边的「原始轮廓」标记:所有原始轮廓边在剪耳前发射外侧 fringe 与凸角角帽;
     * 剪耳三角形恰好含一条原始边时对该边做 AA(内部三角 coverage 0 → +d),
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

        // 所有原始轮廓边:外侧 fringe + 凸角角帽(填补外部楔形)
        for (i in 0 until m) {
            val j = next[i]
            val n = edgeOuterNormal(xs[i], ys[i], xs[j], ys[j], orientation)
            sink.edgeFringe(xs[i], ys[i], xs[j], ys[j], n[0], n[1])
        }
        // 凸角角帽:原始轮廓的凸顶点处填补外部楔形(尖角像素);
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
