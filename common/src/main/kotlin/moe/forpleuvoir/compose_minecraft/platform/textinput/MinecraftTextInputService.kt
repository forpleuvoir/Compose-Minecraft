package moe.forpleuvoir.compose_minecraft.platform.textinput

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.text.input.TextFieldValue
import net.minecraft.client.Minecraft
import net.minecraft.client.input.PreeditEvent
import kotlin.math.roundToInt

/**
 * MC 平台文本输入服务(IME Service,实施计划 mc-ime-service-plan.md)。
 *
 * 职责:
 * - 会话开启/关闭时启停 MC 的系统输入法([TextInputManager.startTextInput] / [TextInputManager.stopTextInput]),
 *   使 TextField 聚焦时弹出系统 IME;
 * - 把 MC 的 preedit 组合态回调([PreeditEvent])转换成 Compose 标准 [EditCommand]
 *   (SetComposingRegionCommand + SetComposingTextCommand),组合区在缓冲区中渲染为下划线;
 * - 候选窗跟随光标:组合更新时把光标矩形(根坐标 = 场景像素,T.24 1:1 渲染)交给
 *   [TextInputManager.setTextInputArea](其内部乘 guiScale 转物理像素,
 *   T.31:场景像素化后这里先除回 GUI 单位,净效果 = 像素直传)。
 *
 * 提交语义(与 charTyped 并存、无重复):
 * MC 的 GLFW 分支(TuxTheAstronaut/minecraft-glfw)在 IME 提交时,WM_IME_COMPOSITION 中
 * 先处理 GCS_RESULTSTR 触发字符回调(→ Screen.charTyped → Compose 插入提交文本),
 * 再处理 GCS_COMPSTR(组合已空)→ preedit(null);取消时(WM_IME_ENDCOMPOSITION)只有
 * preedit(null)且无字符回调。因此:
 * - 提交的最终文本永远走 charTyped(原输入链路不变),preedit 不负责上屏;
 * - preedit 非空:组合文本写入缓冲区(composition range 渲染下划线);
 * - preedit null(提交或取消):提交字符已由 charTyped 插入(且其内部
 *   replaceSelectedText(clearComposition=true) 已清除组合区),这里仅删除缓冲区中残留的
 *   组合文本(取消场景组合文本仍在 composition range 内,一并删除),并把光标放到
 *   提交文本之后(取消场景光标回到组合起点)。
 */
@Suppress("DEPRECATION") // PlatformTextInputService 官方已废弃(改用 PlatformTextInputModifierNode),移植兼容层仍在使用
internal class MinecraftTextInputService : PlatformTextInputService {

    // ── 会话绑定 ──

    /** 新版 API 会话(BasicTextField(state))经自定义 PlatformContext.startInputMethod 绑定 */
    @OptIn(ExperimentalComposeUiApi::class)
    private var request: PlatformTextInputMethodRequest? = null

    /** 旧版 API 会话(BasicTextField(value))经 startInput(value, ...) 绑定 */
    private var legacyOnEditCommand: ((List<EditCommand>) -> Unit)? = null
    private var legacyValue: TextFieldValue = TextFieldValue("")

    // ── 组合态跟踪 ──

    private var composing = false
    private var composedStart = 0
    private var composedLength = 0

    /** 组合期间经 charTyped 上屏的字符数(IME 提交文本),组合结束时用于定位光标 */
    private var committedCharCount = 0

    /** 候选窗锚点(旧版 API 经 [notifyFocusedRect] 缓存;新版直接读 request.focusedRectInRoot) */
    private var focusedRect: Rect? = null

    private val textInputManager get() = Minecraft.getInstance().textInputManager()

    /**
     * 是否处于 IME 组合态。
     *
     * 组合期间,MC 仍会把 Backspace/Delete/Esc 等按键以 keyPressed 转发给 Screen(官方桌面
     * AWT 在组合期间由 InputMethod 消费按键、不达应用)。这些键应由输入法处理(缩短/清除
     * 组合串、取消组合),不能进入 Compose 文本处理——否则 Compose 会删除组合文本并清除
     * 组合区(TextFieldBuffer.replace 无条件 commitComposition),导致后续 preedit 更新
     * 在错误位置重新插入组合文本。
     */
    val isComposing: Boolean
        get() = composing

    // ── 会话生命周期 ──

    override fun startInput(
        value: TextFieldValue,
        imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit,
        onImeActionPerformed: (ImeAction) -> Unit,
    ) {
        request = null
        legacyOnEditCommand = onEditCommand
        legacyValue = value
        textInputManager.startTextInput()
    }

    /** 新版 API 会话在 RootNodeOwner.TextInputSession.startInputMethod 中调用(无参重载) */
    override fun startInput() {
        textInputManager.startTextInput()
    }

    override fun stopInput() {
        textInputManager.stopTextInput()
        request = null
        legacyOnEditCommand = null
        composing = false
        composedStart = 0
        composedLength = 0
        committedCharCount = 0
        focusedRect = null
    }

    override fun showSoftwareKeyboard() {
        textInputManager.startTextInput()
    }

    override fun hideSoftwareKeyboard() {
        textInputManager.stopTextInput()
    }

    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {
        legacyValue = newValue
    }

    // ── 候选窗跟随 ──

    override fun notifyFocusedRect(rect: Rect) {
        focusedRect = rect
        updateTextInputArea()
    }

    // ── 新版 API 会话绑定(由自定义 PlatformContext.startInputMethod 调用)──

    @OptIn(ExperimentalComposeUiApi::class)
    fun bindRequest(newRequest: PlatformTextInputMethodRequest) {
        legacyOnEditCommand = null
        request = newRequest
        composing = false
        committedCharCount = 0
        focusedRect = null
        textInputManager.startTextInput()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun unbindRequest(newRequest: PlatformTextInputMethodRequest) {
        if (request === newRequest) {
            stopInput()
        }
    }

    // ── preedit 转发(由 ComposeScreen.preeditUpdated 调用)──

    fun onPreeditChanged(event: PreeditEvent?) {
        if (event != null) {
            onComposing(event)
        } else {
            onCompositionEnded()
        }
    }

    /** 组合中:把组合文本写入缓冲区并标记组合区(Compose 渲染下划线/光标) */
    private fun onComposing(event: PreeditEvent) {
        val text = event.fullText()
        if (text.isEmpty()) {
            // 防御:MC 的 PreeditEvent.createFromCallback 对空组合串返回 null,正常不会走到
            onCompositionEnded()
            return
        }
        val value = currentValue()
        val start = value.composition?.start ?: value.selection.min
        composedStart = start
        composedLength = text.length
        composing = true
        committedCharCount = 0
        sendEditCommands(
            listOf(
                // 官方桌面语义(DesktopPlatformInput.desktop.kt):只发 SetComposingTextCommand,
                // 其内部会自动替换整个旧组合区(有组合时 replace(compositionStart, compositionEnd, text),
                // 无组合时插入光标处),并按新文本长度重设组合区与光标。
                // 不要额外发 SetComposingRegionCommand:它会先 commitComposition 再按传入范围
                // 重设组合区,退格等缩短组合的场景下旧组合区被提前缩小,导致替换不完整、
                // 组合文本残留错乱(退格"删不掉"拼音)。
                SetComposingTextCommand(text, 1),
            )
        )
        updateTextInputArea()
    }

    /**
     * 组合结束(null = 提交或取消),见类注释的事件顺序:
     * 提交场景:charTyped 已把提交字符插入缓冲区并清除组合区,这里仅删除残留组合文本;
     * 取消场景:组合文本仍在 composition range 内,一并删除。
     */
    private fun onCompositionEnded() {
        if (!composing) return
        composing = false
        val end = composedStart + composedLength
        sendEditCommands(
            listOf(
                // 取消场景:先重新标记组合区(SetComposingRegionCommand 内部会先提交旧组合),
                // 再由 CommitTextCommand("") 删除;提交场景:组合区已被 charTyped 清除,
                // SetComposingRegionCommand 直接设置残留组合文本的范围后删除。
                SetComposingRegionCommand(composedStart, end),
                CommitTextCommand("", 0),
                SetSelectionCommand(
                    composedStart + committedCharCount,
                    composedStart + committedCharCount,
                ),
            )
        )
        composedStart = 0
        composedLength = 0
        committedCharCount = 0
    }

    /** charTyped 计数:组合期间上屏的字符属于 IME 提交文本(组合结束时用于定位光标) */
    fun onCharTyped(codePoint: Int) {
        if (composing) {
            committedCharCount++
        }
    }

    // ── 内部 ──

    @OptIn(ExperimentalComposeUiApi::class)
    private fun currentValue(): TextFieldValue =
        request?.value?.invoke() ?: legacyValue

    private fun sendEditCommands(commands: List<EditCommand>) {
        val callback = request?.onEditCommand ?: legacyOnEditCommand ?: return
        callback(commands)
    }

    /**
     * 候选窗跟随:组合更新时把光标矩形交给 MC TextInputManager。
     *
     * T.24 后场景为 1:1 像素渲染(场景尺寸 = 窗口像素,根坐标即物理像素),而
     * [TextInputManager.setTextInputArea] 内部把入参当 GUI 单位再乘 guiScale 转
     * 物理像素 —— 直接传像素会被二次放大,候选窗位置随 guiScale 偏移。
     * 这里先除回 GUI 单位,净效果 = 像素直传(T.31)。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun updateTextInputArea() {
        val rect = request?.focusedRectInRoot?.invoke() ?: focusedRect ?: return
        val scale = Minecraft.getInstance().window.guiScale.toFloat().coerceAtLeast(1f)
        textInputManager.setTextInputArea(
            (rect.left / scale).roundToInt(),
            (rect.top / scale).roundToInt(),
            (rect.right / scale).roundToInt(),
            (rect.bottom / scale).roundToInt(),
        )
    }
}
