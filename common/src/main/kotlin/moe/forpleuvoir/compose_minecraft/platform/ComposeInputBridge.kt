package moe.forpleuvoir.compose_minecraft.platform

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.KeyEvent as MCKeyEvent

/**
 * MC 输入 → Compose 输入的桥接工具。
 *
 * 从 [ComposeScreen] 抽离:MC 的键盘/鼠标事件统一在这里转换为 Compose 事件结构,
 * 键码/修饰键的映射逻辑集中维护。
 */
object ComposeInputBridge {

    /** GLFW 滚轮一格(±1.0)对应的滚动像素(官方桌面 ≈53px/格;MC 原版列表语义 3 行偏慢,翻倍) */
    const val MC_SCROLL_NOTCH_PX = 54f

    /**
     * MC 滚轮事件 → Compose [Offset] 滚动量。
     * 方向:MC scrollY 正值 = 向上滚;Compose scrollDelta.y 正值 = 向上滚手势
     * (内容向下移动),两者同号,不需要取反。
     */
    fun scrollDelta(scrollX: Double, scrollY: Double): Offset = Offset(
        scrollX.toFloat() * MC_SCROLL_NOTCH_PX,
        scrollY.toFloat() * MC_SCROLL_NOTCH_PX,
    )

    /** MC 修饰键位标志 → Compose [PointerKeyboardModifiers]。 */
    fun Int.toPointerKeyboardModifiers(): PointerKeyboardModifiers = PointerKeyboardModifiers(
        isCtrlPressed = (this and InputConstants.MOD_CONTROL) != 0,
        isMetaPressed = (this and InputConstants.MOD_SUPER) != 0,
        isAltPressed = (this and InputConstants.MOD_ALT) != 0,
        isShiftPressed = (this and InputConstants.MOD_SHIFT) != 0,
    )

    /**
     * MC KeyEvent → Compose [ComposeKeyEvent](GLFW 键码 → 我们自己的 Compose [Key] 常量)。
     *
     * 修饰键来源:MC `KeyEvent.modifiers` 的位标志(InputWithModifiers 语义,
     * 非 macOS 下 Control 位 = 2、Shift 位 = 1、Alt 位 = 4),事件发生时已固定。
     */
    @OptIn(InternalComposeUiApi::class)
    fun MCKeyEvent.toCompose(type: KeyEventType): ComposeKeyEvent {
        return ComposeKeyEvent(
            key = glfwKeyToComposeKey(key),
            type = type,
            isCtrlPressed = hasControlDown(),
            isMetaPressed = (modifiers and InputConstants.MOD_SUPER) != 0,
            isShiftPressed = hasShiftDown(),
            isAltPressed = hasAltDown(),
        )
    }
}

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
