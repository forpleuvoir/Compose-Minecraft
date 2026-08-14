package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent as MCKeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * 原版 [Screen] 桥接(参考 ibuki_gourd 的 ComposeScreen 模式):
 * Compose 场景通过一个原版 Screen 挂入 Minecraft,渲染/层级/输入生命周期
 * 全部走原版屏幕机制,不依赖任何 loader 事件或 mixin 帧钩子。
 *
 * - 渲染:[extractRenderState] 每帧被调用,把场景绘制命令提交进当前帧 GuiRenderState;
 * - 输入:鼠标(点击/释放/移动/拖拽/滚轮)与键盘(按下/释放)从原版 Screen
 *   转发到 Compose 场景(阶段 F);场景坐标 = GUI 单位(密度 1),无需换算;
 * - 生命周期:[removed] 时关闭场景(任何被替换/关闭路径都会触发)。
 *
 * 尚未支持:文本输入(TextField/IME)、双击、Popup/Dialog 焦点层级。
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
        val consumed = composeScene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset(x.toFloat(), y.toFloat()),
            type = PointerType.Mouse,
            // MC scrollY 正值=向上滚;Compose Scroll 的 scrollDelta 与滚动方向一致(参考 ibuki 的取反)
            scrollDelta = Offset(scrollX.toFloat(), -scrollY.toFloat()),
        )
        return consumed.anyMovementConsumed || super.mouseScrolled(x, y, scrollX, scrollY)
    }

    // ── 键盘输入(阶段 F)──────────────────────────────────────

    override fun keyPressed(event: MCKeyEvent): Boolean =
        composeScene.sendKeyEvent(event.toCompose(KeyEventType.KeyDown)) || super.keyPressed(event)

    override fun keyReleased(event: MCKeyEvent): Boolean =
        composeScene.sendKeyEvent(event.toCompose(KeyEventType.KeyUp)) || super.keyReleased(event)

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

/** MC 修饰键位标志 → Compose [PointerKeyboardModifiers] */
private fun Int.toPointerKeyboardModifiers(): PointerKeyboardModifiers = PointerKeyboardModifiers(
    isCtrlPressed = (this and InputConstants.MOD_CONTROL) != 0,
    isMetaPressed = (this and InputConstants.MOD_SUPER) != 0,
    isAltPressed = (this and InputConstants.MOD_ALT) != 0,
    isShiftPressed = (this and InputConstants.MOD_SHIFT) != 0,
)

/** MC KeyEvent → Compose [ComposeKeyEvent](GLFW 键码 → 我们自己的 Compose [Key] 常量) */
@OptIn(InternalComposeUiApi::class)
private fun MCKeyEvent.toCompose(type: KeyEventType): ComposeKeyEvent = ComposeKeyEvent(
    key = glfwKeyToComposeKey(key),
    type = type,
    isCtrlPressed = hasControlDown(),
    isMetaPressed = (modifiers and InputConstants.MOD_SUPER) != 0,
    isShiftPressed = hasShiftDown(),
    isAltPressed = hasAltDown(),
)

/**
 * MC 键码 → Compose [Key] 常量(不引用任何 AWT/desktop/LWJGL 类型,键位语义对照
 * 我们拷贝的 Compose Key.kt 常量表,参考 ibuki_gourd 的映射关系):
 *
 * 键码来源说明:MC 输入层当前基于 GLFW 窗口(与 OpenGL/Vulkan 渲染后端无关),
 * [MCKeyEvent.key] 就是 GLFW 键码(带 [InputConstants.Value] 注解)。这里不直接
 * 绑定 LWJGL,而是使用 MC 自己的 [InputConstants] 抽象 —— 数值不变,但将来
 * MC 更换输入后端时 InputConstants 会随之更新,本映射无需改动。
 *
 * 1. 显式映射:编码不一致的键(导航/编辑/标点/修饰/功能/小键盘);
 * 2. 已验证直通:编码一致的键(A-Z、0-9 等,MC 键码 == Compose Key 编码),
 *    直接以键码构造 [Key],等价于对应常量;
 * 3. 安全降级:无法映射的键返回 [Key.Unknown]。
 */
private fun glfwKeyToComposeKey(mcKey: Int): Key {
    mcKeyToComposeKey[mcKey]?.let { return it }
    if (mcKey in InputConstants.KEY_A..InputConstants.KEY_Z) return Key(mcKey)
    if (mcKey in InputConstants.KEY_0..InputConstants.KEY_9) return Key(mcKey)
    return Key.Unknown
}

/** MC 键码 → Compose [Key] 常量的显式映射表(仅收录 MC 键码与 Compose 编码不一致的键) */
private val mcKeyToComposeKey: Map<Int, Key> = buildMap {
    // ── 导航与编辑键 ──
    put(InputConstants.KEY_UP, Key.DirectionUp)
    put(InputConstants.KEY_DOWN, Key.DirectionDown)
    put(InputConstants.KEY_LEFT, Key.DirectionLeft)
    put(InputConstants.KEY_RIGHT, Key.DirectionRight)
    put(InputConstants.KEY_HOME, Key.MoveHome)
    put(InputConstants.KEY_END, Key.MoveEnd)
    put(InputConstants.KEY_PAGEUP, Key.PageUp)
    put(InputConstants.KEY_PAGEDOWN, Key.PageDown)
    put(InputConstants.KEY_INSERT, Key.Insert)
    put(InputConstants.KEY_DELETE, Key.Delete)
    put(InputConstants.KEY_BACKSPACE, Key.Backspace)
    put(InputConstants.KEY_TAB, Key.Tab)
    put(InputConstants.KEY_RETURN, Key.Enter)
    put(InputConstants.KEY_NUMPADENTER, Key.NumPadEnter)

    // ── Escape ──
    put(InputConstants.KEY_ESCAPE, Key.Escape)

    // ── 与 Compose 编码不一致的标点键 ──
    put(InputConstants.KEY_APOSTROPHE, Key.Apostrophe)
    put(InputConstants.KEY_GRAVE, Key.Grave)

    // ── 修饰键 ──
    put(InputConstants.KEY_LSHIFT, Key.ShiftLeft)
    put(InputConstants.KEY_RSHIFT, Key.ShiftRight)
    put(InputConstants.KEY_LCONTROL, Key.CtrlLeft)
    put(InputConstants.KEY_RCONTROL, Key.CtrlRight)
    put(InputConstants.KEY_LALT, Key.AltLeft)
    put(InputConstants.KEY_RALT, Key.AltRight)
    put(InputConstants.KEY_LSUPER, Key.MetaLeft)
    put(InputConstants.KEY_RSUPER, Key.MetaRight)

    // ── 锁定与系统键 ──
    put(InputConstants.KEY_CAPSLOCK, Key.CapsLock)
    put(InputConstants.KEY_SCROLLLOCK, Key.ScrollLock)
    put(InputConstants.KEY_NUMLOCK, Key.NumLock)
    put(InputConstants.KEY_PRINTSCREEN, Key.PrintScreen)

    // ── 功能键 F1-F12 ──
    put(InputConstants.KEY_F1, Key.F1)
    put(InputConstants.KEY_F2, Key.F2)
    put(InputConstants.KEY_F3, Key.F3)
    put(InputConstants.KEY_F4, Key.F4)
    put(InputConstants.KEY_F5, Key.F5)
    put(InputConstants.KEY_F6, Key.F6)
    put(InputConstants.KEY_F7, Key.F7)
    put(InputConstants.KEY_F8, Key.F8)
    put(InputConstants.KEY_F9, Key.F9)
    put(InputConstants.KEY_F10, Key.F10)
    put(InputConstants.KEY_F11, Key.F11)
    put(InputConstants.KEY_F12, Key.F12)

    // ── 小键盘键 ──
    put(InputConstants.KEY_NUMPAD0, Key.NumPad0)
    put(InputConstants.KEY_NUMPAD1, Key.NumPad1)
    put(InputConstants.KEY_NUMPAD2, Key.NumPad2)
    put(InputConstants.KEY_NUMPAD3, Key.NumPad3)
    put(InputConstants.KEY_NUMPAD4, Key.NumPad4)
    put(InputConstants.KEY_NUMPAD5, Key.NumPad5)
    put(InputConstants.KEY_NUMPAD6, Key.NumPad6)
    put(InputConstants.KEY_NUMPAD7, Key.NumPad7)
    put(InputConstants.KEY_NUMPAD8, Key.NumPad8)
    put(InputConstants.KEY_NUMPAD9, Key.NumPad9)
    put(InputConstants.KEY_NUMPADCOMMA, Key.NumPadDot)
    put(InputConstants.KEY_NUMPADEQUALS, Key.NumPadEquals)
    put(InputConstants.KEY_MULTIPLY, Key.NumPadMultiply)
    put(InputConstants.KEY_ADD, Key.NumPadAdd)
    // 小键盘除/减:MC 未在 InputConstants 暴露对应 int 常量(仅注册表),按 GLFW 键码直写
    put(331, Key.NumPadDivide)
    put(333, Key.NumPadSubtract)

    // ── 其余编码不一致的标点/符号键 ──
    put(InputConstants.KEY_MINUS, Key.Minus)
    put(InputConstants.KEY_EQUALS, Key.Equals)
    put(InputConstants.KEY_LBRACKET, Key.LeftBracket)
    put(InputConstants.KEY_BACKSLASH, Key.Backslash)
    put(InputConstants.KEY_RBRACKET, Key.RightBracket)
    put(InputConstants.KEY_SEMICOLON, Key.Semicolon)
    put(InputConstants.KEY_COMMA, Key.Comma)
    put(InputConstants.KEY_PERIOD, Key.Period)
    put(InputConstants.KEY_SLASH, Key.Slash)
    put(InputConstants.KEY_SPACE, Key.Spacebar)
}
