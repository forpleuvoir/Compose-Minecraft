package moe.forpleuvoir.compose_minecraft.platform.render.paint

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShaderData
import androidx.compose.ui.graphics.NativeColorFilter
import androidx.compose.ui.graphics.RadialGradientShaderData
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.SweepGradientShaderData
import androidx.compose.ui.graphics.TileMode
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 渲染端(GPU 回放 / 3D 顶点路径)颜色求值工具(自 `MinecraftRenderContext` 原样搬移)。
 *
 * 承载:渐变采样与顶点色([sampleGradient] / [gradientVertexColors] / [gradientTAt])、
 * 颜色滤镜([applyColorFilter], draw 级)、最终 0xAARRGGBB 求值([Color.toArgb])、
 * 逐顶点 alpha 缩放([scaleAlpha])。函数体逐字保留(可见性 private→internal)。
 *
 * 语义标注:
 * - [lerpColor] 使用 **RGB 直插(含 alpha)**,不采用 HSV 空间插值:
 *   非色相通道的渐变(饱和度 / 明度 / RGB 通道条 / 透明)两端各一个色标即可;
 *   色相渐变(彩虹 / hue sweep)由调用方给多个色标表达(如 0/60/…/360 共 7 个);
 * - 与 CPU 快照路径 `RasterGradientSampler.lerpColorARGB`（同为 RGB 直插）语义一致；
 * - 官方 `androidx.compose.ui.graphics.lerp` 为 Oklab 插值，与本对象不同；
 * - 快照路径另有 `RasterGradientSampler.kt` 的 `Color.toArgbInt`（truncate 取整），与
 *   [Color.toArgb]（roundToInt）实现不同，各自保留原样。
 */
internal object ColorEvaluator {

    fun scaleAlpha(argb: Int, alpha: Float): Int {
        if (alpha >= 1f) return argb
        val a = (((argb ushr 24) and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }
    fun gradientTAt(x: Float, y: Float, shader: Shader): Float {
        return when (shader) {
            is LinearGradientShaderData -> {
                val dx = shader.to.x - shader.from.x
                val dy = shader.to.y - shader.from.y
                val dot = dx * dx + dy * dy
                if (dot > 0f) {
                    ((x - shader.from.x) * dx + (y - shader.from.y) * dy) / dot
                } else 0f
            }

            is RadialGradientShaderData -> {
                val dx = x - shader.center.x
                val dy = y - shader.center.y
                sqrt(dx * dx + dy * dy) / shader.radius.coerceAtLeast(1e-6f)
            }

            is SweepGradientShaderData  -> {
                val dx = x - shader.center.x
                val dy = y - shader.center.y
                var t = kotlin.math.atan2(dy, dx) / (2.0f * kotlin.math.PI.toFloat()) + 0.5f
                if (t < 0f) t += 1f
                t
            }

            else                        -> 0f
        }
    }
    fun sampleGradient(t: Float, shader: Shader, alphaMul: Float): Int {
        return when (shader) {
            is LinearGradientShaderData -> sampleGradient(t, shader.colors, shader.colorStops, shader.tileMode, alphaMul, null)
            is RadialGradientShaderData -> sampleGradient(t, shader.colors, shader.colorStops, shader.tileMode, alphaMul, null)
            is SweepGradientShaderData  -> sampleGradient(t, shader.colors, shader.colorStops, TileMode.Clamp, alphaMul, null)
            else                        -> Color.White.toArgb(alphaMul)
        }
    }
    fun sampleGradient(
        t: Float,
        colors: List<Color>,
        stops: List<Float>?,
        tileMode: TileMode,
        alphaMul: Float,
        colorFilter: NativeColorFilter?,
    ): Int {
        val clampedT = when (tileMode) {
            TileMode.Clamp    -> t.coerceIn(0f, 1f)
            TileMode.Repeated -> {
                val ft = t - floor(t)
                ft.coerceIn(0f, 1f)
            }

            TileMode.Mirror   -> {
                val ft = t - floor(t)
                val mt = (ft * 2f).let { if (it > 1f) 2f - it else it }
                mt.coerceIn(0f, 1f)
            }

            TileMode.Decal    -> {
                if (t < 0f || t > 1f) return 0x00000000
                t
            }

            else              -> t.coerceIn(0f, 1f)
        }
        return when {
            stops == null             -> {
                if (colors.size == 1) return colorToArgb(colors[0], alphaMul, colorFilter)
                val idx = (clampedT * (colors.size - 1)).toInt().coerceIn(0, colors.size - 2)
                val localT = clampedT * (colors.size - 1) - idx
                colorToArgb(lerpColor(colors[idx], colors[idx + 1], localT), alphaMul, colorFilter)
            }

            clampedT <= stops.first() -> colorToArgb(colors.first(), alphaMul, colorFilter)
            clampedT >= stops.last()  -> colorToArgb(colors.last(), alphaMul, colorFilter)
            else                      -> {
                var result = colors.last()
                for (i in 0 until stops.size - 1) {
                    if (clampedT >= stops[i] && clampedT <= stops[i + 1]) {
                        val range = stops[i + 1] - stops[i]
                        val localT = if (range > 0f) (clampedT - stops[i]) / range else 0f
                        result = lerpColor(colors[i], colors[i + 1], localT)
                        break
                    }
                }
                colorToArgb(result, alphaMul, colorFilter)
            }
        }
    }
    fun gradientVertexColors(
        shader: Shader,
        vertices: FloatArray,
        alphaMul: Float,
        colorFilter: NativeColorFilter?,
    ): IntArray {
        val vc = vertices.size / 3
        val colors = IntArray(vc)
        when (shader) {
            is LinearGradientShaderData -> {
                var dx = shader.to.x - shader.from.x
                var dy = shader.to.y - shader.from.y
                var fromX = shader.from.x
                var fromY = shader.from.y
                var minX = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                var i = 0; while (i + 2 < vertices.size) {
                    val vx = vertices[i]
                    val vy = vertices[i + 1]
                    if (vx < minX) minX = vx; if (vx > maxX) maxX = vx
                    if (vy < minY) minY = vy; if (vy > maxY) maxY = vy
                    i += 3
                }
                val boxW = (maxX - minX).coerceAtLeast(1f)
                val boxH = (maxY - minY).coerceAtLeast(1f)
                val scaleThreshold = 4f
                if (dx.isFinite() && kotlin.math.abs(dx) > boxW * scaleThreshold) {
                    dx = boxW; fromX = minX
                }
                if (dy.isFinite() && kotlin.math.abs(dy) > boxH * scaleThreshold) {
                    dy = boxH; fromY = minY
                }
                val dot = dx * dx + dy * dy
                if (dot > 0f) {
                    val invDot = 1f / dot
                    for (i in 0 until vc) {
                        val vx = vertices[i * 3]
                        val vy = vertices[i * 3 + 1]
                        val t = ((vx - fromX) * dx + (vy - fromY) * dy) * invDot
                        colors[i] = sampleGradient(t, shader.colors, shader.colorStops, shader.tileMode, alphaMul, colorFilter)
                    }
                } else {
                    val base = colorToArgb(shader.colors.first(), alphaMul, colorFilter)
                    colors.fill(base)
                }
            }

            is RadialGradientShaderData -> {
                for (i in 0 until vc) {
                    val vx = vertices[i * 3]
                    val vy = vertices[i * 3 + 1]
                    val dx = vx - shader.center.x
                    val dy = vy - shader.center.y
                    val t = sqrt(dx * dx + dy * dy) / shader.radius.coerceAtLeast(1e-6f)
                    colors[i] = sampleGradient(t, shader.colors, shader.colorStops, shader.tileMode, alphaMul, colorFilter)
                }
            }

            is SweepGradientShaderData  -> {
                for (i in 0 until vc) {
                    val vx = vertices[i * 3]
                    val vy = vertices[i * 3 + 1]
                    val dx = vx - shader.center.x
                    val dy = vy - shader.center.y
                    var t = kotlin.math.atan2(dy, dx) / (2.0f * kotlin.math.PI.toFloat()) + 0.5f
                    if (t < 0f) t += 1f
                    colors[i] = sampleGradient(t, shader.colors, shader.colorStops, TileMode.Clamp, alphaMul, colorFilter)
                }
            }

            else                        -> {
                val base = paintColorArgb(shader, alphaMul, colorFilter)
                colors.fill(base)
            }
        }
        return colors
    }
    fun paintColorArgb(
        shader: Shader,
        alphaMul: Float,
        colorFilter: NativeColorFilter?,
    ): Int = applyColorFilter(
        Color.White.toArgb(alphaMul),
        colorFilter,
    )
    fun lerpColor(a: Color, b: Color, t: Float): Color {
        val ct = t.coerceIn(0f, 1f)
        // RGB 直插(与 CPU 光栅化路径 RasterGradientSampler.lerpColorARGB 一致):
        // 只让单一通道线性变化的色标(饱和度条 / 明度条 / RGB 通道条 / 透明渐变)两端各一个即可 ——
        // HSV 插值在这些端点上会丢信息(如黑色反解为 h=0,s=0),把"只变一个通道"插成色相扫过一圈。
        // 色相渐变(彩虹 / hue sweep)由调用方用多个色标表达,如 0/60/…/360 共 7 个。
        return Color(
            red = a.red + (b.red - a.red) * ct,
            green = a.green + (b.green - a.green) * ct,
            blue = a.blue + (b.blue - a.blue) * ct,
            alpha = a.alpha + (b.alpha - a.alpha) * ct,
        )
    }
    fun colorToArgb(color: Color, alphaMul: Float, colorFilter: NativeColorFilter?): Int {
        return applyColorFilter(color.toArgb(alphaMul), colorFilter)
    }
    fun applyColorFilter(argb: Int, filter: NativeColorFilter?): Int {
        if (filter == null) return argb
        val c = filter.color
        if (c != null) {
            // tint(SrcIn 通用):结果 = color 调制,alpha 保留源
            if (filter.colorMatrix == null && filter.add == null) {
                // BlendModeColorFilter:color 与底色按 blendMode 合成(draw 级近似)
                val sa = (c.alpha * 255f).roundToInt()
                if (sa >= 255 && filter.blendMode == BlendMode.SrcIn) {
                    // SrcIn + 不透明 tint → 直接替换为 tint 色
                    return ((argb ushr 24) shl 24) or (c.toArgb(1f) and 0x00FFFFFF)
                }
                val sr = (c.red * 255f).roundToInt()
                val sg = (c.green * 255f).roundToInt()
                val sb = (c.blue * 255f).roundToInt()
                val dr = (argb shr 16) and 0xFF
                val dg = (argb shr 8) and 0xFF
                val db = argb and 0xFF
                val da = (argb ushr 24) and 0xFF
                return when (filter.blendMode) {
                    // SrcOver:出 = src×sa + dst×(1-sa)(draw 级近似,无背景知识)
                    BlendMode.SrcOver  -> {
                        val ia = 255 - sa
                        val r = (sr * sa + dr * ia) / 255
                        val g = (sg * sa + dg * ia) / 255
                        val b = (sb * sa + db * ia) / 255
                        (da shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    // Modulate:出 = src × dst / 255
                    BlendMode.Modulate -> {
                        val r = (sr * dr) / 255
                        val g = (sg * dg) / 255
                        val b = (sb * db) / 255
                        (da shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    // 其他 blendMode 的 draw 级近似:SrcIn 之外回退到 tint 色 + 源 alpha
                    else               -> (da shl 24) or (sr shl 16) or (sg shl 8) or sb
                }
            }
            // LightingColorFilter:out = src × multiply + add × 255
            val add = filter.add
            val a = ((argb ushr 24) and 0xFF)
            val r = ((argb shr 16) and 0xFF)
            val g = ((argb shr 8) and 0xFF)
            val b = (argb and 0xFF)
            fun mix(v: Int, mul: Float, aoff: Float): Int =
                (v * mul + aoff * 255f).roundToInt().coerceIn(0, 255)
            return (mix(a, c.alpha, add?.alpha ?: 0f) shl 24) or
                    (mix(r, c.red, add?.red ?: 0f) shl 16) or
                    (mix(g, c.green, add?.green ?: 0f) shl 8) or
                    mix(b, c.blue, add?.blue ?: 0f)
        }
        val m = filter.colorMatrix
        if (m != null) {
            val a = (argb ushr 24) and 0xFF
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val v = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat())
            val out = IntArray(4)
            for (row in 0 until 4) {
                // 偏移列(m[row,4])本身已是 0..255 刻度(如反相矩阵的 255),不再乘 255
                var acc = m[row, 4]
                var col = 0
                while (col < 4) {
                    acc += m[row, col] * v[col]
                    col++
                }
                out[row] = acc.roundToInt().coerceIn(0, 255)
            }
            return (out[3] shl 24) or (out[0] shl 16) or (out[1] shl 8) or out[2]
        }
        return argb
    }

}

internal fun Color.toArgb(alphaMultiplier: Float): Int {
        val a = (alpha * alphaMultiplier).coerceIn(0f, 1f)
        return ((a * 255f).roundToInt() shl 24) or
                ((red * 255f).roundToInt() shl 16) or
                ((green * 255f).roundToInt() shl 8) or
                (blue * 255f).roundToInt()
    }
