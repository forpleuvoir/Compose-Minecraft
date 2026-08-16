package moe.forpleuvoir.compose_minecraft.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * 阴影光源配置(T.14 方向性投影)。
 *
 * [LocalShadowLight] 为光源方向(归一化向量,屏幕坐标:y 向下,
 * 默认右上角 = (1, -1))。业务方用 [androidx.compose.runtime.CompositionLocalProvider]
 * 覆盖光源;平台提供的 [Modifier.shadow] 在组合期自动读取,阴影向光源
 * 反方向偏移投射,业务方无需手动指定任何光源属性:
 *
 * ```
 * CompositionLocalProvider(LocalShadowLight provides Offset(1f, -1f)) {
 *     Box(Modifier.shadow(4.dp, RoundedCornerShape(6.dp)).background(...))
 * }
 * ```
 */
val LocalShadowLight =
    staticCompositionLocalOf { Offset(1f, -1f) }

/**
 * 阴影修饰符(平台扩展 T.14):在 [shadowElevation] 高度上按 [shape] 投射阴影,
 * 光源方向自动取自 [LocalShadowLight](组合期读取,业务 provider 生效)。
 *
 * @param elevation 阴影扩散距离(阴影向光源反方向偏移 elevation×0.5)
 * @param shape 阴影形状(图形轮廓,圆角矩形/矩形/Path)
 * @param clip 是否把内容裁剪到 [shape]
 * @param ambientColor 阴影颜色(第一版仅用其 alpha 作强度)
 */
@Composable
fun Modifier.shadow(
    elevation: Dp,
    shape: Shape,
    clip: Boolean = false,
    ambientColor: Color = DefaultShadowColor,
    spotColor: Color = DefaultShadowColor,
): Modifier {
    val light = LocalShadowLight.current
    return graphicsLayer {
        shadowElevation = elevation.toPx()
        this.shape = shape
        this.clip = clip
        this.ambientShadowColor = ambientColor
        this.spotShadowColor = spotColor
        shadowLightDirection = light
    }
}
