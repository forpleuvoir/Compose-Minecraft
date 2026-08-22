package moe.forpleuvoir.compose_minecraft.platform.render.plugins

import moe.forpleuvoir.compose_minecraft.platform.render.CustomDrawContext
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.MinecraftTooltipRenderer
import moe.forpleuvoir.compose_minecraft.platform.render.toMatrix3x2f
import moe.forpleuvoir.compose_minecraft.platform.render.toScreenRectangle
import moe.forpleuvoir.compose_minecraft.platform.ui.tooltip.TooltipLines
import net.minecraft.resources.Identifier

/**
 * tooltip 绘制数据:经 [McTooltipPlugin] 在 Compose 1:1 管线渲染原版视觉的 tooltip。
 *
 * [x]/[y] 为**内容区**左上角坐标(不含背景外扩),单位随 [density]:
 * - null:使用原版 guiScale([MinecraftGuiScale.current])作为缩放系数,坐标为 GUI 单位;
 * - 非 null:作为 Compose 场景密度,坐标为像素(1:1)。
 */
data class TooltipDrawData(
    val lines: TooltipLines,
    val x: Int = 0,
    val y: Int = 0,
    /**
     * null = 使用原版 guiScale;非 null = 自定义 Compose 密度值。
     * 见 [MinecraftTooltipRenderer]。
     */
    val density: Float? = null,
    /** 密度模式「密度 → guiScale」倍率,见 [MinecraftTooltipRenderer.resolveFinalScale]。 */
    val densityToGuiScaleMultiplier: Float = MinecraftTooltipRenderer.DENSITY_TO_GUI_SCALE_MULTIPLIER,
)

/**
 * 内置 tooltip 插件(T.39):渲染与原版 `GuiGraphicsExtractor.tooltip` 视觉一致的
 * tooltip —— 背景 sprite(background + frame 九宫格)+ 文本行 + 图片行,
 * **完全不走 GuiGraphicsExtractor / 原版 GuiRenderState**,由 Compose 渲染器
 * 1:1 像素投影 + 像素级裁剪绘制(getTooltipFromItem 同款文本解析见 [TooltipLines])。
 *
 * [density] 参数见 [TooltipDrawData.density]。
 */
object McTooltipPlugin : MinecraftRenderPlugin {

    val TAG: Identifier = Identifier.fromNamespaceAndPath("compose_minecraft", "tooltip")

    override fun onDraw(tag: Identifier, data: Any?, context: CustomDrawContext): Boolean {
        if (tag != TAG) return false
        val td = data as? TooltipDrawData ?: return false
        if (td.lines.lines.isEmpty()) return false
        val size = td.lines.measure()
        if (size.width <= 0 && size.height <= 0) return false
        val renderer = MinecraftTooltipRenderer(
            sink = context.sink,
            density = td.density,
            scissor = context.scissor?.toScreenRectangle(),
            basePose = context.matrix.toMatrix3x2f(),
            densityToGuiScaleMultiplier = td.densityToGuiScaleMultiplier,
        )
        renderer.renderTooltip(
            lines = td.lines.lines,
            font = td.lines.font,
            x = td.x,
            y = td.y,
            w = size.width,
            h = size.height,
            style = td.lines.style,
        )
        return true
    }
}