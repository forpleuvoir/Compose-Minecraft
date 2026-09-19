package moe.forpleuvoir.compose_minecraft.platform.textinput.imblocker

import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import com.mojang.logging.LogUtils
import org.slf4j.Logger

/**
 * IMBlocker 输入法兼容入口(反射分发)。
 *
 * 本类不直接引用 IMBlocker 的任何类型, 因此模组缺席时也能安全加载; 真正引用 IMBlocker 类型的
 * 实现在 [IMBlockerCompatImpl] 内, 只在此处按需经 [Class.forName] 载入 —— 模组缺席时加载失败
 * 被吞掉, 整个兼容层降级为 no-op, 不抛错、不影响正常输入。
 *
 * 用法: Compose 文本输入会话建立时 [requestTextInputFocus] 登记, 会话结束时调用返回的
 * [IMBlockerFocusSession.close] 注销; 光标 / 文本框位置变化时 [updateCaretPosition];
 * 输入事件(点击 / 按键)到达时 [refreshFocus] 按需重登记; 收到字符时 [onCharTyped] 回报。
 */
object IMBlockerCompat {

    private val LOGGER: Logger = LogUtils.getLogger()

    private const val IMPLEMENTATION_CLASS =
        "moe.forpleuvoir.compose_minecraft.platform.textinput.imblocker.IMBlockerCompatImpl"

    private val implementation: Implementation? by lazy {
        runCatching {
            val clazz = Class.forName(
                IMPLEMENTATION_CLASS,
                true,
                IMBlockerCompat::class.java.classLoader,
            )

            val requestTextInputFocusMethod = clazz.getDeclaredMethod(
                "requestTextInputFocus",
                PlatformTextInputMethodRequest::class.java,
            )

            val updateCaretPositionMethod = clazz.getDeclaredMethod("updateCaretPosition")

            val onCharTypedMethod = clazz.getDeclaredMethod("onCharTyped")

            val refreshFocusMethod = clazz.getDeclaredMethod("refreshFocus")

            object : Implementation {

                override fun requestTextInputFocus(
                    request: PlatformTextInputMethodRequest,
                ): IMBlockerFocusSession =
                    requestTextInputFocusMethod.invoke(null, request) as IMBlockerFocusSession

                override fun updateCaretPosition() {
                    updateCaretPositionMethod.invoke(null)
                }

                override fun onCharTyped() {
                    onCharTypedMethod.invoke(null)
                }

                override fun refreshFocus() {
                    refreshFocusMethod.invoke(null)
                }
            }
        }.onSuccess {
            LOGGER.info("[ComposeMinecraft] IMBlocker detected, IME compatibility enabled")
        }.onFailure {
            LOGGER.info("[ComposeMinecraft] IMBlocker absent, IME compatibility disabled")
        }.getOrNull()
    }

    /**
     * 把当前 Compose 文本输入会话登记为 IMBlocker 的焦点候选。
     *
     * @return 注销句柄; IMBlocker 缺席或登记失败时返回 null
     */
    fun requestTextInputFocus(
        request: PlatformTextInputMethodRequest,
    ): IMBlockerFocusSession? = runCatching {
        implementation?.requestTextInputFocus(request)
    }.onFailure {
        LOGGER.error("[ComposeMinecraft] failed to register Compose text input with IMBlocker", it)
    }.getOrNull()

    /** 光标 / 文本框位置变化后同步 IME 候选窗位置(组合期间每次更新)。 */
    fun updateCaretPosition() {
        runCatching {
            implementation?.updateCaretPosition()
        }.onFailure {
            LOGGER.error("[ComposeMinecraft] failed to update IMBlocker composition position", it)
        }
    }

    /**
     * Compose 文本框收到字符后上报。
     *
     * IMBlocker 会用"向窗口发探测字符"的方式确认真正的输入焦点(见其 `locateRealFocus`); 探测字符
     * 经场景 `charTyped` 落到这里, 若不回报, IMBlocker 判定无人接收并把焦点退还给容器
     * (`preferredState=false` → 关掉输入法), 表现为"装了 IMBlocker 反而没有输入法"。
     */
    fun onCharTyped() {
        runCatching {
            implementation?.onCharTyped()
        }.onFailure {
            LOGGER.error("[ComposeMinecraft] failed to report charTyped to IMBlocker", it)
        }
    }

    /**
     * 按需重新登记焦点候选(输入事件到达时调用)。
     *
     * IMBlocker 会在换屏等时机清空候选(其 `MinecraftScreenMonitor.onScreenChanged` 调
     * `clearFocus()`),或把焦点退还给容器;这两种情况都不会再触发一次 Compose 的
     * `startInputMethod`(文本框本来就一直是聚焦状态),于是没人把它登记回去 ——
     * 表现为"屏幕打开时已自动聚焦文本框,之后第一次点击它不出输入法"。
     * 本方法只在焦点确实不在本候选时动作,正常路径是一次判空 + 一次布尔判断。
     */
    fun refreshFocus() {
        runCatching {
            implementation?.refreshFocus()
        }.onFailure {
            LOGGER.error("[ComposeMinecraft] failed to refresh IMBlocker focus", it)
        }
    }

    private interface Implementation {

        fun requestTextInputFocus(
            request: PlatformTextInputMethodRequest,
        ): IMBlockerFocusSession

        fun updateCaretPosition()

        fun onCharTyped()

        fun refreshFocus()
    }
}
