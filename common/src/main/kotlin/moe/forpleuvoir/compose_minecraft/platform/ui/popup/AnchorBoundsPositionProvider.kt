package moe.forpleuvoir.compose_minecraft.platform.ui.popup

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

/**
 * 锚点 bounds 在整数像素下的矩形。
 */
internal fun Rect.roundToIntRect(): IntRect =
    IntRect(
        left.roundToInt(),
        top.roundToInt(),
        right.roundToInt(),
        bottom.roundToInt(),
    )

/**
 * 基于锚组件在 root 坐标系中的 [Rect] 定位弹层(如 tooltip)。
 *
 * 沿偏好方向放置,垂直/水平方向均相对锚组件**居中对齐**;
 * 空间不足自动回退:偏好方向 → 反向 → 正交方向(上/下 ↔ 左/右互转)→ 空间更大的一侧,
 * 最后用 `coerceIn` 夹取避免溢出 root。
 *
 * 锚点捕获**不新增布局节点**:业务在锚组件自己的 modifier 链上用
 * `Modifier.onGloballyPositioned { anchorBounds = it.boundsInRoot() }` 记录,
 * 再以 `{ anchorBounds }` 惰性闭包传入本定位器(参考 ibukigourd Tooltip 思路)。
 *
 * @param anchorBounds 锚组件在 root 坐标系中的 bounds(from `boundsInRoot`),惰性读取。
 * @param position 偏好方向,空间不足自动翻转。
 * @param spacing 弹层与锚组件之间的间距(同时充当与 root 边缘的安全间距)。
 */
@Immutable
class AnchorBoundsPositionProvider(
    private val anchorBounds: () -> Rect,
    private val position: AnchorPosition,
    private val spacing: Int = 4,
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val bounds = this@AnchorBoundsPositionProvider.anchorBounds().roundToIntRect()
        // anchorBounds 尚未记录(首帧)时退回调用方传入的窗口锚点(几乎不会发生)
        val effectiveBounds =
            if (bounds.width == 0 && bounds.height == 0) anchorBounds else bounds
        val tooltipW = popupContentSize.width.coerceAtLeast(0)
        val tooltipH = popupContentSize.height.coerceAtLeast(0)
        val rootW = windowSize.width
        val rootH = windowSize.height

        val spaceAbove = effectiveBounds.top - spacing
        val spaceBelow = rootH - effectiveBounds.bottom - spacing
        val rtl = layoutDirection == LayoutDirection.Rtl
        val spaceStart = if (rtl) rootW - effectiveBounds.right - spacing else effectiveBounds.left - spacing
        val spaceEnd = if (rtl) effectiveBounds.left - spacing else rootW - effectiveBounds.right - spacing

        fun pickVertical(pref: AnchorPosition, opp: AnchorPosition): AnchorPosition = when {
            (if (pref == AnchorPosition.Above) spaceAbove else spaceBelow) >= tooltipH -> pref
            (if (opp == AnchorPosition.Above) spaceAbove else spaceBelow) >= tooltipH -> opp
            // 上下都放不下,转向左右
            spaceStart >= tooltipW -> AnchorPosition.Start
            spaceEnd >= tooltipW -> AnchorPosition.End
            else -> if (spaceStart >= spaceEnd) AnchorPosition.Start else AnchorPosition.End
        }

        fun pickHorizontal(pref: AnchorPosition, opp: AnchorPosition): AnchorPosition = when {
            (if (pref == AnchorPosition.Start) spaceStart else spaceEnd) >= tooltipW -> pref
            (if (opp == AnchorPosition.Start) spaceStart else spaceEnd) >= tooltipW -> opp
            // 左右都放不下,转向上下
            spaceAbove >= tooltipH -> AnchorPosition.Above
            spaceBelow >= tooltipH -> AnchorPosition.Below
            else -> if (spaceAbove >= spaceBelow) AnchorPosition.Above else AnchorPosition.Below
        }

        val effective = when (position) {
            AnchorPosition.Above -> pickVertical(AnchorPosition.Above, AnchorPosition.Below)
            AnchorPosition.Below -> pickVertical(AnchorPosition.Below, AnchorPosition.Above)
            AnchorPosition.Start -> pickHorizontal(AnchorPosition.Start, AnchorPosition.End)
            AnchorPosition.End -> pickHorizontal(AnchorPosition.End, AnchorPosition.Start)
        }

        val xRange = spacing..(rootW - tooltipW - spacing).coerceAtLeast(spacing)
        val yRange = spacing..(rootH - tooltipH - spacing).coerceAtLeast(spacing)

        return when (effective) {
            AnchorPosition.Above -> {
                val y = (effectiveBounds.top - tooltipH - spacing).coerceIn(yRange)
                val x = (effectiveBounds.left + (effectiveBounds.width - tooltipW) / 2).coerceIn(xRange)
                IntOffset(x, y)
            }

            AnchorPosition.Below -> {
                val y = (effectiveBounds.bottom + spacing).coerceIn(yRange)
                val x = (effectiveBounds.left + (effectiveBounds.width - tooltipW) / 2).coerceIn(xRange)
                IntOffset(x, y)
            }

            AnchorPosition.Start -> {
                val x = if (rtl) {
                    (effectiveBounds.right + spacing).coerceIn(xRange)
                } else {
                    (effectiveBounds.left - tooltipW - spacing).coerceIn(xRange)
                }
                val y = (effectiveBounds.top + (effectiveBounds.height - tooltipH) / 2).coerceIn(yRange)
                IntOffset(x, y)
            }

            AnchorPosition.End -> {
                val x = if (rtl) {
                    (effectiveBounds.left - tooltipW - spacing).coerceIn(xRange)
                } else {
                    (effectiveBounds.right + spacing).coerceIn(xRange)
                }
                val y = (effectiveBounds.top + (effectiveBounds.height - tooltipH) / 2).coerceIn(yRange)
                IntOffset(x, y)
            }
        }
    }
}

/**
 * 弹层相对锚组件的偏好方向。
 */
@Immutable
enum class AnchorPosition {
    /** 锚组件上方:上方不足→下方→左右更宽一侧。 */
    Above,

    /** 锚组件下方:下方不足→上方→左右更宽一侧。 */
    Below,

    /** 锚组件左侧(RTL 下为右侧):左侧不足→右侧→上下更高一侧。 */
    Start,

    /** 锚组件右侧(RTL 下为左侧):右侧不足→左侧→上下更高一侧。 */
    End,
}