package moe.forpleuvoir.compose_minecraft.platform.textinput.imblocker

import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import io.github.reserveword.imblocker.common.IMManager
import io.github.reserveword.imblocker.common.gui.FocusContainer
import io.github.reserveword.imblocker.common.gui.FocusManager
import io.github.reserveword.imblocker.common.gui.FocusableWidget
import io.github.reserveword.imblocker.common.gui.Point
import io.github.reserveword.imblocker.common.gui.Rectangle
import moe.forpleuvoir.compose_minecraft.mc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Compose 文本输入会话在 IMBlocker 侧的登记句柄。
 *
 * [close] 把该会话从 IMBlocker 的焦点候选中移除; 会话结束(失焦 / 场景关闭 / 换绑请求)时必须调用,
 * 否则失效的候选会留在 IMBlocker 的焦点表里继续参与焦点判定。
 */
fun interface IMBlockerFocusSession : AutoCloseable {

    override fun close()
}

/**
 * IMBlocker 兼容实现。
 *
 * 本文件是全工程唯一直接引用 IMBlocker 类型的源码: 它只能由 [IMBlockerCompat] 在确认 IMBlocker
 * 已加载后反射载入; 模组缺席时 `Class.forName` 抛 [NoClassDefFoundError], 由调用方吞掉,
 * 兼容层整体降级为 no-op。
 *
 * 四个入口与 IMBlocker 7.3 的焦点模型对应:
 * - [requestTextInputFocus] — 会话建立时把 Compose 文本框登记为 `FocusContainer.MINECRAFT` 的
 *   焦点候选(候选制: 容器在多个候选中挑真正持有键盘输入的那个, 可能走字符模拟验证);
 * - [updateCaretPosition] — 光标 / 位置变化时让 IMBlocker 重算候选窗坐标;
 * - [onCharTyped] — 收到字符(含焦点探测字符)时回报, 见该方法 KDoc;
 * - [refreshFocus] — 输入事件到达时按需重登记(候选可能已被换屏逻辑清掉)。
 */
internal object IMBlockerCompatImpl {

    /** 当前登记的候选:同一时刻至多一个 Compose 会话持有文本输入焦点。 */
    private var activeWidget: ComposeFocusableWidget? = null

    @JvmStatic
    fun requestTextInputFocus(
        request: PlatformTextInputMethodRequest,
    ): IMBlockerFocusSession {
        val widget = ComposeFocusableWidget(request)

        activeWidget = widget
        FocusContainer.MINECRAFT.requestFocus(widget)

        return IMBlockerFocusSession {
            if (activeWidget === widget) {
                activeWidget = null
            }
            FocusContainer.MINECRAFT.removeFocus(widget)
        }
    }

    @JvmStatic
    fun updateCaretPosition() {
        IMManager.updateCaretPosition()
    }

    /**
     * Compose 文本框收到字符后回调。
     *
     * IMBlocker 的 `MinecraftFocusContext.locateRealFocus` 在部分场景用"向窗口发探测字符"的方式
     * 确认真正的焦点持有者(`FocusManager.isTrackingFocus` 为 true): 字符经场景 `charTyped` 落到
     * Compose, 这里必须把焦点判给本候选(`switchFocus` 在跟踪期会置 `isFocusLocated`), 否则
     * IMBlocker 认为无人接收 → `restoreContainerFocus()` → 容器 `preferredState=false` → 关掉输入法。
     */
    @JvmStatic
    fun onCharTyped() {
        val widget = activeWidget ?: return

        if (FocusManager.isTrackingFocus) {
            FocusContainer.MINECRAFT.switchFocus(widget)
        } else {
            refreshFocus()
        }
    }

    /**
     * 按需重新登记焦点候选:只在焦点确实不在本候选时动作。
     *
     * [FocusContainer.requestFocus] 会刷新候选时间戳并让容器重新定位焦点;已持焦点时跳过 ——
     * 部分屏幕的定位方式是"字符模拟"(向窗口发探测字符),无谓重定位只会多发探测字符。
     */
    @JvmStatic
    fun refreshFocus() {
        val widget = activeWidget ?: return

        if (!widget.isTrulyFocused) {
            FocusContainer.MINECRAFT.requestFocus(widget)
        }
    }
}

/**
 * 把 Compose 文本框接入 IMBlocker 的焦点体系。
 *
 * 坐标契约(IMBlocker 7.3): [getBoundsAbs] / [getCaretPos] 返回**物理像素**,
 * [getFontHeight] 返回 **UI 像素** —— 候选窗高度 = `fontHeight × guiScale`, 故两者单位必须自洽。
 * 本平台 Compose 场景为 1:1 像素渲染(场景根坐标 = 窗口物理像素), 因此 `textFieldRectInRoot()` /
 * `focusedRectInRoot()` 的返回值可直接当物理像素用;
 * [getGuiScale] 取窗口 guiScale, 与 CMP 交给 MC `TextInputManager.setTextInputArea` 的换算同源 ——
 * 即 `物理像素 = UI 像素 × guiScale`。
 */
private class ComposeFocusableWidget(
    private val request: PlatformTextInputMethodRequest,
) : FocusableWidget {

    override fun getFocusContainer(): FocusContainer = FocusContainer.MINECRAFT

    /** 文本框期望开启输入法。 */
    override fun getPreferredState(): Boolean = true

    /** 会话存活即视为可渲染, 否则 IMBlocker 会因候选不可见而把焦点退还给容器。 */
    override fun isRenderable(): Boolean = true

    override fun getBoundsAbs(): Rectangle {
        val field = request.textFieldRectInRoot() ?: return Rectangle.EMPTY

        return Rectangle(
            floor(field.left).toInt(),
            floor(field.top).toInt(),
            ceil(field.width).toInt(),
            ceil(field.height).toInt(),
        )
    }

    /** 光标相对文本框左上角的位置: 组合窗跟随光标, 取 `focusedRect` 与文本框矩形之差。 */
    override fun getCaretPos(): Point {
        val field = request.textFieldRectInRoot() ?: return Point.TOP_LEFT
        val caret = request.focusedRectInRoot() ?: return Point.TOP_LEFT

        return Point(
            floor(caret.left - field.left).toInt(),
            floor(caret.top - field.top).toInt(),
        )
    }

    override fun getGuiScale(): Double =
        mc.window.guiScale.toDouble().coerceAtLeast(1.0)

    override fun getFontHeight(): Int {
        val caretHeight = request.focusedRectInRoot()?.height ?: return 8

        return (caretHeight / getGuiScale()).roundToInt().coerceAtLeast(1)
    }
}
