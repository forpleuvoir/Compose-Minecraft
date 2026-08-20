package moe.forpleuvoir.compose_minecraft.platform.ui.draw

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.recordCustomDraw
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McTooltipPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.TooltipDrawData
import moe.forpleuvoir.compose_minecraft.platform.ui.tooltip.TooltipLines

/**
 * 在 [DrawScope] 中绘制一个原版视觉的 tooltip(T.39),经 [McTooltipPlugin] 在
 * Compose 1:1 管线渲染 —— **不走 GuiGraphicsExtractor**。
 *
 * [x]/[y] 为**内容区**左上角坐标(不含背景外扩),单位与 [TooltipLines.measure] 一致:
 * - [density] 为 null:使用原版 guiScale;
 * - 非 null:作为 Compose 场景密度,坐标为像素。
 *
 * 作为 popup 内容渲染时传 (0, 0) 即可:弹层位置由 PopupPositionProvider 负责,
 * 本函数只负责在弹层内绘制完整 tooltip 视觉(背景 + 文本 + 图片行)。
 */
fun DrawScope.drawMinecraftTooltip(
    lines: TooltipLines,
    x: Int = 0,
    y: Int = 0,
    density: Float? = null,
) {
    drawIntoCanvas { canvas ->
        canvas.recordCustomDraw(
            McTooltipPlugin.TAG,
            TooltipDrawData(lines, x, y, density),
            null,
            null,
        )
    }
}