package moe.forpleuvoir.compose_minecraft.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import moe.forpleuvoir.compose_minecraft.platform.ComposeInputBridge.scrollDelta
import moe.forpleuvoir.compose_minecraft.platform.ComposeInputBridge.toCompose
import moe.forpleuvoir.compose_minecraft.platform.ComposeInputBridge.toPointerKeyboardModifiers
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent as MCKeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * 原版 [Screen] 桥接(参考 ibuki_gourd 的 ComposeScreen 模式):
 * Compose 场景通过一个原版 Screen 挂入 Minecraft,渲染/层级/输入生命周期
 * 全部走原版屏幕机制,不依赖任何 loader 事件或 mixin 帧钩子。
 *
 * - 渲染:[extractRenderState] 每帧被调用,把场景绘制命令提交进当前帧 GuiRenderState;
 * - 输入:鼠标(点击/释放/移动/拖拽/滚轮)、键盘(按下/释放)与字符(charTyped,含中文
 *   输入法上屏)从原版 Screen 转发到 Compose 场景(阶段 F + 文本输入 I.1);
 *   场景坐标 = GUI 单位(密度 1),无需换算;
 * - 生命周期:[removed] 时关闭场景(任何被替换/关闭路径都会触发)。
 *
 * 尚未支持:双击、Popup/Dialog 焦点层级、IME preedit 组合态提示(候选窗口由系统输入法负责)。
 */
class ComposeScreen(
    content: @Composable () -> Unit,
) : Screen(Component.literal("Compose Screen")) {

    /** 本屏幕持有的 Compose 场景 */
    val composeScene: MinecraftComposeScene = MinecraftComposeScene(width = 1, height = 1)

    private var closed = false

    init {
        composeScene.setContent(content)
    }

    // ── 渲染 ──────────────────────────────────────────────────

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // 不调用 super:不渲染原版 widget 列表与默认背景,画面完全由 Compose 场景提供
        composeScene.renderFrame()
    }

    // ── 鼠标输入(阶段 F)──────────────────────────────────────

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val consumed = composeScene.sendPointerEvent(
            eventType = PointerEventType.Press,
            position = Offset(event.x.toFloat(), event.y.toFloat()),
            type = PointerType.Mouse,
            keyboardModifiers = event.buttonInfo.modifiers().toPointerKeyboardModifiers(),
            button = PointerButton(event.buttonInfo.button()),
        )
        return consumed.anyMovementConsumed || super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val consumed = composeScene.sendPointerEvent(
            eventType = PointerEventType.Release,
            position = Offset(event.x.toFloat(), event.y.toFloat()),
            type = PointerType.Mouse,
            keyboardModifiers = event.buttonInfo.modifiers().toPointerKeyboardModifiers(),
            button = PointerButton(event.buttonInfo.button()),
        )
        return consumed.anyMovementConsumed || super.mouseReleased(event)
    }

    override fun mouseMoved(x: Double, y: Double) {
        composeScene.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(x.toFloat(), y.toFloat()),
            type = PointerType.Mouse,
        )
        super.mouseMoved(x, y)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val consumed = composeScene.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(event.x.toFloat(), event.y.toFloat()),
            type = PointerType.Mouse,
            keyboardModifiers = event.buttonInfo.modifiers().toPointerKeyboardModifiers(),
        )
        return consumed.anyMovementConsumed || super.mouseDragged(event, dx, dy)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        val consumed =
            composeScene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = Offset(x.toFloat(), y.toFloat()),
                type = PointerType.Mouse,
                scrollDelta = scrollDelta(scrollX, scrollY),
            )
        return consumed.anyMovementConsumed || super.mouseScrolled(x, y, scrollX, scrollY)
    }

    // ── 键盘输入(阶段 F)──────────────────────────────────────

    override fun keyPressed(event: MCKeyEvent): Boolean =
        composeScene.sendKeyEvent(event.toCompose(KeyEventType.KeyDown)) ||
            super.keyPressed(event)

    override fun keyReleased(event: MCKeyEvent): Boolean =
        composeScene.sendKeyEvent(event.toCompose(KeyEventType.KeyUp)) || super.keyReleased(event)

    /**
     * 文本输入(计划 I.1):MC 字符上屏(含中文输入法上屏)→ Compose typed KeyEvent。
     *
     * MC 的 charTyped 在字符上屏时被调用;Compose 侧字符输入走 [ComposeKeyEvent] 的
     * codePoint 字段(KeyEvent.isTypedEvent → TextFieldKeyEventHandler 插入文本),
     * 因此这里把 [CharacterEvent.codepoint] 直接构造成 typed 事件转发,无需 TextInputService。
     * 转发前先经 [LocalCharFilter](默认全放行,由业务方覆盖)。
     */
    @OptIn(InternalComposeUiApi::class)
    override fun charTyped(event: CharacterEvent): Boolean {
        if (!composeScene.isCharAccepted(event.codepoint)) {
            return false
        }
        val typedEvent = ComposeKeyEvent(
            key = Key.Unknown,
            type = KeyEventType.KeyDown,
            codePoint = event.codepoint,
        )
        return composeScene.sendKeyEvent(typedEvent)
    }

    // ── 生命周期 ─────────────────────────────────────────────

    override fun onClose() {
        super.onClose()
        // 关闭请求:原版会继续走 setScreen(null) → removed(),真正的清理在 removed 中
    }

    override fun removed() {
        if (!closed) {
            closed = true
            runCatching { composeScene.close() }
        }
        super.removed()
    }

    companion object {
        /** 打开一个 Compose 屏幕(等价于原版 minecraft.gui.setScreen) */
        fun open(content: @Composable () -> Unit) {
            Minecraft.getInstance().gui.setScreen(ComposeScreen(content))
        }
    }
}
