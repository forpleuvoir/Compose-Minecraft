@file:Suppress("GrazieInspection", "SpellCheckingInspection")

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
        val cornerSegments = max(4, ceil(max(rx, ry) * sink.aaScale / 4f).toInt())
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
        val segments = max(6, ceil(PI * h * aaScale / 4.0).toInt())
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
            for ((points) in subpaths) {
                if (points.size < 6) continue
                if (isConvex(points)) {
                    sink.fanAA(points)
                } else {
                    earClip(points, sink)
                }
            }
        } else {
            for ((points, closed) in subpaths) {
                strokeRing(points, closed, strokeWidth, cap, sink)
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
            PointMode.Points  -> for (p in points) {
                if (cap == StrokeCap.Round) {
                    circle(p.x, p.y, w / 2f, fill = true, strokeWidth = 0f, sink)
                } else {
                    squarePoint(p.x, p.y, w / 2f, sink)
                }
            }

            PointMode.Lines   -> {
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

    private class SubPath(val points: FloatArray, val closed: Boolean) {
        operator fun component1(): FloatArray = points
        operator fun component2(): Boolean = closed
    }

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
                MinecraftPath.PathSegmentType.Move      -> {
                    flush(closed = false)
                    startX = p[0]; startY = p[1]
                    current.add(startX); current.add(startY)
                    lastX = startX; lastY = startY
                    hasPoint = true
                }

                MinecraftPath.PathSegmentType.Line      -> {
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

                MinecraftPath.PathSegmentType.Cubic     -> {
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

                MinecraftPath.PathSegmentType.Close     -> {
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

    /** 点 [px, py] 到线段 (ax, ay)-(bx, by) 的距离(投影钳制在段内) */
    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-6f) return dist(px, py, ax, ay)
        val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        val tc = t.coerceIn(0f, 1f)
        return dist(px, py, ax + dx * tc, ay + dy * tc)
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
        val segments = max(6, ceil(PI * h * sink.aaScale / 4.0).toInt())
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
                max(1, ceil(abs(angle) * h * sink.aaScale / 4.0).toInt())
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
            // 闭合:每个顶点都是入/出法线之间的 join;首顶点入法线 = 最后边。
            // 顶点带只覆盖边 (0→1)..(n-2→n-1),闭合边 (n-1→0) 的带
            // 需要把首顶点带与末顶点带之间的四边形补发出来,否则描边开口。
            var firstA: FloatArray? = null
            var firstB: FloatArray? = null
            for (i in 0 until n) {
                val nIn = normals[(i - 1 + n) % n]
                val nOut = normals[i]
                vertexBand(pts[i * 2], pts[i * 2 + 1], nIn, nOut)
                if (i == 0) {
                    firstA = lastA
                    firstB = lastB
                }
            }
            firstA?.let { fa -> firstB?.let { fb -> emit(fa, fb) } }
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
            val c = (i + 1) % n
            val abx = pts[i * 2] - pts[a * 2]
            val aby = pts[i * 2 + 1] - pts[a * 2 + 1]
            val bcx = pts[c * 2] - pts[i * 2]
            val bcy = pts[c * 2 + 1] - pts[i * 2 + 1]
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
     * 凹多边形填充(耳切三角化 + 内缩 AA 壳)。
     *
     * AA 结构(与凸路径 fanAA 同源):
     * 1. 原始轮廓边:外侧 fringe + 凸角角帽(1 物理像素外扩,保留);
     * 2. 内侧 AA 壳:每条轮廓边发斜坡三角 (a, b, c_i)(轮廓 0 → 壳点 +2),
     *    每个顶点发楔形三角 (v_i, c_{i-1}, c_i)(0 → +2);
     *    —— 壳内 coverage 为「到轮廓的垂直距离」,渐变垂直轮廓,过渡带
     *       恒为 1 物理像素、跨轮廓两侧;
     * 3. 内缩多边形(轮廓内缩 2 物理像素,顶点为相邻边内缩线的交点)填充:
     *    凸 → 扇形 / 凹 → 耳切,顶点 coverage = 到原始轮廓的真距离(≥ 2 → 完全覆盖);
     *    内缩多边形边界与壳的 +2 连续,无硬切。
     *
     * 旧方案的缺陷:耳三角形含 2 条原始边或全对角线时按实心输出,轮廓内侧
     * 无距离斜坡(半宽 AA、轮廓像素 alpha 在 0.5/1.0 间交替);贝塞尔细分出的
     * 近共线稠密点还会使耳判定叉积符号抖动,收尾兜底输出非法三角形。
     * 内缩壳不再依赖耳三角形承担 AA,两类问题一并消除。
     */
    private fun earClip(pts: FloatArray, sink: Sink) {
        val n = pts.size / 2
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

        // 多边形方向:叉积符号统一
        var area2 = 0f
        for (i in 0 until m) {
            val j = (i + 1) % m
            area2 += xs[i] * ys[j] - xs[j] * ys[i]
        }
        val orientation = if (area2 >= 0f) 1 else -1

        // ── 1. 原始轮廓边:外侧 fringe + 凸角角帽(填补外部楔形)──
        for (i in 0 until m) {
            val j = (i + 1) % m
            val nrm = edgeOuterNormal(xs[i], ys[i], xs[j], ys[j], orientation)
            sink.edgeFringe(xs[i], ys[i], xs[j], ys[j], nrm[0], nrm[1])
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

        // ── 自交检测:自交多边形(蝴蝶结等)不支持耳切,走 EvenOdd 梯形扫描 ──
        if (findCrossings(xs, ys, m).isNotEmpty()) {
            evenOddFill(xs, ys, m, sink)
            return
        }

        // ── 2. 内侧 AA 壳:内缩线交点(壳点)+ 斜坡/楔形三角 ──
        val w = 2f / sink.aaScale
        if (w <= 0f || !w.isFinite()) return
        val cov = 2f // 壳点 coverage = w * aaScale(物理像素 2px → 距离 2)
        val cx = FloatArray(m)
        val cy = FloatArray(m)
        for (i in 0 until m) {
            // 边 i 的内缩线:过边中点 + 内向法线·w,方向 = 边方向
            val j = (i + 1) % m
            val nrm = edgeOuterNormal(xs[i], ys[i], xs[j], ys[j], orientation)
            val qx = (xs[i] + xs[j]) / 2f - nrm[0] * w
            val qy = (ys[i] + ys[j]) / 2f - nrm[1] * w
            var dx = xs[j] - xs[i]
            var dy = ys[j] - ys[i]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-6f) continue
            dx /= len
            dy /= len
            // 与上一条边的内缩线求交 → 壳点(凸/凹顶点统一:内缩线的交点)
            val pv = (i - 1 + m) % m
            val jj = (pv + 1) % m
            val nrm2 = edgeOuterNormal(xs[pv], ys[pv], xs[jj], ys[jj], orientation)
            val qx2 = (xs[pv] + xs[jj]) / 2f - nrm2[0] * w
            val qy2 = (ys[pv] + ys[jj]) / 2f - nrm2[1] * w
            var dx2 = xs[jj] - xs[pv]
            var dy2 = ys[jj] - ys[pv]
            val len2 = sqrt(dx2 * dx2 + dy2 * dy2)
            if (len2 < 1e-6f) continue
            dx2 /= len2
            dy2 /= len2
            val denom = dx * dy2 - dy * dx2
            if (abs(denom) < 1e-4f) {
                // 近共线(平滑接合/拐点):交点 ≈ 顶点正内方,取「顶点 + 内向法线平均 × w」,
                // 而非边中点内缩 —— 中点内缩会让壳边偏离顶点,楔形/斜坡覆盖场错位
                val mx = nrm[0] + nrm2[0]
                val my = nrm[1] + nrm2[1]
                val ml = sqrt(mx * mx + my * my)
                if (ml < 1e-6f) {
                    cx[i] = qx
                    cy[i] = qy
                } else {
                    cx[i] = xs[i] - (mx / ml) * w
                    cy[i] = ys[i] - (my / ml) * w
                }
            } else {
                // q + s·d = q2 + t·d2 → s = ((q2 - q) × d2) / (d × d2)
                val s = ((qx2 - qx) * dy2 - (qy2 - qy) * dx2) / denom
                val ix = qx + dx * s
                val iy = qy + dy * s
                // 数值防护:交点应落在顶点附近(合法角点在 w/sin(θ/2) 内);
                // 距顶点过远(近共线数值退化/异常)→ 退回边中点内缩
                if (dist(ix, iy, xs[i], ys[i]) > w * 20f) {
                    cx[i] = qx
                    cy[i] = qy
                } else {
                    cx[i] = ix
                    cy[i] = iy
                }
            }
        }
        // 2a. 边带四边形 (v_i, v_j, c_j, c_i):轮廓 0 → 壳边 +2
        //     拆两个三角:(v_i, v_j, c_j) + (v_i, c_j, c_i);
        //     近共线顶点处壳点 c_i 会贴近/重合 v_j,「斜坡+楔形」会退化成
        //     零面积三角导致边带缺覆盖,四边形在退化时仍覆盖完整边带。
        //     壳点沿内向方向外延 50%,盖住壳边与内缩填充边界间的像素缝隙。
        for (i in 0 until m) {
            val j = (i + 1) % m
            val c2x = cx[j] + (cx[j] - xs[j]) * 0.5f
            val c2y = cy[j] + (cy[j] - ys[j]) * 0.5f
            val c1x = cx[i] + (cx[i] - xs[i]) * 0.5f
            val c1y = cy[i] + (cy[i] - ys[i]) * 0.5f
            sink.triangleAA(xs[i], ys[i], 0f, xs[j], ys[j], 0f, c2x, c2y, cov)
            sink.triangleAA(xs[i], ys[i], 0f, c2x, c2y, cov, c1x, c1y, cov)
        }

        // ── 3. 内缩多边形填充(顶点 = 壳点,coverage = 到轮廓真距离)──
        fillInset(cx, cy, m, xs, ys, m, orientation, sink)
    }

    /**
     * 内缩多边形填充:凸 → 扇形 / 凹 → 耳切,顶点 coverage = 到原始轮廓边的
     * 真距离 × aaScale(≥ 壳深 2px → smoothstep 恒为 1,完全覆盖)。
     * 不发 fringe/角帽:内缩多边形边界距轮廓 ≥ 2px,与壳的 +2 连续。
     * 耳切前先剔除近共线顶点(曲线细分冗余点,|sin| < 0.02):稠密近共线点会令
     * 耳判定的叉积符号抖动而找不到耳,收尾兜底输出跨越轮廓的非法三角形。
     */
    private fun fillInset(
        px: FloatArray, py: FloatArray, m: Int,
        ox: FloatArray, oy: FloatArray, on: Int,
        orientation: Int, sink: Sink,
    ) {
        if (m < 3) return
        fun trueDist(x: Float, y: Float): Float {
            var best = Float.MAX_VALUE
            for (i in 0 until on) {
                val j = (i + 1) % on
                val d = distToSegment(x, y, ox[i], oy[i], ox[j], oy[j])
                if (d < best) best = d
            }
            return best * sink.aaScale
        }

        // 剔除近共线顶点(|sin| < 0.02 ≈ 1.2°),避免耳判定失效;
        // 对每段连续剔除的壳点 [r_1..r_k](端点 B_prev/B_next 为保留壳点),
        // 从壳边向简化弦 (B_prev→B_next) 扇形填充 (B_prev, r_j, B_next):
        // 简化弦直连 S 弯两端壳点,弦与原壳边之间留出缝隙
        // (高 aaScale 下呈扁平空洞),扇形把缝隙完整填平。
        val keep = ArrayList<Int>(m)
        var idx = 0
        while (idx < m) {
            val a = (idx - 1 + m) % m
            val b = idx
            val c = (idx + 1) % m
            val abx = px[b] - px[a]
            val aby = py[b] - py[a]
            val bcx = px[c] - px[b]
            val bcy = py[c] - py[b]
            val len = sqrt((abx * abx + aby * aby) * (bcx * bcx + bcy * bcy))
            if (len < 1e-6f) {
                keep.add(b)
                idx++
                continue
            }
            if (abs(abx * bcy - aby * bcx) / len >= 0.02f) {
                keep.add(b)
                idx++
                continue
            }
            // 连续剔除段 [idx .. j]
            var j = idx
            while (true) {
                val jj = (j + 1) % m
                val pa = (j - 1 + m) % m
                val abx2 = px[j] - px[pa]
                val aby2 = py[j] - py[pa]
                val bcx2 = px[jj] - px[j]
                val bcy2 = py[jj] - py[j]
                val len2 = sqrt((abx2 * abx2 + aby2 * aby2) * (bcx2 * bcx2 + bcy2 * bcy2))
                if (len2 < 1e-6f || abs(abx2 * bcy2 - aby2 * bcx2) / len2 >= 0.02f) break
                j = jj
            }
            val bp = (idx - 1 + m) % m
            val bn = (j + 1) % m
            var r = idx
            while (true) {
                sink.triangleAA(
                    px[bp], py[bp], trueDist(px[bp], py[bp]),
                    px[r], py[r], trueDist(px[r], py[r]),
                    px[bn], py[bn], trueDist(px[bn], py[bn]),
                )
                if (r == j) break
                r = (r + 1) % m
            }
            idx = j + 1
        }
        val n = keep.size
        if (n < 3) return
        val qx = FloatArray(n)
        val qy = FloatArray(n)
        val values = FloatArray(n)
        for (k in 0 until n) {
            val i = keep[k]
            qx[k] = px[i]
            qy[k] = py[i]
            values[k] = trueDist(px[i], py[i])
        }

        if (isConvex(qx, qy, n)) {
            // 凸:从形心扇形,顶点 coverage = 真距离
            var ccx = 0f
            var ccy = 0f
            for (k in 0 until n) {
                ccx += qx[k]
                ccy += qy[k]
            }
            ccx /= n
            ccy /= n
            val dc = trueDist(ccx, ccy)
            for (k in 0 until n) {
                val j = (k + 1) % n
                sink.triangleAA(qx[k], qy[k], values[k], qx[j], qy[j], values[j], ccx, ccy, dc)
            }
            return
        }

        // 凹:耳切(无 fringe/角帽),顶点 coverage = 真距离
        val prev = IntArray(n)
        val next = IntArray(n)
        for (i in 0 until n) {
            prev[i] = (i - 1 + n) % n
            next[i] = (i + 1) % n
        }
        var remaining = n
        var guard = 0
        val maxGuard = n * n * 2
        var i = 0
        while (remaining > 3 && guard++ < maxGuard) {
            val a = prev[i]
            val b = i
            val c = next[i]
            if (isEar(a, b, c, orientation, qx, qy, prev, next, remaining)) {
                sink.triangleAA(qx[a], qy[a], values[a], qx[b], qy[b], values[b], qx[c], qy[c], values[c])
                next[a] = c
                prev[c] = a
                remaining--
            }
            i = next[i]
        }
        // 收尾:剩余环兜底输出(值 ≥ 壳深,过渡不可见)
        if (remaining >= 3) {
            var idx = i
            var count = 0
            while (count < remaining - 2) {
                val a = prev[idx]
                val b = idx
                val c = next[idx]
                sink.triangleAA(qx[a], qy[a], values[a], qx[b], qy[b], values[b], qx[c], qy[c], values[c])
                next[a] = c
                prev[c] = a
                idx = c
                count++
            }
        }
    }

    /** 凸性检测(数组形式,供内缩多边形使用) */
    private fun isConvex(px: FloatArray, py: FloatArray, n: Int): Boolean {
        if (n < 3) return true
        var sign = 0
        for (i in 0 until n) {
            val a = (i - 1 + n) % n
            val c = (i + 1) % n
            val abx = px[i] - px[a]
            val aby = py[i] - py[a]
            val bcx = px[c] - px[i]
            val bcy = py[c] - py[i]
            val cross = abx * bcy - aby * bcx
            val len = sqrt((abx * abx + aby * aby) * (bcx * bcx + bcy * bcy))
            if (len < 1e-6f) continue
            val s = cross / len
            if (abs(s) < 0.01f) continue
            val thisSign = if (s > 0f) 1 else -1
            if (sign == 0) sign = thisSign
            else if (sign != thisSign) return false
        }
        return true
    }

    /**
     * 顶点 b 是否为凸耳:局部凸(含近共线容差)+ 三角形内无其他顶点。
     * 点在三角形边界上(共线,如拐点处连续共线壳点)不算内部 ——
     * 否则边界上的共线点会让所有耳判定失效,耳切卡死进入兜底输出垃圾三角形。
     */
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
            if (i != b && i != c && pointInTriangle(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b], xs[c], ys[c])) {
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
        // 边界容差:点在边上(共线,|d| < eps)不算内部,避免拐点共线点卡死耳切
        val eps = 1e-4f
        val hasNeg = d1 < -eps || d2 < -eps || d3 < -eps
        val hasPos = d1 > eps || d2 > eps || d3 > eps
        return !(hasNeg && hasPos)
    }

    // ── 自交多边形 EvenOdd 填充 ────────────────────────────────────────────

    private data class Crossing(val i: Int, val t: Float, val j: Int, val u: Float, val x: Float, val y: Float)

    private data class SubEdge(val ax: Float, val ay: Float, val bx: Float, val by: Float)

    /** 非相邻边交点(端点接触不算自交) */
    private fun findCrossings(xs: FloatArray, ys: FloatArray, n: Int): List<Crossing> {
        val out = ArrayList<Crossing>()
        for (i in 0 until n) {
            val ax = xs[i]
            val ay = ys[i]
            val bx = xs[(i + 1) % n]
            val by = ys[(i + 1) % n]
            for (j in i + 1 until n) {
                if (j == i || (j + 1) % n == i || j == (i + 1) % n) continue
                val cx = xs[j]
                val cy = ys[j]
                val dx = xs[(j + 1) % n]
                val dy = ys[(j + 1) % n]
                val denom = (ax - bx) * (cy - dy) - (ay - by) * (cx - dx)
                if (abs(denom) < 1e-9f) continue
                val t = ((ax - cx) * (cy - dy) - (ay - cy) * (cx - dx)) / denom
                val u = ((ax - cx) * (ay - by) - (ay - cy) * (ax - bx)) / denom
                if (t < -1e-9f || t > 1f + 1e-9f || u < -1e-9f || u > 1f + 1e-9f) continue
                if ((t < 1e-6f && u < 1e-6f) || (t < 1e-6f && u > 1f - 1e-6f) ||
                    (t > 1f - 1e-6f && u < 1e-6f) || (t > 1f - 1e-6f && u > 1f - 1e-6f)
                ) continue
                out.add(Crossing(i, t, j, u, ax + t * (bx - ax), ay + t * (by - ay)))
            }
        }
        return out
    }

    /**
     * 自交多边形 EvenOdd 填充:交点切分边 + 水平条带梯形扫描。
     * 奇数绕序的条带区间填充;梯形左右边在轮廓上 coverage 0,
     * 上下边(条带内部)OPAQUE —— 轮廓内侧有距离斜坡,配合外侧 fringe 呈完整 AA。
     */
    private fun evenOddFill(xs: FloatArray, ys: FloatArray, n: Int, sink: Sink) {
        val crossings = findCrossings(xs, ys, n)
        if (crossings.isEmpty()) return

        // 切分边 → 子边
        val edges = ArrayList<SubEdge>(n + crossings.size * 2)
        for (i in 0 until n) {
            val p1x = xs[i]
            val p1y = ys[i]
            val p2x = xs[(i + 1) % n]
            val p2y = ys[(i + 1) % n]
            val ts = ArrayList<Float>(4)
            ts.add(0f); ts.add(1f)
            for ((i1, t, j, u) in crossings) {
                if (i1 == i) ts.add(t)
                if (j == i) ts.add(u)
            }
            ts.sort()
            var prev = ts[0]
            for (k in 1 until ts.size) {
                val t = ts[k]
                if (t - prev < 1e-7f) {
                    prev = t; continue
                }
                val x0 = p1x + prev * (p2x - p1x)
                val y0 = p1y + prev * (p2y - p1y)
                val x1 = p1x + t * (p2x - p1x)
                val y1 = p1y + t * (p2y - p1y)
                if (dist(x0, y0, x1, y1) > 1e-7f) edges.add(SubEdge(x0, y0, x1, y1))
                prev = t
            }
        }
        if (edges.isEmpty()) return

        // 条带边界 y 值(顶点 + 交点)
        val ysSet = sortedSetOf<Float>()
        for ((_, ay, _, by) in edges) {
            ysSet.add(ay); ysSet.add(by)
        }
        val ysArr = ysSet.toFloatArray()

        for (k in 0 until ysArr.size - 1) {
            val y0 = ysArr[k]
            val y1 = ysArr[k + 1]
            if (y1 - y0 < 1e-9f) continue
            val ym = (y0 + y1) / 2f
            // 与条带中部相交的活动边
            val active = ArrayList<SubEdge>(8)
            for (e in edges) {
                if ((e.ay - ym) * (e.by - ym) < 0f) active.add(e)
            }
            if (active.size < 2) continue
            fun xat(e: SubEdge, yy: Float): Float {
                if (abs(e.by - e.ay) < 1e-9f) return (e.ax + e.bx) / 2f
                return e.ax + (yy - e.ay) * (e.bx - e.ax) / (e.by - e.ay)
            }
            active.sortWith(compareBy { xat(it, ym) })
            var i = 0
            while (i + 1 < active.size) {
                val e0 = active[i]
                val e1 = active[i + 1]
                // 梯形 (l0, r0, r1, l1):左右边在轮廓上(0),上下边内部(OPAQUE)
                val l0x = xat(e0, y0)
                val r0x = xat(e1, y0)
                val r1x = xat(e1, y1)
                val l1x = xat(e0, y1)
                if (dist(l0x, y0, r0x, y0) < 1e-6f && dist(l1x, y1, r1x, y1) < 1e-6f) {
                    i += 2
                    continue
                }
                sink.triangleAA(l0x, y0, OPAQUE, r0x, y0, OPAQUE, r1x, y1, OPAQUE)
                sink.triangleAA(l0x, y0, OPAQUE, r1x, y1, OPAQUE, l1x, y1, OPAQUE)
                i += 2
            }
        }
    }

    /**
     * 圆/椭圆细分段数:每段弦长约 0.5 屏幕像素(弧轮廓折线角彻底不可见)。
     */
    private fun circleSegments(radius: Float, aaScale: Float): Int =
        max(96, (2.0 * PI * radius * aaScale * 2.0).roundToInt())
}
