package moe.forpleuvoir.compose_minecraft.platform.render.paint

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShaderData
import androidx.compose.ui.graphics.RadialGradientShaderData
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.SweepGradientShaderData
import androidx.compose.ui.graphics.TileMode
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * CPU 快照路径(P1 从 `GraphicsLayerRasterizer` 原样搬移,2025-08)。
 *
 * 承载 CPU 光栅化(toImageBitmap 快照)的渐变采样与顶点色计算。
 *
 * 语义标注(P1,D1,2025-08 用户确认):
 * - [lerpColorARGB] 使用 **RGB 直插** —— **已知能力缺口:不能表达「色相渐变」**(GPU 回放
 *   路径的 `GradientSampler.lerpColor` 用 HSV 可表达)。**已确认保持现状、不立项(2025-08
 *   用户拍板)**,两版本并存、不统一(重构范围声明:行为零变更);
 * - 取整方式(`Color.toArgbInt`,truncate)与 GPU 系 `ColorEvaluator.toArgb`(roundToInt)不同,
 *   各自保留原样。
 *
 * 搬移原则:P1 只做结构搬移,函数体逐字保留;可见性 private→internal(跨文件调用)。
 */
internal object RasterGradientSampler {

    fun gradientVertexColors(
        shader: Shader,
        vertices: FloatArray,
        vertexCount: Int,
        alphaMul: Float,
    ): IntArray {
        val vc = vertices.size / 3
        val colors = IntArray(vc)
        when (shader) {
            is LinearGradientShaderData -> {
                var dx = shader.to.x - shader.from.x
                var dy = shader.to.y - shader.from.y
                var fromX = shader.from.x
                var fromY = shader.from.y
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                var i = 0; while (i + 2 < vertices.size) {
                    val vx = vertices[i]; val vy = vertices[i + 1]
                    if (vx < minX) minX = vx; if (vx > maxX) maxX = vx
                    if (vy < minY) minY = vy; if (vy > maxY) maxY = vy
                    i += 3
                }
                val boxW = (maxX - minX).coerceAtLeast(1f)
                val boxH = (maxY - minY).coerceAtLeast(1f)
                val scaleThreshold = 4f
                if (dx.isFinite() && kotlin.math.abs(dx) > boxW * scaleThreshold) { dx = boxW; fromX = minX }
                if (dy.isFinite() && kotlin.math.abs(dy) > boxH * scaleThreshold) { dy = boxH; fromY = minY }
                val dot = dx * dx + dy * dy
                if (dot > 0f) {
                    val invDot = 1f / dot
                    var vi = 0; var i = 0
                    while (i + 2 < vertices.size) {
                        val vx = vertices[i]; val vy = vertices[i + 1]
                        val t = ((vx - fromX) * dx + (vy - fromY) * dy) * invDot
                        colors[vi] = sampleGradient(t, shader.colors, shader.colorStops, shader.tileMode, alphaMul)
                        vi++; i += 3
                    }
                } else {
                    colors.fill(colors.firstOrNull() ?: 0)
                }
            }
            is RadialGradientShaderData -> {
                var vi = 0
                var i = 0
                while (i + 2 < vertices.size) {
                    val vx = vertices[i]
                    val vy = vertices[i + 1]
                    val dx = vx - shader.center.x
                    val dy = vy - shader.center.y
                    val t = sqrt(dx * dx + dy * dy) / shader.radius.coerceAtLeast(1e-6f)
                    colors[vi] = sampleGradient(t, shader.colors, shader.colorStops, shader.tileMode, alphaMul)
                    vi++
                    i += 3
                }
            }
            is SweepGradientShaderData -> {
                var vi = 0
                var i = 0
                while (i + 2 < vertices.size) {
                    val vx = vertices[i]
                    val vy = vertices[i + 1]
                    val dx = vx - shader.center.x
                    val dy = vy - shader.center.y
                    var t = kotlin.math.atan2(dy, dx) / (2.0f * kotlin.math.PI.toFloat()) + 0.5f
                    if (t < 0f) t += 1f
                    colors[vi] = sampleGradient(t, shader.colors, shader.colorStops, TileMode.Clamp, alphaMul)
                    vi++
                    i += 3
                }
            }
            else -> {
                val base = Color.White.toArgbInt(alphaMul)
                colors.fill(base)
            }
        }
        return colors
    }

    fun sampleGradient(
        t: Float,
        colors: List<Color>,
        stops: List<Float>?,
        tileMode: TileMode,
        alphaMul: Float,
    ): Int {
        val clampedT = when (tileMode) {
            TileMode.Clamp -> t.coerceIn(0f, 1f)
            TileMode.Repeated -> {
                val ft = t - floor(t)
                ft.coerceIn(0f, 1f)
            }
            TileMode.Mirror -> {
                val ft = t - floor(t)
                val mt = (ft * 2f).let { if (it > 1f) 2f - it else it }
                mt.coerceIn(0f, 1f)
            }
            TileMode.Decal -> {
                if (t < 0f || t > 1f) return 0x00000000
                t
            }
            else -> t.coerceIn(0f, 1f)
        }
        val color = if (stops == null) {
            if (colors.size == 1) return colors[0].toArgbInt(alphaMul)
            val idx = (clampedT * (colors.size - 1)).toInt().coerceIn(0, colors.size - 2)
            val localT = clampedT * (colors.size - 1) - idx
            lerpColorARGB(colors[idx], colors[idx + 1], localT, alphaMul)
        } else {
            if (clampedT <= stops.first()) {
                return colors.first().toArgbInt(alphaMul)
            }
            if (clampedT >= stops.last()) {
                return colors.last().toArgbInt(alphaMul)
            }
            for (i in 0 until stops.size - 1) {
                if (clampedT >= stops[i] && clampedT <= stops[i + 1]) {
                    val range = stops[i + 1] - stops[i]
                    val localT = if (range > 0f) (clampedT - stops[i]) / range else 0f
                    return lerpColorARGB(colors[i], colors[i + 1], localT, alphaMul)
                }
            }
            colors.last().toArgbInt(alphaMul)
        }
        return color
    }

    fun lerpColorARGB(a: Color, b: Color, t: Float, alphaMul: Float): Int {
        val clampedT = t.coerceIn(0f, 1f)
        val r = (a.red + (b.red - a.red) * clampedT) * 255f
        val g = (a.green + (b.green - a.green) * clampedT) * 255f
        val bl = (a.blue + (b.blue - a.blue) * clampedT) * 255f
        val al = (a.alpha + (b.alpha - a.alpha) * clampedT) * alphaMul * 255f
        return ((al.toInt().coerceIn(0, 255) shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            bl.toInt().coerceIn(0, 255))
    }
}

/** [Color] → 0xAARRGGBB(raster 系,truncate 取整;与 GPU 系 `ColorEvaluator.toArgb` 实现不同,保留原样) */
internal fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun Color.toArgbInt(alphaMul: Float): Int {
    val a = (alpha * alphaMul * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}