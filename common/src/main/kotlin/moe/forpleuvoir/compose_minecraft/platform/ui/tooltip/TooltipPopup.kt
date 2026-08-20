package moe.forpleuvoir.compose_minecraft.platform.ui.tooltip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftGuiScale
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftTooltipRenderer
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.drawMinecraftTooltip
import moe.forpleuvoir.compose_minecraft.platform.ui.popup.LocalPopupHost
import moe.forpleuvoir.compose_minecraft.platform.ui.popup.register
import kotlin.math.roundToInt

/**
 * 原版 tooltip 定位(T.39):实现 `DefaultTooltipPositioner` 同款算法 ——
 * 鼠标 + 偏移(20, -20)起手,超出右边界翻转回退 24、贴边 4,超出下边界贴底
 * (高度 + 3 边距)。所有数值参数化,默认 = 原版。
 *
 * 注意:若 [mouseOffsetX]/[mouseOffsetY] 取值较小(如原版 20,-20),popup
 * 背景区会覆盖鼠标位置,此时 popup 图层会拦截指针事件,可能导致多图层场景
 * 中下层 hover 丢失。调用方应适当增大偏移(如 20,20)使 popup 完全避开鼠标
 * 来解决闪烁;或改用锚点定位。
 *
 * [mouse] 为窗口像素(场景坐标);[guiScale] 非 null 时按原版 GUI 单位计算
 * (鼠标像素 / scale、屏幕/弹层尺寸 / scale),最终结果乘回 scale 返回像素。
 */
class TooltipPositionProvider(
    private val mouse: () -> Offset,
    private val guiScale: Float? = null,
    /**
     * 内容区左上角相对鼠标的水平偏移(像素/逻辑单位)。默认 20(原版):内容区在
     * 鼠标右侧 20px,背景从鼠标位置开始。增大此值使 popup 右移,鼠标不再在背景内。
     */
    private val mouseOffsetX: Int = MinecraftTooltipRenderer.MOUSE_OFFSET_X,
    /**
     * 内容区左上角相对鼠标的垂直偏移(像素/逻辑单位)。默认 -20(原版):内容区在
     * 鼠标上方 20px,背景从 my-24 开始,鼠标在背景内。设为正值(如 20)使 popup
     * 完全在鼠标下方,鼠标不在 popup 内,避免多图层 hover 闪烁。
     */
    private val mouseOffsetY: Int = MinecraftTooltipRenderer.MOUSE_OFFSET_Y,
    private val overflowFlipBack: Int = MinecraftTooltipRenderer.OVERFLOW_FLIP_BACK,
    private val edgeMin: Int = MinecraftTooltipRenderer.EDGE_MIN,
    private val screenPadding: Int = MinecraftTooltipRenderer.SCREEN_PADDING,
    /** popup 布局尺寸两侧的背景外扩(2 × (PADDING + MARGIN)),定位时减掉得到内容区尺寸 */
    private val outerPaddingX: Int = MinecraftTooltipRenderer.TOTAL_OUTER_PADDING,
    private val outerPaddingY: Int = MinecraftTooltipRenderer.TOTAL_OUTER_PADDING,
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val scale = guiScale ?: 1f
        val screenW = (windowSize.width / scale).roundToInt()
        val screenH = (windowSize.height / scale).roundToInt()
        // popup 布局尺寸是"背景区"(内容区两侧各 PADDING+MARGIN,像素),换算回内容区尺寸
        val contentW = ((popupContentSize.width - outerPaddingX * scale) / scale).roundToInt().coerceAtLeast(0)
        val contentH = ((popupContentSize.height - outerPaddingY * scale) / scale).roundToInt().coerceAtLeast(0)
        val m = mouse()
        val mx = (m.x / scale).roundToInt()
        val my = (m.y / scale).roundToInt()

        // 先按原版算法算"内容区左上角"(鼠标 +20,-20 + 边界校正)
        var x = mx + mouseOffsetX
        var y = my + mouseOffsetY
        if (x + contentW > screenW) {
            x = maxOf(x - overflowFlipBack - contentW, edgeMin)
        }
        val paddedHeight = contentH + screenPadding
        if (y + paddedHeight > screenH) {
            y = screenH - paddedHeight
        }
        // popup 内容是背景区:其左上角 = 内容区左上角 - 单侧外扩(PADDING + MARGIN)
        val bgX = x - (outerPaddingX / 2)
        val bgY = y - (outerPaddingY / 2)
        return IntOffset(
            (bgX * scale).roundToInt().coerceAtLeast(0),
            (bgY * scale).roundToInt().coerceAtLeast(0),
        )
    }
}

/**
 * 以 popup 形式渲染原版视觉 tooltip(T.39):基于 [LocalPopupHost] 注册场景根弹层,
 * 内容为绘制原版 tooltip 视觉的 [Canvas](经 [drawMinecraftTooltip],不走
 * GuiGraphicsExtractor);定位用 [TooltipPositionProvider](鼠标跟随,原版语义)。
 *
 * [guiScaleEnabled] 为真时按当前窗口原版 guiScale([MinecraftGuiScale.current])布局
 * 并整体放大(含 popup 内容尺寸与定位计算);为假(默认)则 1:1 像素。
 *
 * @param key 弹层唯一键(业务生成,如 `remember { Any() }`)。
 * @param visible 是否显示;false 时不注册。
 * @param lines tooltip 数据([TooltipLines] 由 itemTooltipLines / tooltipLinesOf 构建)。
 * @param mouse 鼠标位置(窗口像素/场景坐标),驱动定位。
 * @param guiScaleEnabled 是否启用原版 guiScale。
 * @param positionProvider 自定义定位(默认 [TooltipPositionProvider])。
 */
@Composable
fun rememberTooltip(
    key: Any,
    visible: Boolean,
    lines: TooltipLines,
    mouse: Offset,
    guiScaleEnabled: Boolean = false,
    /** 自定义 Compose 密度(覆盖 [LocalDensity.current.density])。null = 自动读取场景密度。 */
    density: Float? = null,
    positionProvider: PopupPositionProvider? = null,
    properties: PopupProperties = PopupProperties(focusable = false),
) {
    val popupHost = LocalPopupHost.current
    if (!visible || popupHost == null || lines.lines.isEmpty()) return

    val densityValue = density ?: LocalDensity.current.density
    val finalScale: Float = if (guiScaleEnabled) {
        MinecraftGuiScale.current()
    } else {
        densityValue * MinecraftTooltipRenderer.DENSITY_TO_GUI_SCALE_MULTIPLIER
    }
    val size = lines.measure()
    // positionProvider 的 guiScale 参数:null=1:1,非null=缩放系数(与原版 guiScale 语义一致)
    val provider = positionProvider ?: TooltipPositionProvider(
        mouse = { mouse },
        guiScale = if (guiScaleEnabled) finalScale else null,
    )

    // 背景外扩(PADDING + MARGIN)会画到内容区之外:Canvas 布局尺寸含外扩
    // (避免被 popup 图层 clip 裁掉),绘制时内容区整体偏移到外扩内侧。
    val outer = MinecraftTooltipRenderer.PADDING + MinecraftTooltipRenderer.MARGIN

    popupHost.register(
        key = key,
        positionProvider = provider,
        onDismissRequest = null,
        properties = properties,
    ) {
        // 需要的像素尺寸 = (内容区 + 外扩) × finalScale
        val pixelW = ((size.width + MinecraftTooltipRenderer.TOTAL_OUTER_PADDING) * finalScale).roundToInt().coerceAtLeast(1)
        val pixelH = ((size.height + MinecraftTooltipRenderer.TOTAL_OUTER_PADDING) * finalScale).roundToInt().coerceAtLeast(1)
        // dp 尺寸 = 像素尺寸 / density(Compose 场景密度)
        Canvas(Modifier.size((pixelW / densityValue).dp, (pixelH / densityValue).dp)) {
            drawMinecraftTooltip(lines, outer, outer, if (guiScaleEnabled) null else densityValue)
        }
    }
}