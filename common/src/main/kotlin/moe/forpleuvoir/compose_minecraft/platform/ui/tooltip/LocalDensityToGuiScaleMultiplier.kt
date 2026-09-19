package moe.forpleuvoir.compose_minecraft.platform.ui.tooltip

import androidx.compose.runtime.staticCompositionLocalOf
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.MinecraftTooltipRenderer

/**
 * 密度模式下 tooltip 的「Compose 密度 → guiScale」倍率:
 * 默认 = [MinecraftTooltipRenderer.DENSITY_TO_GUI_SCALE_MULTIPLIER](2f —— 补偿平台默认
 * 字号 18sp 为 MC 文本原生 9 单位 2 倍的差值)。
 *
 * 业务可在子树内经
 * `CompositionLocalProvider(LocalDensityToGuiScaleMultiplier provides …)` 按场景覆盖;
 * 布局期([TooltipPopup] 外框尺寸)与渲染期(pose 缩放)经
 * [MinecraftTooltipRenderer.resolveFinalScale] 取同一值,保证外框与内容同步缩放。
 *
 * guiScale 模式(TooltipPopup 的 guiScaleEnabled = true)下不生效(该模式直接用原版 guiScale)。
 */
val LocalDensityToGuiScaleMultiplier: androidx.compose.runtime.CompositionLocal<Float> =
    staticCompositionLocalOf { MinecraftTooltipRenderer.DENSITY_TO_GUI_SCALE_MULTIPLIER }
