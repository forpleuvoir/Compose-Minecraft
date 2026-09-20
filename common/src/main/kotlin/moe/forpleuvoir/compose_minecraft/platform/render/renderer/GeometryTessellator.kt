@file:Suppress("GrazieInspection", "SpellCheckingInspection")

package moe.forpleuvoir.compose_minecraft.platform.render.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CornerPathEffect
import androidx.compose.ui.graphics.DashPathEffect
import androidx.compose.ui.graphics.MinecraftPath
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// ─────────────────────────────────────────────────────────────────────────────
// 几何三角化器(抗锯齿版,第二版)
//
// 把 Compose 绘制命令的几何(局部坐标,场景 px,密度 1)三角化为交错
// [x, y, coverage] 顶点序列,交给 GuiTriangleRenderState 以 TRIANGLES
// 拓扑提交。
//
// coverage 语义(与片元着色器 gui_triangles.fsh 配合):
// - **有符号屏幕像素距离**:到「最近真实轮廓边」的带符号距离(× Sink.aaScale,
//   其中 aaScale = 矩阵最大轴缩放 × guiScale,即局部坐标 → 物理像素);
//   轮廓外侧为负、真实轮廓上为 0、轮廓内侧为正;
// - 过渡带:固定 smoothstep(-1.0, 1.0, d)(fill 与 stroke 分属两个 shader,
//   gui_triangles / gui_triangles_stroke),恒约 2 物理像素(跨真实轮廓两侧);
//   几何外扩/内缩深度取 1.5px(> fwidth 最大值 √2,任意斜角下边缘 alpha 归零);
//   不能用 fwidth:描边是分段三角形,GPU fwidth 在三角形边界(coverage 场跳变)
//   测大梯度 → 带内半透明/边缘断点(实测回退);
// - **距离场连续性铁律**:任何顶点 coverage 必须是「到轮廓的近似距离」
//   (梯度 ≈ 1,fwidth ≈ 1,过渡带处处等宽且垂直轮廓);OPAQUE 大数只允许
//   用于距轮廓 ≥ 1.5px 的纯内部三角形(quad 等无轮廓图元)。
//
// 第二版结构(与第一版 fringe+角帽 的区别):
// - 所有图元先降为「轮廓多边形」:圆/椭圆/弧/圆角矩形/贝塞尔按**弦高**
//   判据自适应细分(弦高 ≤ 0.25px,圆段数比第一版少约 13 倍);
// - 填充 = 三环:
//   1. 外扩带:真实轮廓(coverage 0)→ miter 膨胀轮廓(-1),每边 2 三角形,
//      膨胀轮廓自闭合(凸角 = 相邻边偏移线交点,凹角 = 角平分线平移),
//      **无角帽三角形**,外部楔形被膨胀轮廓自然覆盖;
//   2. 内缩带:真实轮廓(0)→ miter 收缩轮廓(+1),同理自闭合;
//   3. 内部填充:收缩轮廓内部凸 → 形心扇形 / 凹 → 耳切,顶点 coverage =
//      到原始轮廓的真距离(≥ 1.5px,与内缩带 +1.5 同处饱和区,无缝);
// - 描边 = 带展开:每段带四边形 coverage 用「到对侧轮廓边的垂直距离」
//   (真距离近似),外/内轮廓边各带 1.5px fringe;**fringe 与轮廓同构** ——
//   每个截面携带自己的法线,fringe 两端各用各的法线外推,相邻段共享
//   边界边(弯曲顶点处凸侧无楔形裂缝、凹侧无重叠双混合,与填充外扩带
//   的连续 miter 轮廓同构;旧实现整段单法线外推是圆/弧描边毛刺的根因);
//   join 支持 Round/Bevel/Miter(Skia 截断判据),闭合路径首尾 join 由
//   顶点环自然闭合;
// - 自交路径:交点切分 → EvenOdd 子环,每个子环独立三环填充;
//   切分失败兜底只画外扩带(不输出错误三角形);
// - 近共线简化:flatten 后先剔除贝塞尔细分冗余点(贪心直线段延伸),
//   从根源消除耳切失败与角帽噪音(第一版毛刺主因)。
//
// 已知简化(与 Skia 语义差异,文档记录):
// - strokeWidth 在局部坐标展开,矩阵缩放会等比影响线宽(未做设备空间补偿);
// - strokeWidth == 0 按 hairline 1px 处理;
// - 三角形绕序统一视觉顺时针;pipeline 侧已关闭背面剔除,不依赖绕序正确性;
// - aaScale 用矩阵最大轴缩放 × guiScale,非均匀缩放下 AA 宽度在次轴略偏;
// - 凹多边形内缩壳在窄缝(< 2px)处可能翻面,内部填充跳过(壳带仍覆盖过渡区);
// - dash 闭合路径在接缝点若被 on 区间跨过,该 on 段会在接缝处分成两截
//   (端帽重合,视觉接近无缝);零长度 on 区间不画圆点(Skia 圆帽才有点)。
// ─────────────────────────────────────────────────────────────────────────────

internal object GeometryTessellator {

    /** 内部实心三角形 coverage 大数(仅用于无轮廓图元 / 距轮廓 ≥ 1.5px 的纯内部) */
    private const val OPAQUE = 1e4f

    /**
     * 顶点/三角形输出缓冲:无装箱 [FloatArray] 交错平铺 [x, y, coverage]。
     * 翻倍扩容,clear() 只重置长度(零分配复用)。
     */
    class Sink {
        /**
         * 局部坐标 → 屏幕像素的缩放系数(矩阵最大轴缩放 × guiScale),
         * 由回放端在每命令前设置;coverage 距离按它换算。
         */
        var aaScale: Float = 1f

        private var data = FloatArray(384)

        var vertexCount: Int = 0
            private set

        private fun ensure(extra: Int) {
            val need = vertexCount * 3 + extra
            if (need > data.size) {
                var size = data.size
                while (size < need) size *= 2
                data = data.copyOf(size)
            }
        }

        fun vertex(x: Float, y: Float, cov: Float) {
            ensure(3)
            val i = vertexCount * 3
            data[i] = x
            data[i + 1] = y
            data[i + 2] = cov
            vertexCount++
        }

        fun triangle(
            ax: Float, ay: Float, ac: Float,
            bx: Float, by: Float, bc: Float,
            cx: Float, cy: Float, cc: Float,
        ) {
            vertex(ax, ay, ac); vertex(bx, by, bc); vertex(cx, cy, cc)
        }

        /** 实心四边形(无轮廓,coverage = OPAQUE) */
        fun quad(
            ax: Float, ay: Float,
            bx: Float, by: Float,
            cx: Float, cy: Float,
            dx: Float, dy: Float,
        ) {
            triangle(ax, ay, OPAQUE, bx, by, OPAQUE, cx, cy, OPAQUE)
            triangle(ax, ay, OPAQUE, cx, cy, OPAQUE, dx, dy, OPAQUE)
        }

        fun clear() {
            vertexCount = 0
        }

        /** 交错 [x, y, coverage] 拷贝(每命令一次) */
        fun toArray(): FloatArray = data.copyOf(vertexCount * 3)
    }

    // ── 细分判据 ──────────────────────────────────────────────────────────

    /**
     * 圆弧段数:按**弦高 ≤ 0.1 物理像素**自适应(远小于 AA 过渡带,折角不可见)。
     * 段数 = π / acos(1 - 0.1/r),钳制 [12, 1024]。
     */
    private fun arcSegments(radiusPx: Float): Int {
        val r = radiusPx
        if (r <= 0.2f) return 12
        val half = acos((1f - 0.1f / r).coerceIn(-1f, 1f))
        return ceil(PI / half).toInt().coerceIn(12, 1024)
    }

    /**
     * 描边细分段数:按**段长 ≤ 2 物理像素**(取与 [arcSegments] 的较大者)。
     * 段长 2px 是细分密度与性能的折中(更密对边缘质量无进一步改善但
     * 帧数大幅下降);段边界处的 coverage 场连续性由逐截面法线 fringe
     * 保证(见 StrokeEmitter),不依赖更短的段长。
     */
    private fun strokeSegments(radiusPx: Float): Int {
        val byAngle = arcSegments(radiusPx)
        val byLength = ceil(2.0 * PI * radiusPx / 2.0).toInt()
        return max(byAngle, byLength).coerceAtMost(2048)
    }

    /** 圆/椭圆点列(起点 3 点钟,顺时针,y-down) */
    private fun ellipsePoints(
        cx: Float, cy: Float, rx: Float, ry: Float,
        startAngle: Double, sweep: Double, segments: Int,
    ): FloatArray {
        val pts = FloatArray((segments + 1) * 2)
        for (i in 0..segments) {
            val a = startAngle + sweep * i / segments
            pts[i * 2] = cx + rx * cos(a).toFloat()
            pts[i * 2 + 1] = cy + ry * sin(a).toFloat()
        }
        return pts
    }

    /** 贝塞尔细分段数:控制多边形总长按弦长 ~1 物理像素 */
    private fun curveSteps(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, aaScale: Float): Int {
        val len = (dist(x0, y0, x1, y1) + dist(x1, y1, x2, y2)) * aaScale
        return max(1, ceil(len / 1f).toInt())
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

    // ── 近共线简化 ────────────────────────────────────────────────────────

    /**
     * 贪心简化:剔除贝塞尔细分产生的近共线冗余点(第一版耳切失败/角帽噪音
     * 的根源),顺带剔除重复点。
     *
     * 从保留点 i 贪心延伸直线段:并入点 j+1 前,**重新检查 i..j+1 所有中间点
     * 到新弦 (i, j+1) 的垂直距离**,任何一点超过 eps 则段止于 j。
     * (不能只检查 j+1 到旧弦 (i, j) —— 那会允许中间点相对最终弦的累积
     * 偏离,圆会被压成弦高 ~1px 的多边形。)
     * eps = 0.15 物理像素:最终弦高上限,远小于 AA 过渡带。
     */
    private fun simplifyPolygon(pts: FloatArray, aaScale: Float): FloatArray {
        var n = pts.size / 2
        // 首尾重复点剔除(闭合路径;三角形等小多边形也必须处理 ——
        // 否则 fillPolygon 把重复顶点当独立点,产生零长度边 → 退化三角形
        // + 角部楔形,coverage 场混乱,如三角形底边左端异常)
        if (n >= 3 && dist(pts[0], pts[1], pts[(n - 1) * 2], pts[(n - 1) * 2 + 1]) < 1e-4f) {
            n--
        }
        if (n < 3) return pts
        if (n < 6) {
            return if (n == pts.size / 2) pts else pts.copyOf(n * 2)
        }
        val eps = 0.15f / aaScale
        val outX = ArrayList<Float>(n)
        val outY = ArrayList<Float>(n)
        var i = 0
        while (i < n) {
            val ix = pts[i * 2]
            val iy = pts[i * 2 + 1]
            if (outX.isNotEmpty()) {
                val lx = outX[outX.size - 1]
                val ly = outY[outY.size - 1]
                if (dist(ix, iy, lx, ly) < 1e-4f) {
                    i++
                    continue
                }
            }
            outX.add(ix)
            outY.add(iy)
            var j = i
            var canExtend = true
            while (canExtend && j + 1 < n) {
                val jx = pts[(j + 1) * 2]
                val jy = pts[(j + 1) * 2 + 1]
                // 1) 新点必须延续当前方向(防尖角点被吞,如扇形圆心):
                //    j+1 到「段起点 i 与段末 j 连线」的距离 ≤ eps;
                //    j == i(单点段无方向)时退化为「j+1 到 i 前一点与 i 连线」的距离。
                //    若 i 是首点(无前一点),跳过方向检查(靠 2)兜底)。
                val ref = if (j > i) j else i - 1
                if (ref >= 0 && distToLine(jx, jy, ix, iy, pts[ref * 2], pts[ref * 2 + 1]) > eps) {
                    canExtend = false
                    break
                }
                // 2) 弦高检查:所有中间点(含新点)到新弦 (i, j+1) 的距离 ≤ eps
                //    (防累积偏离:贪心只查相邻弦会允许圆被压成 ~1px 弦高的多边形)
                for (k in i + 1..j + 1) {
                    if (distToLine(pts[k * 2], pts[k * 2 + 1], ix, iy, jx, jy) > eps) {
                        canExtend = false
                        break
                    }
                }
                if (canExtend) j++
            }
            // 段末点 j 是下一段的起点(若 j == i 则本点独立成段,跳过)
            i = if (j > i) j else j + 1
        }
        // 闭合:末点与首点重合 → 去掉末点
        if (outX.size >= 3) {
            val fx = outX[0]
            val fy = outY[0]
            val lx = outX[outX.size - 1]
            val ly = outY[outY.size - 1]
            if (dist(fx, fy, lx, ly) < 1e-4f) {
                outX.removeAt(outX.size - 1)
                outY.removeAt(outY.size - 1)
            }
        }
        if (outX.size < 3) return pts
        val out = FloatArray(outX.size * 2)
        for (k in outX.indices) {
            out[k * 2] = outX[k]
            out[k * 2 + 1] = outY[k]
        }
        return out
    }

    // ── miter 轮廓偏移 ────────────────────────────────────────────────────

    /** 多边形有向面积(2 倍,数学符号) */
    private fun signedArea2(pts: FloatArray, n: Int): Float {
        var area = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1]
        }
        return area
    }

    /**
     * 多边形偏移(miter):返回偏移后轮廓 [x, y] 平铺 n 点。
     * [d] > 0 膨胀(外扩带外侧),[d] < 0 收缩(内缩壳内侧)。
     * - 凸顶点(转向与面积符号同号):相邻边偏移线交点(深度 |d|/cos(θ/2)),
     *   超限(> 2.5|d|,尖角)沿交点方向截断;
     *   ⚠ 不能钳到 |d|:角平分线与边夹角 θ/2,垂距 = 深度×sin(θ/2),钳到 |d|
     *   会使角部带垂距不足(53° 角仅 0.67px)→ 底边两端过渡带断层;
     * - 凹顶点 / 近共线:沿角平分线(入/出边外法线平均)平移 [d]。
     * [orientation] = 面积符号(±1),确定外法线侧:外法线 = 边方向右法线 × orientation。
     */
    private fun offsetPolygon(pts: FloatArray, n: Int, d: Float, orientation: Int): FloatArray {
        val out = FloatArray(n * 2)
        val ex = FloatArray(n)
        val ey = FloatArray(n)
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            var dx = pts[j * 2] - pts[i * 2]
            var dy = pts[j * 2 + 1] - pts[i * 2 + 1]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-6f) continue
            dx /= len
            dy /= len
            ex[i] = dx
            ey[i] = dy
            nx[i] = dy * orientation
            ny[i] = -dx * orientation
        }
        for (i in 0 until n) {
            val prev = (i - 1 + n) % n
            val cross = ex[prev] * ey[i] - ey[prev] * ex[i]
            if (cross * orientation > 0.01f && abs(cross) > 1e-4f) {
                // 凸顶点:miter 交点
                val ppx = pts[prev * 2] + nx[prev] * d
                val ppy = pts[prev * 2 + 1] + ny[prev] * d
                val pix = pts[i * 2] + nx[i] * d
                val piy = pts[i * 2 + 1] + ny[i] * d
                val s = ((pix - ppx) * ey[i] - (piy - ppy) * ex[i]) / cross
                var ix = ppx + ex[prev] * s
                var iy = ppy + ey[prev] * s
                // 尖角钳制:交点距顶点过远 → 沿交点方向截断。
                // ⚠ 不能钳到 |d|:角平分线方向与边夹角 θ/2,垂距 = 深度×sin(θ/2),
                // 钳到 |d| 会让角部外扩/内缩带垂距不足(如 53° 角垂距仅 0.67px)
                // → 底边两端过渡带断层(___ 阶梯)。2.5|d| 保持垂距 ≥ |d|(过渡带完整)。
                val ox = ix - pts[i * 2]
                val oy = iy - pts[i * 2 + 1]
                val od = sqrt(ox * ox + oy * oy)
                val limit = 2.5f * abs(d)
                if (od > limit && od > 1e-6f) {
                    ix = pts[i * 2] + ox / od * limit
                    iy = pts[i * 2 + 1] + oy / od * limit
                }
                out[i * 2] = ix
                out[i * 2 + 1] = iy
            } else {
                // 凹顶点 / 近共线:角平分线(法线平均)平移 d
                var mx = nx[prev] + nx[i]
                var my = ny[prev] + ny[i]
                val ml = sqrt(mx * mx + my * my)
                if (ml < 1e-6f) {
                    mx = nx[i]
                    my = ny[i]
                }
                out[i * 2] = pts[i * 2] + mx / ml * d
                out[i * 2 + 1] = pts[i * 2 + 1] + my / ml * d
            }
        }
        return out
    }

    // ── 填充三环 ──────────────────────────────────────────────────────────

    /**
     * 多边形填充(三环):外扩带(0 → -1)+ 内缩带(0 → +1)+ 内部填充。
     * 自交路径 → [splitFill] 切分为 EvenOdd 子环。
     */
    private fun fillPolygon(ptsIn: FloatArray, sink: Sink) {
        val pts = simplifyPolygon(ptsIn, sink.aaScale)
        val n = pts.size / 2
        if (n < 3) return
        // 自交检测优先:自交对称图形(如蝴蝶结 X 形)的有向面积恰好为 0,
        // 若先用面积判定退化会被直接丢弃 —— 先查 crossings,非空走 splitFill
        val crossings = findCrossings(pts, n)
        if (crossings.isNotEmpty()) {
            splitFill(pts, n, crossings, sink)
            return
        }
        val area2 = signedArea2(pts, n)
        if (abs(area2) < 1e-6f) return
        val orientation = if (area2 >= 0f) 1 else -1

        val w = 1.5f / sink.aaScale
        val outer = offsetPolygon(pts, n, w, orientation)
        val inner = offsetPolygon(pts, n, -w, orientation)

        // 1. 外扩带:轮廓 0 → 膨胀轮廓 -1.5(每边 2 三角形,自闭合无角帽)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val bx = pts[j * 2]; val by = pts[j * 2 + 1]
            val ox = outer[i * 2]; val oy = outer[i * 2 + 1]
            val px = outer[j * 2]; val py = outer[j * 2 + 1]
            sink.triangle(ax, ay, 0f, bx, by, 0f, px, py, -1.5f)
            sink.triangle(ax, ay, 0f, px, py, -1.5f, ox, oy, -1.5f)
        }
        // 2. 内缩带:轮廓 0 → 收缩轮廓 +1.5(饱和区,与内部填充无缝)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val bx = pts[j * 2]; val by = pts[j * 2 + 1]
            val ix = inner[i * 2]; val iy = inner[i * 2 + 1]
            val qx = inner[j * 2]; val qy = inner[j * 2 + 1]
            sink.triangle(ax, ay, 0f, bx, by, 0f, qx, qy, 1.5f)
            sink.triangle(ax, ay, 0f, qx, qy, 1.5f, ix, iy, 1.5f)
        }
        // 3. 内部填充:收缩轮廓内 fan / 耳切,coverage = 到原始轮廓真距离(≥ 1.5px,饱和)
        fillInner(inner, n, pts, n, sink)
    }

    /** 内部填充:收缩轮廓内凸 → 形心扇形 / 凹 → 耳切 */
    private fun fillInner(inner: FloatArray, m: Int, orig: FloatArray, on: Int, sink: Sink) {
        val innerArea = signedArea2(inner, m)
        val origArea = signedArea2(orig, on)
        // 收缩轮廓翻面/退化(窄缝 < 2px):壳带已覆盖过渡区,跳过内部
        if (innerArea * origArea <= 0f) return

        fun trueDist(x: Float, y: Float): Float {
            var best = Float.MAX_VALUE
            for (i in 0 until on) {
                val j = (i + 1) % on
                val d = distToSegment(x, y, orig[i * 2], orig[i * 2 + 1], orig[j * 2], orig[j * 2 + 1])
                if (d < best) best = d
            }
            return best * sink.aaScale
        }

        if (isConvex(inner, m)) {
            var ccx = 0f
            var ccy = 0f
            for (i in 0 until m) {
                ccx += inner[i * 2]
                ccy += inner[i * 2 + 1]
            }
            ccx /= m
            ccy /= m
            val dc = trueDist(ccx, ccy)
            for (i in 0 until m) {
                val j = (i + 1) % m
                sink.triangle(
                    inner[i * 2], inner[i * 2 + 1], trueDist(inner[i * 2], inner[i * 2 + 1]),
                    inner[j * 2], inner[j * 2 + 1], trueDist(inner[j * 2], inner[j * 2 + 1]),
                    ccx, ccy, dc,
                )
            }
        } else {
            earClipInner(inner, m, orig, on, sink)
        }
    }

    /** 内缩轮廓(凹)耳切填充:顶点 coverage = 到原始轮廓真距离(≥ 1.5px 饱和,无视觉兜底垃圾) */
    private fun earClipInner(inner: FloatArray, m: Int, orig: FloatArray, on: Int, sink: Sink) {
        val innerArea = signedArea2(inner, m)
        val orientation = if (innerArea >= 0f) 1 else -1

        fun trueDist(x: Float, y: Float): Float {
            var best = Float.MAX_VALUE
            for (i in 0 until on) {
                val j = (i + 1) % on
                val d = distToSegment(x, y, orig[i * 2], orig[i * 2 + 1], orig[j * 2], orig[j * 2 + 1])
                if (d < best) best = d
            }
            return best * sink.aaScale
        }

        val xs = FloatArray(m)
        val ys = FloatArray(m)
        for (i in 0 until m) {
            xs[i] = inner[i * 2]
            ys[i] = inner[i * 2 + 1]
        }
        val prev = IntArray(m)
        val next = IntArray(m)
        for (i in 0 until m) {
            prev[i] = (i - 1 + m) % m
            next[i] = (i + 1) % m
        }
        var remaining = m
        var guard = 0
        var i = 0
        while (remaining > 3 && guard++ < m * m * 2) {
            val a = prev[i]
            val b = i
            val c = next[i]
            if (isEar(a, b, c, orientation, xs, ys, next, remaining)) {
                sink.triangle(
                    xs[a], ys[a], trueDist(xs[a], ys[a]),
                    xs[b], ys[b], trueDist(xs[b], ys[b]),
                    xs[c], ys[c], trueDist(xs[c], ys[c]),
                )
                next[a] = c
                prev[c] = a
                remaining--
            }
            i = next[i]
        }
        // 收尾:剩余环兜底输出(coverage ≥ 1.5px 饱和,不产生垃圾视觉)
        var idx = i
        var count = 0
        while (count < remaining - 2) {
            val a = prev[idx]
            val b = idx
            val c = next[idx]
            sink.triangle(
                xs[a], ys[a], trueDist(xs[a], ys[a]),
                xs[b], ys[b], trueDist(xs[b], ys[b]),
                xs[c], ys[c], trueDist(xs[c], ys[c]),
            )
            next[a] = c
            prev[c] = a
            idx = c
            count++
        }
    }

    // ── 阴影网格(平台扩展,GPU 距离场软阴影)──────────────────────────────

    /**
     * 软阴影网格(平台扩展,参照 Skia SkShadowUtils 的"形状模糊"语义):
     * 覆盖「轮廓外扩 [blurPx](≈3σ,模糊衰减区)+ 本体内部」,顶点 coverage =
     * 到轮廓真实距离 × [norm](norm = 1/(σ√2),片元插值后由 gui_shadow
     * 片元着色器的 erfc 高斯解析解转 alpha:轮廓 0 → 0.5,外部衰减,内部饱和)。
     *
     * 结构(与 [fillPolygon] 三环同构,仅外扩宽度与归一化不同):
     * 1. 外扩带:轮廓 0 → 膨胀轮廓 [blurPx](×norm);
     * 2. 内缩带:轮廓 0 → 收缩轮廓 +1.5(×norm);
     * 3. 内部填充:trueDist × norm(饱和)。
     * 自交路径暂不支持(直接返回空,阴影缺失可接受,与填充的 EvenOdd
     * 切分不同 —— 阴影是模糊的,低优先级)。
     */
    fun shadowFill(points: FloatArray, blurPx: Float, norm: Float, sink: Sink) {
        val pts = simplifyPolygon(points, sink.aaScale)
        val n = pts.size / 2
        if (n < 3) return
        val area2 = signedArea2(pts, n)
        if (abs(area2) < 1e-6f) return
        val orientation = if (area2 >= 0f) 1 else -1
        if (findCrossings(pts, n).isNotEmpty()) return

        val outer = offsetPolygon(pts, n, blurPx, orientation)
        val inner = offsetPolygon(pts, n, -1.5f, orientation)

        // 1. 外扩带:轮廓 0 → 膨胀轮廓 -blurPx(外部为负距离,×norm;
        //    shader alpha = 0.5·(1+erf(d')) 在负距离衰减 → 外部柔和过渡)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val bx = pts[j * 2]; val by = pts[j * 2 + 1]
            val ox = outer[i * 2]; val oy = outer[i * 2 + 1]
            val px = outer[j * 2]; val py = outer[j * 2 + 1]
            sink.triangle(ax, ay, 0f, bx, by, 0f, px, py, -blurPx * norm)
            sink.triangle(ax, ay, 0f, px, py, -blurPx * norm, ox, oy, -blurPx * norm)
        }
        // 2. 内缩带:轮廓 0 → 收缩轮廓 +1.5(归一化,饱和区)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val bx = pts[j * 2]; val by = pts[j * 2 + 1]
            val ix = inner[i * 2]; val iy = inner[i * 2 + 1]
            val qx = inner[j * 2]; val qy = inner[j * 2 + 1]
            sink.triangle(ax, ay, 0f, bx, by, 0f, qx, qy, 1.5f * norm)
            sink.triangle(ax, ay, 0f, qx, qy, 1.5f * norm, ix, iy, 1.5f * norm)
        }
        // 3. 内部填充:trueDist × norm(饱和)
        fillShadowInner(inner, n, pts, n, sink, norm)
    }

    /** 阴影内部填充(凸 → 形心扇形 / 凹 → 耳切),coverage = trueDist × norm */
    private fun fillShadowInner(inner: FloatArray, m: Int, orig: FloatArray, on: Int, sink: Sink, norm: Float) {
        val innerArea = signedArea2(inner, m)
        val origArea = signedArea2(orig, on)
        if (innerArea * origArea <= 0f) return

        fun trueDist(x: Float, y: Float): Float {
            var best = Float.MAX_VALUE
            for (i in 0 until on) {
                val j = (i + 1) % on
                val d = distToSegment(x, y, orig[i * 2], orig[i * 2 + 1], orig[j * 2], orig[j * 2 + 1])
                if (d < best) best = d
            }
            return best * sink.aaScale * norm
        }

        if (isConvex(inner, m)) {
            var ccx = 0f
            var ccy = 0f
            for (i in 0 until m) {
                ccx += inner[i * 2]
                ccy += inner[i * 2 + 1]
            }
            ccx /= m
            ccy /= m
            val dc = trueDist(ccx, ccy)
            for (i in 0 until m) {
                val j = (i + 1) % m
                sink.triangle(
                    inner[i * 2], inner[i * 2 + 1], trueDist(inner[i * 2], inner[i * 2 + 1]),
                    inner[j * 2], inner[j * 2 + 1], trueDist(inner[j * 2], inner[j * 2 + 1]),
                    ccx, ccy, dc,
                )
            }
        } else {
            earClipShadowInner(inner, m, orig, on, sink, norm)
        }
    }

    /** 阴影内缩轮廓(凹)耳切填充:coverage = trueDist × norm */
    private fun earClipShadowInner(inner: FloatArray, m: Int, orig: FloatArray, on: Int, sink: Sink, norm: Float) {
        val innerArea = signedArea2(inner, m)
        val orientation = if (innerArea >= 0f) 1 else -1

        fun trueDist(x: Float, y: Float): Float {
            var best = Float.MAX_VALUE
            for (i in 0 until on) {
                val j = (i + 1) % on
                val d = distToSegment(x, y, orig[i * 2], orig[i * 2 + 1], orig[j * 2], orig[j * 2 + 1])
                if (d < best) best = d
            }
            return best * sink.aaScale * norm
        }

        val xs = FloatArray(m)
        val ys = FloatArray(m)
        for (i in 0 until m) {
            xs[i] = inner[i * 2]
            ys[i] = inner[i * 2 + 1]
        }
        val prev = IntArray(m)
        val next = IntArray(m)
        for (i in 0 until m) {
            prev[i] = (i - 1 + m) % m
            next[i] = (i + 1) % m
        }
        var remaining = m
        var guard = 0
        var i = 0
        while (remaining > 3 && guard++ < m * m * 2) {
            val a = prev[i]
            val b = i
            val c = next[i]
            if (isEar(a, b, c, orientation, xs, ys, next, remaining)) {
                sink.triangle(
                    xs[a], ys[a], trueDist(xs[a], ys[a]),
                    xs[b], ys[b], trueDist(xs[b], ys[b]),
                    xs[c], ys[c], trueDist(xs[c], ys[c]),
                )
                next[a] = c
                prev[c] = a
                remaining--
            }
            i = next[i]
        }
        var idx = i
        var count = 0
        while (count < remaining - 2) {
            val a = prev[idx]
            val b = idx
            val c = next[idx]
            sink.triangle(
                xs[a], ys[a], trueDist(xs[a], ys[a]),
                xs[b], ys[b], trueDist(xs[b], ys[b]),
                xs[c], ys[c], trueDist(xs[c], ys[c]),
            )
            next[a] = c
            prev[c] = a
            idx = c
            count++
        }
    }

    // ── 自交多边形 EvenOdd 切分填充 ───────────────────────────────────────

    private data class Crossing(val i: Int, val t: Float, val j: Int, val u: Float, val x: Float, val y: Float)

    /** 非相邻边交点(端点接触不算自交) */
    private fun findCrossings(pts: FloatArray, n: Int): List<Crossing> {
        val out = ArrayList<Crossing>()
        for (i in 0 until n) {
            val ax = pts[i * 2]
            val ay = pts[i * 2 + 1]
            val bx = pts[(i + 1) % n * 2]
            val by = pts[(i + 1) % n * 2 + 1]
            for (j in i + 1 until n) {
                if (j == i || (j + 1) % n == i || j == (i + 1) % n) continue
                val cx = pts[j * 2]
                val cy = pts[j * 2 + 1]
                val dx = pts[(j + 1) % n * 2]
                val dy = pts[(j + 1) % n * 2 + 1]
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

    private class Hit(val t: Float, val x: Float, val y: Float, val partnerEdge: Int, val partnerT: Float)

    /**
     * 自交多边形 EvenOdd 填充:交点切分 → 子环遍历 → 每个子环独立三环填充。
     * 切分失败(数值异常)时只画外扩带,不输出错误三角形。
     */
    private fun splitFill(pts: FloatArray, n: Int, crossings: List<Crossing>, sink: Sink) {
        // 每条边上的命中点(交点 + 两端点),按 t 排序
        val hits = Array(n) { ArrayList<Hit>(4) }
        for (c in crossings) {
            hits[c.i].add(Hit(c.t, c.x, c.y, c.j, c.u))
            hits[c.j].add(Hit(c.u, c.x, c.y, c.i, c.t))
        }
        for (i in 0 until n) {
            hits[i].add(Hit(0f, pts[i * 2], pts[i * 2 + 1], -1, -1f))
            hits[i].add(Hit(1f, pts[(i + 1) % n * 2], pts[(i + 1) % n * 2 + 1], -1, -1f))
            hits[i].sortBy { it.t }
        }
        // 子边访问标记:边 i 的子边 s = (hits[i][s], hits[i][s+1])
        val used = Array(n) { BooleanArray(hits[it].size - 1) }

        val w = 1.5f / sink.aaScale
        // 外扩带兜底(子环失败时使用)
        fun outerBandOnly() {
            val area2 = signedArea2(pts, n)
            if (abs(area2) < 1e-6f) return
            val orientation = if (area2 >= 0f) 1 else -1
            val outer = offsetPolygon(pts, n, w, orientation)
            for (i in 0 until n) {
                val j = (i + 1) % n
                sink.triangle(
                    pts[i * 2], pts[i * 2 + 1], 0f,
                    pts[j * 2], pts[j * 2 + 1], 0f,
                    outer[j * 2], outer[j * 2 + 1], -1.5f,
                )
                sink.triangle(
                    pts[i * 2], pts[i * 2 + 1], 0f,
                    outer[j * 2], outer[j * 2 + 1], -1.5f,
                    outer[i * 2], outer[i * 2 + 1], -1.5f,
                )
            }
        }

        var rings = 0
        var startSub = 0
        for (startEdge in 0 until n) {
            val edgeHits = hits[startEdge]
            while (startSub < edgeHits.size - 1 && used[startEdge][startSub]) startSub++
            if (startSub >= edgeHits.size - 1) {
                startSub = 0
                continue
            }
            // 新环:从 (startEdge, startSub) 出发
            val ring = ArrayList<Float>(16)
            var edge = startEdge
            var s = startSub
            var guard = 0
            var ok = true
            while (guard++ <= n * 4 + 4) {
                if (s >= used[edge].size || used[edge][s]) { ok = false; break }
                used[edge][s] = true
                val h0 = hits[edge][s]
                val h1 = hits[edge][s + 1]
                if (ring.isEmpty()) {
                    ring.add(h0.x); ring.add(h0.y)
                } else {
                    // 与上一个点去重(同一交点)
                    val lx = ring[ring.size - 2]
                    val ly = ring[ring.size - 1]
                    if (dist(h0.x, h0.y, lx, ly) > 1e-5f) {
                        ring.add(h0.x); ring.add(h0.y)
                    }
                }
                if (h1.partnerEdge >= 0) {
                    // 交点:跳到配对边
                    val pe = hits[h1.partnerEdge]
                    var idx = -1
                    for (k in pe.indices) {
                        if (abs(pe[k].t - h1.partnerT) < 1e-6f) { idx = k; break }
                    }
                    if (idx < 0) { ok = false; break }
                    if (idx >= used[h1.partnerEdge].size) { ok = false; break }
                    edge = h1.partnerEdge
                    s = idx
                } else {
                    // 端点:进入下一条边
                    edge = (edge + 1) % n
                    s = 0
                }
                if (edge == startEdge && s == startSub) break // 闭环
            }
            if (ok && ring.size >= 6) {
                // 闭环:补回起点
                ring.add(ring[0])
                ring.add(ring[1])
                rings++
                fillPolygon(ring.toFloatArray(), sink)
            } else if (ring.size >= 6) {
                // 环遍历中断(遇到已用子边 / 数值异常):已收集的点 ≥ 3 个时
                // 补起点闭合输出(EvenOdd 子环)。修复:自交蝴蝶结的第二环
                // 会走到已用子边被丢弃 → 只渲染一半;这里兜底保留部分环。
                ring.add(ring[0])
                ring.add(ring[1])
                val area2 = signedArea2(ring.toFloatArray(), ring.size / 2)
                if (abs(area2) > 1e-6f) {
                    rings++
                    fillPolygon(ring.toFloatArray(), sink)
                }
            }
            // 继续扫描未访问子边
            startSub = 0
        }
        if (rings == 0) {
            outerBandOnly()
        }
    }

    // ── 凸性 / 耳切判定 ───────────────────────────────────────────────────

    private fun isConvex(pts: FloatArray, n: Int): Boolean {
        if (n < 3) return true
        val xs = pts
        var sign = 0
        for (i in 0 until n) {
            val a = (i - 1 + n) % n
            val c = (i + 1) % n
            val abx = xs[i * 2] - xs[a * 2]
            val aby = xs[i * 2 + 1] - xs[a * 2 + 1]
            val bcx = xs[c * 2] - xs[i * 2]
            val bcy = xs[c * 2 + 1] - xs[i * 2 + 1]
            val cross = abx * bcy - aby * bcx
            val len = sqrt((abx * abx + aby * aby) * (bcx * bcx + bcy * bcy))
            if (len < 1e-6f) continue
            val s = cross / len
            if (abs(s) < 0.01f) continue // 近共线(简化后应极少),不影响凸性
            val thisSign = if (s > 0f) 1 else -1
            if (sign == 0) sign = thisSign
            else if (sign != thisSign) return false
        }
        return true
    }

    private fun isEar(
        a: Int, b: Int, c: Int, orientation: Int,
        xs: FloatArray, ys: FloatArray,
        next: IntArray,
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
        val eps = 1e-4f
        val hasNeg = d1 < -eps || d2 < -eps || d3 < -eps
        val hasPos = d1 > eps || d2 > eps || d3 > eps
        return !(hasNeg && hasPos)
    }

    // ── 描边带 ────────────────────────────────────────────────────────────

    /** strokeWidth == 0 → hairline 1px(Compose 语义) */
    private fun effectiveWidth(strokeWidth: Float): Float =
        if (strokeWidth <= 0f) 1f else strokeWidth

    /**
     * 带发射器:中心线点对序列 → 带内 4 三角形 + 双侧 fringe。
     * 带内剖分按段两端中心线点是否同一顶点:边带用中心线点(cA/cB,段边界
     * coverage 场连续无分段线)、join 弧段用段中心(弧段对称时 = 顶点 V,不
     * 退化无缝隙);coverage 轮廓边 0、中心 +h;外 fringe 0 → -1.5(带外渐隐)、
     * 内 fringe 0 → -1.5(圆孔方向渐隐,方向与符号必须与 coverage 语义一致)。
     */
    private class StrokeEmitter(val sink: Sink, val h: Float, val w: Float) {
        private var prevOx = 0f
        private var prevOy = 0f
        private var prevIx = 0f
        private var prevIy = 0f
        private var prevCx = 0f
        private var prevCy = 0f
        private var prevNx = 0f
        private var prevNy = 0f
        private var firstOx = 0f
        private var firstOy = 0f
        private var firstIx = 0f
        private var firstIy = 0f
        private var firstCx = 0f
        private var firstCy = 0f
        private var firstNx = 0f
        private var firstNy = 0f
        private var hasPrev = false

        fun reset() {
            hasPrev = false
        }

        /**
         * 发射中心线点 (px, py) 处法线 (mx, my) 的点对,与上一个点对组成带段。
         * 段类型由 emitSegment 判定:段两端同一中心线点 = join 弧段(法线旋转,
         * 用段中心剖分,避免中心线点 cA=cB=顶点 的退化三角形);不同 = 边带
         * (直线段,用中心线点剖分,段边界场连续无分段线)。
         */
        fun emit(px: Float, py: Float, mx: Float, my: Float) = emitScaled(px, py, mx, my, 1f)

        /**
         * 带法线缩放的截面发射(miter join 专用):点对距离中心线 ±h·[scale]
         * (miter 尖点位于真实轮廓上,coverage 仍取 0,中心 +h 不变)。
         */
        fun emitScaled(px: Float, py: Float, mx: Float, my: Float, scale: Float) {
            val hs = h * scale
            val ox = px + mx * hs
            val oy = py + my * hs
            val ix = px - mx * hs
            val iy = py - my * hs
            if (!hasPrev) {
                firstOx = ox; firstOy = oy; firstIx = ix; firstIy = iy
                firstCx = px; firstCy = py
                firstNx = mx; firstNy = my
            } else {
                emitSegment(
                    prevOx, prevOy, prevIx, prevIy, ox, oy, ix, iy,
                    prevNx, prevNy, mx, my,
                    prevCx, prevCy, px, py,
                )
            }
            prevOx = ox; prevOy = oy; prevIx = ix; prevIy = iy
            prevCx = px; prevCy = py
            prevNx = mx; prevNy = my
            hasPrev = true
        }

        /** 闭合:最后一个点对 → 第一个点对之间的带段(直线段,中心线点剖分) */
        fun close() {
            if (hasPrev) {
                emitSegment(
                    prevOx, prevOy, prevIx, prevIy, firstOx, firstOy, firstIx, firstIy,
                    prevNx, prevNy, firstNx, firstNy,
                    prevCx, prevCy, firstCx, firstCy,
                )
            }
        }

        /**
         * 带段 (po→o 外轮廓, pi→i 内轮廓) 剖分:
         * - 直线段([isArc]=false):**中心线点(cA/cB)剖分** —— 四条轮廓边全 0、
         *   中心线点 cA/cB 为 +h;相邻段共享中心线点,coverage 场在段边界
         *   连续,**不产生分段线**(段中心剖分的 M 不同 → 段边界斜率不一致 →
         *   每段一条可见线,仅直线段不得使用);
         * - join 弧段([isArc]=true):**段中心 M=(po+o+i+pi)/4 剖分** —— 弧段
         *   对称时 M = 顶点 V,不退化、覆盖完整(四边形绕 M 的 4 三角形),
         *   且相邻弧段共享 M=V → 无分段线、无缝隙(中心线点剖分在弧段
         *   cA=cB=V 会退化成零面积三角形 → 带中央缝隙 → 转角毛刺)。
         *
         * fringe(修复:弯曲描边顶点毛刺):**fringe 与轮廓同构** —— 每个截面
         * 携带自己的法线(nA/nB),fringe 四边形两端各用各的法线外推,
         * 相邻段共享边界边。旧实现整段只用段末法线外推:弯曲路径顶点处
         * 凸侧 fringe 间留楔形裂缝(缺口状毛刺)、凹侧 fringe 重叠双混合
         * (凸起状毛刺);逐截面法线后裂缝/重叠在结构上消失(与填充外扩带
         * 的连续 miter 轮廓同构)。
         */
        private fun emitSegment(
            poX: Float, poY: Float, piX: Float, piY: Float,
            oX: Float, oY: Float, iX: Float, iY: Float,
            nAX: Float, nAY: Float, nBX: Float, nBY: Float,
            cAX: Float, cAY: Float, cBX: Float, cBY: Float,
        ) {
            val hc = h * sink.aaScale // 中心 coverage = 半宽(屏幕像素)
            // 段两端同一中心线点(法线旋转,join 弧段)→ 段中心剖分;
            // 否则为边带(直线段)→ 中心线点剖分。
            val isArc = dist(cAX, cAY, cBX, cBY) < 1e-6f
            if (isArc) {
                // 弧段:段中心 M(对称时 = 顶点 V)
                val mcx = (poX + oX + iX + piX) / 4f
                val mcy = (poY + oY + iY + piY) / 4f
                sink.triangle(poX, poY, 0f, oX, oY, 0f, mcx, mcy, hc)
                sink.triangle(oX, oY, 0f, iX, iY, 0f, mcx, mcy, hc)
                sink.triangle(iX, iY, 0f, piX, piY, 0f, mcx, mcy, hc)
                sink.triangle(piX, piY, 0f, poX, poY, 0f, mcx, mcy, hc)
            } else {
                // 直线段(边带):中心线点剖分(段边界场连续,无分段线)
                sink.triangle(poX, poY, 0f, oX, oY, 0f, cBX, cBY, hc)
                sink.triangle(poX, poY, 0f, cBX, cBY, hc, cAX, cAY, hc)
                sink.triangle(piX, piY, 0f, cAX, cAY, hc, cBX, cBY, hc)
                sink.triangle(piX, piY, 0f, cBX, cBY, hc, iX, iY, 0f)
            }
            // 双侧 fringe(0 → -1.5):逐截面法线外推,相邻段共享边界边。
            // 顶点收拢侧的重叠由 vertexJoin 的亚像素 miter 截面消除(见
            // emitBevel),这里两侧 fringe 恒发射。
            sink.triangle(poX, poY, 0f, oX, oY, 0f, oX + nBX * w, oY + nBY * w, -1.5f)
            sink.triangle(poX, poY, 0f, oX + nBX * w, oY + nBY * w, -1.5f, poX + nAX * w, poY + nAY * w, -1.5f)
            // 方向与符号必须与 coverage 语义一致(带外为负),否则内圈实心/颜色加深
            sink.triangle(iX, iY, 0f, piX, piY, 0f, piX - nAX * w, piY - nAY * w, -1.5f)
            sink.triangle(iX, iY, 0f, piX - nAX * w, piY - nAY * w, -1.5f, iX - nBX * w, iY - nBY * w, -1.5f)
        }
    }

    /**
     * 轮廓带展开入口(任意折线):先按 [pathEffect] 做几何预处理
     * (corner 倒圆 / dash 弧长切分),再交给 [strokeBand] 带展开。
     * [pts] 为 [x, y] 平铺中心线顶点。
     */
    private fun strokeRing(
        ptsIn: FloatArray, closed: Boolean, strokeWidth: Float,
        cap: StrokeCap, join: StrokeJoin, miterLimit: Float, pathEffect: PathEffect?,
        sink: Sink,
    ) {
        // 闭合路径首尾重复点(flatten Close 补齐)去掉末点,避免零长度闭合边
        var pts = ptsIn
        if (closed && ptsIn.size >= 6 &&
            dist(ptsIn[0], ptsIn[1], ptsIn[ptsIn.size - 2], ptsIn[ptsIn.size - 1]) < 1e-4f
        ) {
            pts = ptsIn.copyOf(ptsIn.size - 2)
        }
        if (pts.size / 2 < 2) return
        if (pathEffect is CornerPathEffect && pathEffect.radius > 0f) {
            pts = roundCorners(pts, closed, pathEffect.radius, sink.aaScale)
        }
        if (pathEffect is DashPathEffect && pathEffect.total > 0f) {
            // dash(Skia 语义):沿折线弧长按 on/off 区间切段,每段 on 子折线
            // 独立带展开(端帽用 Paint cap);off 区间不画。
            for (piece in dashSplit(pts, closed, pathEffect.normalized, pathEffect.phase)) {
                strokeBand(piece, false, strokeWidth, cap, join, miterLimit, sink)
            }
            return
        }
        strokeBand(pts, closed, strokeWidth, cap, join, miterLimit, sink)
    }

    /**
     * 轮廓带展开(任意折线,[join] 拐角样式,闭合路径无端帽,开放路径按 [cap] 端帽)。
     * [pts] 为 [x, y] 平铺中心线顶点。
     */
    private fun strokeBand(
        pts: FloatArray, closed: Boolean, strokeWidth: Float,
        cap: StrokeCap, join: StrokeJoin, miterLimit: Float,
        sink: Sink,
    ) {
        val n = pts.size / 2
        if (n < 2) return
        val h = effectiveWidth(strokeWidth) / 2f
        if (h <= 0f) return
        val w = 1.5f / sink.aaScale
        val aaScale = sink.aaScale

        // 单位边法线(数学左法线)
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = pts[j * 2] - pts[i * 2]
            val dy = pts[j * 2 + 1] - pts[i * 2 + 1]
            val len = sqrt(dx * dx + dy * dy)
            if (len > 1e-6f) {
                nx[i] = -dy / len
                ny[i] = dx / len
            }
        }

        val em = StrokeEmitter(sink, h, w)
        em.reset()

        /** 顶点 join(Skia 语义):Round 法线旋转弧 / Bevel 斜切 / Miter 角平分线延长(超限退化 Bevel) */
        fun vertexJoin(px: Float, py: Float, inX: Float, inY: Float, outX: Float, outY: Float) {
            val cross = inX * outY - inY * outX
            val dot = inX * outX + inY * outY
            var angle = atan2(cross.toDouble(), dot.toDouble())
            if (angle > PI) angle -= 2.0 * PI
            if (angle < -PI) angle += 2.0 * PI
            if (abs(angle) < 1e-4f) return // 近共线:不发射(直边段两端法线一致即可)
            /**
             * 斜切 join:in/out 两个截面,其间带段以 M=顶点 剖分即平面斜切。
             *
             * 偏转角很小(圆/弧密采样顶点)时斜切与 miter 的视觉差为亚像素
             * (尖点凸出量 h·(1/cos(θ/2)−1)),但双截面在收拢侧结构性重叠:
             * 相邻两边带段的 fringe 四边形都探入截面间的楔形区,再叠 join
             * 弧段 fringe → 三层 SrcOver 叠加 → 顶点深色珠子(圆描边内圈
             * 毛刺);扇出侧 fringe 边界自然收敛相接,无此问题。
             * 故尖点凸出量 < 0.25 屏幕像素时改用单 miter 截面:两侧边带段
             * 共享同一截面,轮廓边与 fringe 边界边严格相接,无重叠无缺口。
             */
            fun emitBevel(px: Float, py: Float, inX: Float, inY: Float, outX: Float, outY: Float) {
                var mx = inX + outX
                var my = inY + outY
                val ml = sqrt(mx * mx + my * my)
                if (ml > 1e-6f) {
                    mx /= ml
                    my /= ml
                    val cosHalf = mx * inX + my * inY
                    if (cosHalf > 1e-3f && h * aaScale * (1f / cosHalf - 1f) < 0.25f) {
                        em.emitScaled(px, py, mx, my, 1f / cosHalf)
                        return
                    }
                }
                em.emit(px, py, inX, inY)
                em.emit(px, py, outX, outY)
            }

            when (join) {
                StrokeJoin.Bevel -> emitBevel(px, py, inX, inY, outX, outY)

                StrokeJoin.Miter -> {
                    // 角平分线方向,长度 h/cos(θ/2);Skia 截断判据:
                    // miterLimit·sin(θ/2) < 1 → 退化 Bevel(默认 limit 4 ↔ θ < ~29° 截断)
                    var mx = inX + outX
                    var my = inY + outY
                    val ml = sqrt(mx * mx + my * my)
                    val sinHalf = sin(abs(angle) / 2.0).toFloat()
                    if (ml > 1e-6f && miterLimit > 0f && miterLimit * sinHalf >= 1f) {
                        mx /= ml
                        my /= ml
                        val cosHalf = mx * inX + my * inY
                        if (cosHalf > 1e-3f) {
                            em.emitScaled(px, py, mx, my, 1f / cosHalf)
                            return
                        }
                    }
                    emitBevel(px, py, inX, inY, outX, outY)
                }

                StrokeJoin.Round -> {
                    val k = max(1, ceil(abs(angle) * h * aaScale / 2.0).toInt())
                    // 必须发射 in 法线起点(t=0):k=1(如 2px 矩形的 90° 角)时
                    // 若只发 out 法线单点,相邻直边段两端带法线跳 90° → 带为
                    // 斜平行四边形,每条边只盖一半;先发 in 起点后直边段平直完整。
                    em.emit(px, py, inX, inY)
                    for (i in 1..k) {
                        val t = i.toFloat() / k
                        val a = angle * t
                        val ca = cos(a).toFloat()
                        val sa = sin(a).toFloat()
                        em.emit(px, py, inX * ca - inY * sa, inX * sa + inY * ca)
                    }
                }
            }
        }

        if (closed) {
            for (i in 0 until n) {
                vertexJoin(pts[i * 2], pts[i * 2 + 1], nx[(i - 1 + n) % n], ny[(i - 1 + n) % n], nx[i], ny[i])
            }
            em.close()
        } else {
            // 首端:端点对
            em.emit(pts[0], pts[1], nx[0], ny[0])
            for (i in 1 until n - 1) {
                vertexJoin(pts[i * 2], pts[i * 2 + 1], nx[i - 1], ny[i - 1], nx[i], ny[i])
            }
            // 末端点对
            em.emit(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], nx[n - 2], ny[n - 2])
            // 端帽
            if (cap == StrokeCap.Round) {
                // 首端半圆:从 +n0 经带外方向(-边方向)到 -n0
                roundCapEnd(pts[0], pts[1], nx[0], ny[0], +1, h, sink)
                roundCapEnd(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], nx[n - 2], ny[n - 2], -1, h, sink)
            } else {
                endCapButt(pts[0], pts[1], nx[0], ny[0], +1, h, w, sink)
                endCapButt(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], nx[n - 2], ny[n - 2], -1, h, w, sink)
            }
        }
    }

    /**
     * dash 弧长切分(Skia SkDashPathEffect 语义):沿折线把 on/off 区间交替铺开,
     * 返回所有 on 子折线(≥ 2 点)。
     *
     * - [intervals] 恒偶数组(构造时已归一化),[phase] 先 mod 区间总和;
     * - 闭合折线按「首尾相接的开链」处理(接缝处 on 区间可能被切成两截,
     *   端帽重合,视觉接近无缝);
     * - 零长度区间直接跳过(不画 Skia 圆帽点)。
     */
    private fun dashSplit(pts: FloatArray, closed: Boolean, intervals: FloatArray, phase: Float): List<FloatArray> {
        val n = pts.size / 2
        if (n < 2) return emptyList()
        val total = intervals.sum()
        if (total <= 0f) return listOf(pts)

        val segCount = if (closed) n else n - 1
        fun px(i: Int) = pts[(i % n) * 2]
        fun py(i: Int) = pts[(i % n) * 2 + 1]

        // phase 归一化到 [0, total),定位起始区间
        var offset = phase % total
        if (offset < 0f) offset += total
        var idx = 0
        var left = intervals[0]
        while (offset >= left) {
            offset -= left
            idx = (idx + 1) % intervals.size
            left = intervals[idx]
        }
        var on = idx % 2 == 0

        val pieces = ArrayList<FloatArray>()
        var cur = ArrayList<Float>()

        fun closePiece() {
            if (on && cur.size >= 4) pieces.add(cur.toFloatArray())
            cur = ArrayList()
        }

        fun nextInterval() {
            idx = (idx + 1) % intervals.size
            left = intervals[idx]
            on = !on
        }

        for (i in 0 until segCount) {
            val ax = px(i)
            val ay = py(i)
            val bx = px(i + 1)
            val by = py(i + 1)
            val segLen = dist(ax, ay, bx, by)
            if (segLen < 1e-6f) continue
            val ux = (bx - ax) / segLen
            val uy = (by - ay) / segLen
            var consumed = 0f
            while (segLen - consumed > 1e-6f) {
                val step = min(left, segLen - consumed)
                if (on && cur.isEmpty()) {
                    cur.add(ax + ux * consumed)
                    cur.add(ay + uy * consumed)
                }
                consumed += step
                if (on) {
                    cur.add(ax + ux * consumed)
                    cur.add(ay + uy * consumed)
                }
                left -= step
                if (left <= 1e-6f) {
                    closePiece()
                    nextInterval()
                    // 跳过零长度区间(total > 0 保证有限步内结束)
                    var guard = 0
                    while (left <= 1e-6f && guard++ <= intervals.size) nextInterval()
                }
            }
        }
        closePiece()
        return pieces
    }

    /**
     * corner 倒圆(Skia SkCornerPathEffect 语义):折线每个尖角替换为半径
     * [radius] 的圆弧。切点距顶点 d = r/tan(α/2)(α = 拐角偏转角),
     * d 钳制到相邻边长的一半(半径自动缩小,切点不越界)。
     * 闭合折线首尾顶点同样倒圆,返回点列仍按闭合使用。
     */
    private fun roundCorners(ptsIn: FloatArray, closed: Boolean, radius: Float, aaScale: Float): FloatArray {
        val n = ptsIn.size / 2
        if (n < 3 || radius <= 0f) return ptsIn
        fun px(i: Int) = ptsIn[((i % n) + n) % n * 2]
        fun py(i: Int) = ptsIn[((i % n) + n) % n * 2 + 1]

        val out = ArrayList<Float>(ptsIn.size * 2)

        fun append(x: Float, y: Float) {
            val m = out.size / 2
            if (m > 0 && dist(out[(m - 1) * 2], out[(m - 1) * 2 + 1], x, y) < 1e-4f) return
            out.add(x)
            out.add(y)
        }

        fun roundedCorner(i: Int) {
            val vx = px(i)
            val vy = py(i)
            val prevLen = dist(px(i - 1), py(i - 1), vx, vy)
            val nextLen = dist(vx, vy, px(i + 1), py(i + 1))
            if (prevLen < 1e-6f || nextLen < 1e-6f) {
                append(vx, vy)
                return
            }
            val ux = (vx - px(i - 1)) / prevLen
            val uy = (vy - py(i - 1)) / prevLen
            val wx = (px(i + 1) - vx) / nextLen
            val wy = (py(i + 1) - vy) / nextLen
            // α = 偏转角(0 = 直线);cosα = (-u)·w
            val cosA = (-ux * wx - uy * wy).coerceIn(-1f, 1f)
            val alpha = acos(cosA)
            if (alpha < 1e-3f) {
                append(vx, vy)
                return
            }
            val tanHalf = tan(alpha / 2.0).toFloat()
            val sinHalf = sin(alpha / 2.0).toFloat()
            val d = min(radius / tanHalf, 0.5f * min(prevLen, nextLen))
            if (d < 1e-4f) {
                append(vx, vy)
                return
            }
            val rEff = d * tanHalf
            val t1x = vx - ux * d
            val t1y = vy - uy * d
            val t2x = vx + wx * d
            val t2y = vy + wy * d
            // 圆心:角平分线方向,距顶点 rEff/sin(α/2)
            var bx = -ux + wx
            var by = -uy + wy
            val bl = sqrt(bx * bx + by * by)
            if (bl < 1e-6f || sinHalf < 1e-6f) {
                append(vx, vy)
                return
            }
            bx /= bl
            by /= bl
            val ccx = vx + bx * (rEff / sinHalf)
            val ccy = vy + by * (rEff / sinHalf)
            val a0 = atan2((t1y - ccy).toDouble(), (t1x - ccx).toDouble())
            var sweep = atan2((t2y - ccy).toDouble(), (t2x - ccx).toDouble()) - a0
            while (sweep > PI) sweep -= 2.0 * PI
            while (sweep < -PI) sweep += 2.0 * PI
            val k = max(2, ceil(abs(sweep) * rEff * aaScale / 2.0).toInt())
            append(t1x, t1y)
            for (s in 1 until k) {
                val a = a0 + sweep * s / k
                append(ccx + rEff * cos(a).toFloat(), ccy + rEff * sin(a).toFloat())
            }
            append(t2x, t2y)
        }

        if (closed) {
            for (i in 0 until n) roundedCorner(i)
        } else {
            append(px(0), py(0))
            for (i in 1 until n - 1) roundedCorner(i)
            append(px(n - 1), py(n - 1))
        }
        return if (out.size >= 4) out.toFloatArray() else ptsIn
    }

    /**
     * round 端帽:以 (px, py) 为圆心、半径 [h] 的半圆,从 +n 经带外方向到 -n。
     * [sign] = +1 首端(带外方向 = -边方向),-1 末端(+边方向)。
     */
    private fun roundCapEnd(px: Float, py: Float, nx: Float, ny: Float, sign: Int, h: Float, sink: Sink) {
        val w = 1.5f / sink.aaScale
        val aaScale = sink.aaScale
        // 带外方向 = sign × (-边方向);边方向 = (ny, -nx)(左法线反解)
        val dirX = -sign * ny
        val dirY = sign * nx
        val k = max(4, ceil(PI * h * aaScale / 2.0).toInt())
        val base = atan2(ny, nx)
        // 旋转方向:经过 dir 侧
        val ccw = (dirX * (-ny) + dirY * nx) > 0f
        var prevX = px + nx * h
        var prevY = py + ny * h
        for (i in 1..k) {
            val t = if (ccw) base + PI * i / k else base - PI * i / k
            val ax = px + h * cos(t).toFloat()
            val ay = py + h * sin(t).toFloat()
            // 扇形(圆心 +h,弧 0):coverage = 带中心线距离场
            sink.triangle(px, py, h * aaScale, prevX, prevY, 0f, ax, ay, 0f)
            // 弧段径向 fringe(0 → -1.5)
            val rnx = (ax - px) / h
            val rny = (ay - py) / h
            sink.triangle(prevX, prevY, 0f, ax, ay, 0f, ax + rnx * w, ay + rny * w, -1.5f)
            sink.triangle(prevX, prevY, 0f, ax + rnx * w, ay + rny * w, -1.5f, prevX + rnx * w, prevY + rny * w, -1.5f)
            prevX = ax
            prevY = ay
        }
    }

    /**
     * butt 端帽:端边(端点处 ±n·h 连线)coverage 0,向带内 1.5px 梯形 +1.5;
     * 端边外侧 fringe + 两端角帽(与带长边 fringe 衔接)。
     * [sign] = +1 首端,-1 末端(带外方向)。
     */
    /**
     * butt 端帽:端边(端点处 ±n·h 连线)本身就是带轮廓的一部分(带四边形在
     * 端边 coverage = 0,向带内由带四边形自然渐显),因此只补:
     * - 端边外侧 fringe(0 → -1.5):向带外 1.5px 渐隐;
     * - 两端角帽:端边 fringe 与带长边 fringe 之间的外部楔形。
     * (不得再加「端边向带内 1.5px 的梯形」—— 端边内侧已被带四边形覆盖,
     * 梯形与之重叠会造成 alpha 叠加 → 端部黑色。)
     * [sign] = +1 首端,-1 末端(带外方向)。
     */
    private fun endCapButt(px: Float, py: Float, nx: Float, ny: Float, sign: Int, h: Float, w: Float, sink: Sink) {
        val dirX = -sign * ny
        val dirY = sign * nx
        val ox = px + nx * h
        val oy = py + ny * h
        val ix = px - nx * h
        val iy = py - ny * h
        // 端边外侧 fringe(0 → -1.5)
        sink.triangle(ox, oy, 0f, ix, iy, 0f, ix + dirX * w, iy + dirY * w, -1.5f)
        sink.triangle(ox, oy, 0f, ix + dirX * w, iy + dirY * w, -1.5f, ox + dirX * w, oy + dirY * w, -1.5f)
        // 角帽:端边端点与带长边 fringe 之间的外部楔形(0 → -1.5)
        cornerCap(ox, oy, nx, ny, dirX, dirY, w, sink)
        cornerCap(ix, iy, -nx, -ny, dirX, dirY, w, sink)
    }

    /** 凸角角帽:两个外侧方向 [n1]/[n2] 之间的楔形(coverage 顶点 0 → fringe -1) */
    private fun cornerCap(px: Float, py: Float, n1x: Float, n1y: Float, n2x: Float, n2y: Float, w: Float, sink: Sink) {
        if (w <= 0f) return
        val ax = px + n1x * w
        val ay = py + n1y * w
        val bx = px + n2x * w
        val by = py + n2y * w
        if (dist(ax, ay, bx, by) < 1e-4f) {
            // 同向(直线顶点):单侧楔形
            sink.triangle(px, py, 0f, ax, ay, -1.5f, px - n1y * w, py + n1x * w, -1.5f)
        } else {
            sink.triangle(px, py, 0f, ax, ay, -1f, bx, by, -1f)
        }
    }

    // ── 入口图元 ──────────────────────────────────────────────────────────

    /** 圆。细分按弦高自适应(每段弦高 ≤ 0.25 屏幕像素) */
    fun circle(
        cx: Float, cy: Float, radius: Float, fill: Boolean, strokeWidth: Float, sink: Sink,
        join: StrokeJoin = StrokeJoin.Miter, miterLimit: Float = 4f, pathEffect: PathEffect? = null,
    ) {
        val r = radius.coerceAtLeast(0f)
        if (r <= 0f) return
        val segments = if (fill) arcSegments(r * sink.aaScale) else strokeSegments(r * sink.aaScale)
        val pts = ellipsePoints(cx, cy, r, r, 0.0, 2.0 * PI, segments)
        if (fill) {
            fillPolygon(pts, sink)
        } else {
            strokeRing(pts, true, strokeWidth, StrokeCap.Butt, join, miterLimit, pathEffect, sink)
        }
    }

    /** 椭圆(轴对齐)。 */
    fun oval(
        left: Float, top: Float, right: Float, bottom: Float, fill: Boolean, strokeWidth: Float, sink: Sink,
        join: StrokeJoin = StrokeJoin.Miter, miterLimit: Float = 4f, pathEffect: PathEffect? = null,
    ) {
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        if (rx <= 0f || ry <= 0f) return
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val segments = if (fill) arcSegments(max(rx, ry) * sink.aaScale) else strokeSegments(max(rx, ry) * sink.aaScale)
        val pts = ellipsePoints(cx, cy, rx, ry, 0.0, 2.0 * PI, segments)
        if (fill) {
            fillPolygon(pts, sink)
        } else {
            strokeRing(pts, true, strokeWidth, StrokeCap.Butt, join, miterLimit, pathEffect, sink)
        }
    }

    /**
     * 椭圆弧。startAngle/sweepAngle 单位为度,0° = 3 点钟,正 sweep 顺时针(y-down)。
     * fill + useCenter → 扇形;fill + !useCenter → 弓形(弦闭合);
     * stroke → 弧带(两端 butt cap)。
     */
    fun arc(
        left: Float, top: Float, right: Float, bottom: Float,
        startAngleDeg: Float, sweepAngleDeg: Float, useCenter: Boolean,
        fill: Boolean, strokeWidth: Float, sink: Sink,
        join: StrokeJoin = StrokeJoin.Miter, miterLimit: Float = 4f, pathEffect: PathEffect? = null,
    ) {
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        if (rx <= 0f || ry <= 0f || sweepAngleDeg == 0f) return
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val start = startAngleDeg * PI / 180.0
        val sweep = sweepAngleDeg * PI / 180.0
        val radiusPx = max(rx, ry) * sink.aaScale
        val segments = max(4, ceil(abs(sweep) / (2.0 * PI) * (if (fill) arcSegments(radiusPx) else strokeSegments(radiusPx))).toInt())
        val pts = ellipsePoints(cx, cy, rx, ry, start, sweep, segments)
        if (fill) {
            if (useCenter) {
                val n = segments + 2
                val full = FloatArray(n * 2)
                for (i in 0 until (segments + 1) * 2) full[i] = pts[i]
                full[(segments + 1) * 2] = cx
                full[(segments + 1) * 2 + 1] = cy
                fillPolygon(full, sink)
            } else {
                fillPolygon(pts, sink)
            }
        } else {
            strokeRing(pts, false, strokeWidth, StrokeCap.Butt, join, miterLimit, pathEffect, sink)
        }
    }

    /**
     * 矩形网格细分:把矩形切成 cols×rows 个四边形(每个 2 三角形)。
     *
     * 用于非线性渐变(径向/扫描等)等需要内部采样点的填充 —— 只用 4 角顶点时,
     * 顶点色线性插值无法还原内部渐变:若角点全部超出渐变半径被 clamp 到
     * 边缘色,整块会退化为纯色;网格太粗则会出现肉眼可见的三角形棱面
     * (径向/扫描看起来「乱七八糟」)。
     *
     * 自适应细分:小矩形按 [minCell] 细分保证平滑,大矩形按总单元数
     * [maxCells] 自动放大单元避免三角形数量失控;单边行列再封顶 [maxAxis]。
     */
    fun rectGrid(
        left: Float, top: Float, right: Float, bottom: Float,
        sink: Sink,
    ) {
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return

        val minCell = 4f
        val maxCells = 4096
        val maxAxis = 128

        var cols = max(1, ceil(w / minCell).toInt())
        var rows = max(1, ceil(h / minCell).toInt())
        val cells = cols.toLong() * rows
        if (cells > maxCells.toLong()) {
            // 等比放大单元边长,使总单元数 ≈ maxCells
            val factor = sqrt(cells.toDouble() / maxCells)
            cols = max(1, ceil(cols / factor).toInt())
            rows = max(1, ceil(rows / factor).toInt())
        }
        cols = cols.coerceAtMost(maxAxis)
        rows = rows.coerceAtMost(maxAxis)

        val stepX = w / cols
        val stepY = h / rows
        for (r in 0 until rows) {
            val y0 = top + stepY * r
            val y1 = if (r == rows - 1) bottom else y0 + stepY
            for (c in 0 until cols) {
                val x0 = left + stepX * c
                val x1 = if (c == cols - 1) right else x0 + stepX
                sink.quad(x0, y0, x1, y0, x1, y1, x0, y1)
            }
        }
    }

    /**
     * 圆角矩形(半径钳制到半宽/半高)。fill → 三环填充;
     * stroke → 轮廓带(圆角处自然由细分提供圆 join,带 AA)。
     */
    fun roundRect(
        left: Float, top: Float, right: Float, bottom: Float,
        radiusX: Float, radiusY: Float,
        fill: Boolean, strokeWidth: Float, sink: Sink,
        join: StrokeJoin = StrokeJoin.Miter, miterLimit: Float = 4f, pathEffect: PathEffect? = null,
    ) {
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return
        val rx = radiusX.coerceIn(0f, w / 2f)
        val ry = radiusY.coerceIn(0f, h / 2f)
        if (rx <= 0f || ry <= 0f) {
            // 退化为矩形
            if (fill) {
                // 用网格而不是单 quad:让径向/扫描等非线性渐变有内部采样顶点
                rectGrid(left, top, right, bottom, sink)
            } else {
                strokeRing(floatArrayOf(left, top, right, top, right, bottom, left, bottom), true, strokeWidth, StrokeCap.Butt, join, miterLimit, pathEffect, sink)
            }
            return
        }
        // 圆角细分:90° 弧按弦高
        val cornerSegments = max(2, ceil(arcSegments(max(rx, ry) * sink.aaScale) / 4f).toInt())
        val pts = ArrayList<Float>((cornerSegments * 4 + 4) * 2)

        fun corner(cx: Float, cy: Float, startAngle: Double) {
            for (i in 1..cornerSegments) {
                val a = startAngle + PI / 2.0 * i / cornerSegments
                pts.add(cx + rx * cos(a).toFloat())
                pts.add(cy + ry * sin(a).toFloat())
            }
        }

        pts.add(left + rx); pts.add(top)
        pts.add(right - rx); pts.add(top)
        corner(right - rx, top + ry, -PI / 2.0)
        pts.add(right); pts.add(bottom - ry)
        corner(right - rx, bottom - ry, 0.0)
        pts.add(left + rx); pts.add(bottom)
        corner(left + rx, bottom - ry, PI / 2.0)
        pts.add(left); pts.add(top + ry)
        corner(left + rx, top + ry, PI)

        val outline = pts.toFloatArray()
        if (fill) {
            fillPolygon(outline, sink)
        } else {
            strokeRing(outline, true, strokeWidth, StrokeCap.Butt, join, miterLimit, pathEffect, sink)
        }
    }

    /**
     * 线段:带展开(butt → 方形端帽;round → 半圆端帽),全部边经带 AA。
     * 零长度线段 → 按 cap 画一个点。
     */
    fun line(
        x1: Float, y1: Float, x2: Float, y2: Float, strokeWidth: Float, cap: StrokeCap, sink: Sink,
        join: StrokeJoin = StrokeJoin.Miter, miterLimit: Float = 4f, pathEffect: PathEffect? = null,
    ) {
        val w = effectiveWidth(strokeWidth)
        val h = w / 2f
        if (dist(x1, y1, x2, y2) <= 1e-6f) {
            if (cap == StrokeCap.Round) {
                circle(x1, y1, h, true, 0f, sink)
            } else {
                squarePoint(x1, y1, h, sink)
            }
            return
        }
        strokeRing(floatArrayOf(x1, y1, x2, y2), false, w, cap, join, miterLimit, pathEffect, sink)
    }

    // ── Path ───────────────────────────────────────────────────────────────

    /**
     * Path 绘制。segments 为扁平化段(Move/Line/Quadratic/Cubic/Close)。
     * fill → 每子路径三环填充(凸/凹/自交统一);
     * stroke → 每子路径轮廓带(开放子路径按 [cap] 端帽,闭合子路径圆 join)。
     */
    fun path(
        segments: List<MinecraftPath.PathSegmentData>,
        fill: Boolean,
        strokeWidth: Float,
        cap: StrokeCap,
        sink: Sink,
        join: StrokeJoin = StrokeJoin.Miter,
        miterLimit: Float = 4f,
        pathEffect: PathEffect? = null,
    ) {
        val subpaths = flatten(segments, sink.aaScale)
        if (fill) {
            for ((points) in subpaths) {
                if (points.size < 6) continue
                fillPolygon(points, sink)
            }
        } else {
            for ((points, closed) in subpaths) {
                if (points.size < 4) continue
                strokeRing(points, closed, strokeWidth, cap, join, miterLimit, pathEffect, sink)
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
     *   不自动闭合回起点;每段带展开,顶点圆 join,两端按 [cap] 端帽。
     */
    fun points(
        mode: PointMode, points: List<Offset>, strokeWidth: Float, cap: StrokeCap, sink: Sink,
        join: StrokeJoin = StrokeJoin.Miter, miterLimit: Float = 4f, pathEffect: PathEffect? = null,
    ) {
        if (points.isEmpty()) return
        val w = effectiveWidth(strokeWidth)
        when (mode) {
            PointMode.Points -> for (p in points) {
                if (cap == StrokeCap.Round) {
                    circle(p.x, p.y, w / 2f, true, 0f, sink)
                } else {
                    squarePoint(p.x, p.y, w / 2f, sink)
                }
            }

            PointMode.Lines -> {
                var i = 0
                while (i + 1 < points.size) {
                    val a = points[i]
                    val b = points[i + 1]
                    line(a.x, a.y, b.x, b.y, w, cap, sink, join, miterLimit, pathEffect)
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
                    if (pts.size == 2) {
                        if (cap == StrokeCap.Round) {
                            circle(pts[0], pts[1], w / 2f, true, 0f, sink)
                        } else {
                            squarePoint(pts[0], pts[1], w / 2f, sink)
                        }
                    }
                    return
                }
                strokeRing(pts.toFloatArray(), false, w, cap, join, miterLimit, pathEffect, sink)
            }
        }
    }

    /** 方形点(Points mode,非 Round cap):边长为 2h 的方形,经三环 AA */
    private fun squarePoint(x: Float, y: Float, h: Float, sink: Sink) {
        if (h <= 0f) return
        fillPolygon(floatArrayOf(x - h, y - h, x + h, y - h, x + h, y + h, x - h, y + h), sink)
    }

    // ── 内部:段序列 → 子路径 ──────────────────────────────────────────────

    private class SubPath(val points: FloatArray, val closed: Boolean) {
        operator fun component1(): FloatArray = points
        operator fun component2(): Boolean = closed
    }

    /** 段序列 → 子路径点序列(二次/三次贝塞尔按 ~2 屏幕像素弦长自适应细分) */
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
}
