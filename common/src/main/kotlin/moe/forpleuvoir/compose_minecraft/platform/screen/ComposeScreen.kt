package moe.forpleuvoir.compose_minecraft.platform.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorType
import moe.forpleuvoir.compose_minecraft.platform.textinput.ComposeInputBridge.scrollDelta
import moe.forpleuvoir.compose_minecraft.platform.textinput.ComposeInputBridge.toCompose
import moe.forpleuvoir.compose_minecraft.platform.textinput.ComposeInputBridge.toPointerKeyboardModifiers
import moe.forpleuvoir.compose_minecraft.platform.textinput.MinecraftTextInputService
import moe.forpleuvoir.compose_minecraft.platform.render.ComposeGuiRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.network.chat.Component
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import net.minecraft.client.input.KeyEvent as MCKeyEvent

/**
 * 原版 [Screen] 桥接(参考 ibuki_gourd 的 ComposeScreen 模式):
 * Compose 场景通过一个原版 Screen 挂入 Minecraft,渲染/层级/输入生命周期
 * 全部走原版屏幕机制,不依赖任何 loader 事件或 mixin 帧钩子。
 *
 * - 渲染:[extractRenderState] 每帧被调用,把场景绘制命令提交进当前帧 GuiRenderState;
 * - 输入:鼠标(点击/释放/移动/拖拽/滚轮)、键盘(按下/释放)与字符(charTyped,含中文
 *   输入法上屏)从原版 Screen 转发到 Compose 场景(阶段 F + 文本输入 I.1);
 *   场景坐标 = 像素(密度默认 1f,1dp == 1 像素;[density] 可配置放大,见构造);
 * - 生命周期:[removed] 时关闭场景(任何被替换/关闭路径都会触发)。
 *
 * IME 支持(实施计划已随实现落地移除):[preeditUpdated] 把系统输入法组合态
 * (preedit)转发到 Compose 编辑缓冲(下划线组合文本);[charTyped] 保持提交文本上屏
 * 并通知 service 计数(组合结束时定位光标)。
 *
 * 父屏幕能力(T.25):
 * - [parent]:打开本屏之前的 Screen。关闭时([onClose],Esc 或业务调用)自动
 *   `setScreen(parent)` 返回父屏 —— 与原版各 Screen 的 lastScreen 约定一致
 *   (原版 [Screen.onClose] 默认 `setScreen(null)`,[net.minecraft.client.gui.Gui.setScreen] 无自动记忆,
 *   lastScreen 是子类约定);
 * - [renderParentScreen]:开关,控制本屏打开时是否把父屏内容渲染在 Compose 之下
 *   (Compose 内容在最上层,半透明背景可透出父屏)。原版父屏走原版 GuiRenderState
 *   (原版 guiRenderer 先画);Compose 父屏由其 [MinecraftComposeScene.renderFrame]
 *   收集后经 [ComposeGuiRenderer.absorbAndClear] 并入本屏渲染器(顺序在前);
 * - [reopenable]:父屏为 ComposeScreen 时由 [open] 自动标记 —— [removed] 保留场景
 *   (不 close),返回时 [added] 复活(场景与组合状态保留,滚动位置等不丢失);
 *   非可复活屏关闭即销毁场景。
 *
 * 尚未支持:双击、IME 候选窗(由系统输入法负责);Popup/Dialog 已实现(T.33,
 * 场景内图层弹层:焦点隔离/scrim 遮罩/Escape 与 outside 点击关闭)。
 */
class ComposeScreen(
    val parent: Screen? = null,
    var renderParentScreen: Boolean = false,
    /**
     * 场景密度(T.26):默认 1f(1dp == 1 像素,场景尺寸 = 窗口像素 T.24);
     * 传 >1f 放大 UI(官方桌面 density 语义,文本字号同步放大)。
     */
    val density: Float = 1f,
    private val content: @Composable () -> Unit,
) : Screen(Component.literal("Compose Screen")) {

    /** 本屏持有的 Compose 场景(可复活:removed 保留场景时非空,否则重建) */
    private var composeScene: MinecraftComposeScene? = null

    /**
     * T.25:可复活标记 —— true 时 [removed] 不销毁场景(关闭返回父屏后状态保留),
     * 由 [open] 在替换父屏前自动设置(父屏为 ComposeScreen 时)。
     */
    var reopenable: Boolean = false

    // ── 复述系统(Narration)桥接状态 ──────────────────────────
    // 最近一次鼠标位置(像素,取自 mouseHandler 原始坐标):悬停朗读回退用

    private val mousePosition get() = Offset(minecraft.mouseHandler.xpos().toFloat(), minecraft.mouseHandler.ypos().toFloat())

    private var lastFocusedSemanticsId: Int = -1

    // 语义变化暂存:onSemanticsChanged 回调发生在语义快照提交期(合成/测量阶段),
    // 不能直接调 triggerImmediateNarration,改为标记后在下一帧 extractRenderState 处理
    private var narrationPending: Boolean = false

    init {
        ensureScene()
    }

    /**
     * 确保场景存在并注册本屏渲染器。首次构造与 [added](关闭返回父屏后再次打开)
     * 都会调用:场景已存在(可复活保留)则复用,否则重建。
     */
    private fun ensureScene() {
        if (composeScene == null) {
            val scene = MinecraftComposeScene(width = 1, height = 1, density = density)
            // 复述系统:语义树变化(焦点迁移等)时标记,下一帧 extractRenderState 补触发
            // 原版朗读调度(不能在语义快照提交期直接触发,会递归)
            scene.onSemanticsChanged = {
                if (NarratedHelper.currentFocusedSemanticsId(composeScene) != lastFocusedSemanticsId) {
                    narrationPending = true
                }
            }
            scene.setContent(content)
            composeScene = scene
        }
        // T.24:setScreen 先 removed 旧屏再 init 新屏,注册顺序保证 active 指向当前屏
        ComposeGuiRenderer.register(composeScene!!.renderer)
    }

    /** T.25:关闭返回父屏后再次打开时复活场景(重新注册渲染器) */
    override fun added() {
        ensureScene()
        // T.25 修复:复活后复位可复活标记 —— 否则场景一旦被 open 标记为 reopenable 永不
        // 复位,最后从本屏退出回非 Compose 屏时 removed 不销毁场景,Recomposer/effect
        // 协程、窗口尺寸监听与整棵节点树挂起不释放(泄漏)。复位后除非再次作为父屏被
        // open 标记,否则下次 removed 正常关闭。
        reopenable = false
        super.added()
    }

    // ── 渲染 ──────────────────────────────────────────────────

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // 不调用 super:不渲染原版 widget 列表与默认背景,画面完全由 Compose 场景提供
        if (renderParentScreen && parent != null) {
            // T.25:渲染父屏(Compose 之下)。原版父屏走原版 GuiRenderState(原版
            // guiRenderer 先画,Compose 后画盖上面);Compose 父屏由其 renderFrame
            // 收集到自身渲染器,再并入本屏渲染器(元素顺序在前 = 画在下面)。
            parent.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, partialTick)
            if (parent is ComposeScreen) {
                parent.composeScene?.renderer?.let { parentRenderer ->
                    composeScene?.renderer?.absorbAndClear(parentRenderer)
                }
            }
        }
        composeScene?.renderFrame()
        // 复述系统:语义变化(Compose 内部焦点迁移)补触发原版朗读。extractRenderState
        // 帧内调用是安全的(不在语义快照提交期,不会递归);triggerImmediateNarration
        // 内部只在 Narrator.isActive() 或 DEBUG 时执行,静默无副作用。
        if (narrationPending) {
            narrationPending = false
            lastFocusedSemanticsId = NarratedHelper.currentFocusedSemanticsId(composeScene)
            triggerImmediateNarration(false)
        }
        // I9 指针图标:把 Compose 场景的光标请求并入原版 per-frame 光标管线
        // (extractor 构造时 pendingCursor = CursorType.DEFAULT,这里覆写请求值,
        // 帧末由原版 applyCursor → Window.selectCursor 生效,带去重、尊重
        // 原版「允许光标变化」设置项)。无请求时用 DEFAULT 与原版默认一致。
        graphics.requestCursor(composeScene?.desiredCursorType ?: CursorType.DEFAULT)
    }

    /**
     * T.24:空实现 —— 不渲染原版 Screen 的菜单背景遮罩
     * ([Screen.extractBackground] 默认走 extractBlurredBackground + extractMenuBackground,
     * 即模糊 + 半透明黑色遮罩)。Compose 内容自绘背景(业务背景色/图片),不需要原版遮罩。
     */
    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // 空实现:跳过原版菜单背景遮罩
    }

    // ── 鼠标输入(阶段 F)──────────────────────────────────────

    /**
     * 平台接入点的 MC IME 服务(文本输入桥接)。
     *
     * 平台开放点:platformContext 经 factory 可由下游替换,若自定义实现自持
     * [MinecraftTextInputService](默认即如此)这里 cast 成功;否则返回 null,
     * IME 组合态转发(isComposing/onCharTyped/onPreeditChanged)静默失效。
     */
    private val MinecraftComposeScene.imeService: MinecraftTextInputService?
        get() = platformContext.textInputService as? MinecraftTextInputService

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val consumed = composeScene?.sendPointerEvent(
            eventType = PointerEventType.Press,
            position = mousePosition,
            type = PointerType.Mouse,
            keyboardModifiers = event.buttonInfo.modifiers().toPointerKeyboardModifiers(),
            button = PointerButton(event.buttonInfo.button()),
        )
        return consumed?.anyMovementConsumed == true || super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val consumed = composeScene?.sendPointerEvent(
            eventType = PointerEventType.Release,
            position = mousePosition,
            type = PointerType.Mouse,
            keyboardModifiers = event.buttonInfo.modifiers().toPointerKeyboardModifiers(),
            button = PointerButton(event.buttonInfo.button()),
        )
        return consumed?.anyMovementConsumed == true || super.mouseReleased(event)
    }

    override fun mouseMoved(x: Double, y: Double) {
        composeScene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = mousePosition,
            type = PointerType.Mouse,
        )
        super.mouseMoved(x, y)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val consumed = composeScene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = mousePosition,
            type = PointerType.Mouse,
            keyboardModifiers = event.buttonInfo.modifiers().toPointerKeyboardModifiers(),
        )
        return consumed?.anyMovementConsumed == true || super.mouseDragged(event, dx, dy)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        val consumed =
            composeScene?.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = mousePosition,
                type = PointerType.Mouse,
                scrollDelta = scrollDelta(scrollX, scrollY),
            )
        return consumed?.anyMovementConsumed == true || super.mouseScrolled(x, y, scrollX, scrollY)
    }

    // ── 键盘输入(阶段 F)──────────────────────────────────────

    /**
     * 键盘按下转发到 Compose。
     *
     * IME 组合态例外:组合期间 Backspace/Delete/Esc 由系统输入法处理(缩短/清除组合串、
     * 取消组合),这里直接消费、不进入 Compose 文本处理——否则 Compose 的退格会删除组合
     * 文本并清除组合区,导致后续 preedit 更新在错误位置重新插入组合文本(与官方桌面 AWT
     * 组合期间按键不达应用的行为一致)。
     */
    override fun keyPressed(event: MCKeyEvent): Boolean {
        return composeScene?.imeService?.isComposing == true
                && event.key in IME_COMPOSITION_KEYS
                || composeScene?.sendKeyEvent(event.toCompose(KeyEventType.KeyDown)) == true
                || super.keyPressed(event)
    }

    override fun keyReleased(event: MCKeyEvent): Boolean {
        return composeScene?.imeService?.isComposing == true
                && event.key in IME_COMPOSITION_KEYS
                || composeScene?.sendKeyEvent(event.toCompose(KeyEventType.KeyUp)) == true
                || super.keyReleased(event)
    }

    /**
     * 文本输入(计划 I.1):MC 字符上屏(含中文输入法上屏)→ Compose typed KeyEvent。
     *
     * MC 的 charTyped 在字符上屏时被调用;Compose 侧字符输入走 [ComposeKeyEvent] 的
     * codePoint 字段(KeyEvent.isTypedEvent → TextFieldKeyEventHandler 插入文本),
     * 因此这里把 [CharacterEvent.codepoint] 直接构造成 typed 事件转发,无需 TextInputService。
     * 转发前先经 [LocalCharFilter](默认全放行,由业务方覆盖)。
     *
     * IME 提交语义:系统输入法的提交文本也经此路径上屏(MC GLFW 分支先触发字符回调再
     * 触发 preedit null),组合期间先通知 service 计数(组合结束时用于定位光标)。
     */
    @OptIn(InternalComposeUiApi::class)
    override fun charTyped(event: CharacterEvent): Boolean {
        val scene = composeScene ?: return false
        if (!scene.isCharAccepted(event.codepoint)) {
            return false
        }
        scene.imeService?.onCharTyped(event.codepoint)
        val typedEvent = ComposeKeyEvent(
            key = Key.Unknown,
            type = KeyEventType.KeyDown,
            codePoint = event.codepoint,
        )
        return scene.sendKeyEvent(typedEvent)
    }

    /**
     * IME 组合态(preedit)转发(实施计划 §3.2/§4-3):系统输入法的组合串变化(含提交/取消
     * 后的 null)转发到 [moe.forpleuvoir.compose_minecraft.platform.textinput.MinecraftTextInputService.onPreeditChanged],由 service 转换为
     * Compose EditCommand 写入编辑缓冲(下划线组合文本/组合区清理/光标定位)。
     */
    override fun preeditUpdated(event: PreeditEvent?): Boolean {
        composeScene?.imeService?.onPreeditChanged(event)
        return true
    }

    // ── 生命周期 ─────────────────────────────────────────────

    /**
     * T.25:关闭请求 —— 有父屏则自动打开父屏(与原版各 Screen 的 lastScreen 约定一致),
     * 无父屏走原版默认(setScreen(null):回游戏 HUD / 主菜单)。
     */
    override fun onClose() {
        if (parent != null) {
            Minecraft.getInstance().gui.setScreen(parent)
        } else {
            super.onClose()
        }
    }

    override fun removed() {
        // T.24:注销本屏渲染器(避免 gui 阶段继续提交已关闭的场景)
        composeScene?.let { ComposeGuiRenderer.unregister(it.renderer) }
        // T.25:可复活屏保留场景(关闭返回父屏后状态不丢),否则销毁
        if (!reopenable) {
            composeScene?.let { runCatching { it.close() } }
            composeScene = null
        }
        super.removed()
    }

    // ── 复述系统(Narration)桥接 ──────────────────────────────

    override fun updateNarratedWidget(output: NarrationElementOutput) {
        NarratedHelper.updateNarratedWidget(composeScene, mousePosition, output)
    }

    companion object {
        /**
         * IME 组合期间由系统输入法处理的键(GLFW 键码):
         * Backspace/Delete 缩短或清除组合串,Esc 取消组合。
         */
        private val IME_COMPOSITION_KEYS = intArrayOf(
            InputConstants.KEY_BACKSPACE,
            InputConstants.KEY_DELETE,
            InputConstants.KEY_ESCAPE,
        )

        /**
         * 关闭当前 Compose 屏幕(T.25 父屏幕语义):等价于对当前屏调用 [ComposeScreen.onClose]
         * —— 有父屏则自动 `setScreen(parent)` 返回,无父屏回游戏/主菜单(原版默认行为)。
         * 当前屏幕不是 [ComposeScreen](如原版屏幕)时返回 false,不做任何事。
         *
         * 用途:内容侧「返回」按钮 —— **关闭自己**而非重新 open 一个父屏实例
         * (重新 open 会丢父屏组合状态,且叠加多层屏幕)。
         */
        fun closeCurrent(): Boolean {
            val current = Minecraft.getInstance().gui.screen()
            if (current is ComposeScreen) {
                current.onClose()
                return true
            }
            return false
        }

        /**
         * 打开一个 Compose 屏幕(等价于原版 minecraft.gui.setScreen)。
         *
         * T.25:[parent] 默认取打开前的当前屏幕 —— 关闭时自动返回它(「父屏幕能力」);
         * 显式传 null 关闭回主菜单/游戏内。[renderParentScreen] 控制打开期间是否把
         * 父屏内容渲染在 Compose 之下。父屏为 [ComposeScreen] 时自动标记
         * [ComposeScreen.reopenable],保证返回后场景/状态复活。
         *
         * T.26:[density] 场景密度,默认 1f(1dp == 1 像素);传 >1f 放大 UI。
         *
         * 注意 [content] 是最后一个参数 —— 保持 `ComposeScreen.open { ... }`
         * trailing lambda 调用形式与早期版本兼容。
         *
         * @return 创建的 [ComposeScreen] 实例(可经 [ComposeScreen.renderParentScreen]
         * 运行时切换渲染父屏开关)。
         */
        fun open(
            parent: Screen? = null,
            renderParentScreen: Boolean = false,
            density: Float = 1f,
            content: @Composable () -> Unit,
        ): ComposeScreen {
            val resolvedParent = parent ?: Minecraft.getInstance().gui.screen()
            // 父屏是 ComposeScreen:替换前先标记可复活(removed 保留场景)
            (resolvedParent as? ComposeScreen)?.reopenable = true
            val screen = ComposeScreen(resolvedParent, renderParentScreen, density, content)
            Minecraft.getInstance().gui.setScreen(screen)
            return screen
        }
    }
}
