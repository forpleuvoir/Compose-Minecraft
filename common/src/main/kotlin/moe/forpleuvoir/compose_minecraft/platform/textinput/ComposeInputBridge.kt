package moe.forpleuvoir.compose_minecraft.platform.textinput

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.KeyEvent as MCKeyEvent

/**
 * MC 输入 → Compose 输入的桥接工具。
 *
 * 从 [moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen] 抽离:MC 的键盘/鼠标事件统一在这里转换为 Compose 事件结构,
 * 键码/修饰键的映射逻辑集中维护。
 */
object ComposeInputBridge {

    val scrollPx: Float
        get() = MC_SCROLL_NOTCH_PX * scaleFactor

    @Volatile
    var scaleFactor: Float = 1f

    /** 原版滚轮一格(±1.0)对应的滚动像素(官方桌面 ≈53px/格;MC 原版列表语义 3 行偏慢,翻倍) */
    private const val MC_SCROLL_NOTCH_PX = 54f

    /**
     * MC 滚轮事件 → Compose [Offset] 滚动量。
     * 方向:MC scrollY 正值 = 向上滚;Compose scrollDelta.y 正值 = 向上滚手势
     * (内容向下移动),两者同号,不需要取反。
     */
    fun scrollDelta(scrollX: Double, scrollY: Double): Offset = Offset(
        // 纵向滚轮同时写进横向分量:Compose 的 `scrollable` 只取自己轴向的那个分量
        // (横向容器取 x、纵向取 y),而鼠标只有一个纵向滚轮 —— 只写 y 的话,
        // 横向容器(如分类条)永远收不到滚轮,只能拖动。真·横向滚轮(触控板)优先用 scrollX。
        if (scrollX != 0.0) scrollX.toFloat() * scrollPx else scrollY.toFloat() * scrollPx,
        scrollY.toFloat() * scrollPx,
    )

    /**
     * MC 鼠标按钮号 → Compose [PointerButton] 索引。
     *
     * MC 侧按钮号是 [InputConstants] 常量(SDL 语义:左=1、中=2、右=3、4/5=侧键),
     * Compose [PointerButton] index 是 0 基(主=0、次=1、三=2、后退=3、前进=4),
     * 两者不同基,必须显式换算;未收录按钮按 index = 按钮号 - 1 兜底。
     */
    fun Int.toPointerButton(): PointerButton = when (this) {
        InputConstants.MOUSE_BUTTON_LEFT -> PointerButton.Primary
        InputConstants.MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
        InputConstants.MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
        InputConstants.MOUSE_BUTTON_4 -> PointerButton.Back
        InputConstants.MOUSE_BUTTON_5 -> PointerButton.Forward
        else -> PointerButton(this - 1)
    }

    /** MC 修饰键位标志 → Compose [PointerKeyboardModifiers]。 */
    fun Int.toPointerKeyboardModifiers(): PointerKeyboardModifiers = PointerKeyboardModifiers(
        isCtrlPressed = (this and InputConstants.MOD_CONTROL) != 0,
        isMetaPressed = (this and InputConstants.MOD_SUPER) != 0,
        isAltPressed = (this and InputConstants.MOD_ALT) != 0,
        isShiftPressed = (this and InputConstants.MOD_SHIFT) != 0,
    )

    /**
     * MC KeyEvent → Compose [ComposeKeyEvent](MC 键码 → 本平台自己的 Compose [Key] 常量)。
     *
     * 修饰键来源:MC `KeyEvent.modifiers` 的位标志(InputWithModifiers 语义,
     * 非 macOS 下 Control 位 = 2、Shift 位 = 1、Alt 位 = 4),事件发生时已固定。
     */
    @OptIn(InternalComposeUiApi::class)
    fun MCKeyEvent.toCompose(type: KeyEventType): ComposeKeyEvent {
        return ComposeKeyEvent(
            key = toComposeKey(key),
            type = type,
            isCtrlPressed = hasControlDown(),
            isMetaPressed = (modifiers and InputConstants.MOD_SUPER) != 0,
            isShiftPressed = hasShiftDown(),
            isAltPressed = hasAltDown(),
        )
    }
}

/**
 * MC 键码 → Compose [Key] 常量(经 [InputConstants] 取键码,不引用 AWT/LWJGL 类型)。
 *
 * [MCKeyEvent.key] 的数值是 [InputConstants] 键码;26.3 起输入后端为 SDL,该数值是
 * SDL scancode(如 A=4、0=39、Space=44),与 Compose [Key] 的 AWT VK 编码(如 A=65、
 * 0=48)不同值,故全部键走显式映射表;未收录的键返回 [Key.Unknown]。
 */
private fun toComposeKey(mcKey: Int): Key = mcKeyToComposeKey[mcKey] ?: Key.Unknown

/** MC 键码 → Compose [Key] 常量的显式映射表(仅收录 MC 键码与 Compose 编码不一致的键) */
private val mcKeyToComposeKey: Map<Int, Key> = buildMap {
    // ── 字母与主键盘数字(SDL scancode 与 Compose AWT VK 编码不同,逐个显式映射)──
    put(InputConstants.KEY_A, Key.A)
    put(InputConstants.KEY_B, Key.B)
    put(InputConstants.KEY_C, Key.C)
    put(InputConstants.KEY_D, Key.D)
    put(InputConstants.KEY_E, Key.E)
    put(InputConstants.KEY_F, Key.F)
    put(InputConstants.KEY_G, Key.G)
    put(InputConstants.KEY_H, Key.H)
    put(InputConstants.KEY_I, Key.I)
    put(InputConstants.KEY_J, Key.J)
    put(InputConstants.KEY_K, Key.K)
    put(InputConstants.KEY_L, Key.L)
    put(InputConstants.KEY_M, Key.M)
    put(InputConstants.KEY_N, Key.N)
    put(InputConstants.KEY_O, Key.O)
    put(InputConstants.KEY_P, Key.P)
    put(InputConstants.KEY_Q, Key.Q)
    put(InputConstants.KEY_R, Key.R)
    put(InputConstants.KEY_S, Key.S)
    put(InputConstants.KEY_T, Key.T)
    put(InputConstants.KEY_U, Key.U)
    put(InputConstants.KEY_V, Key.V)
    put(InputConstants.KEY_W, Key.W)
    put(InputConstants.KEY_X, Key.X)
    put(InputConstants.KEY_Y, Key.Y)
    put(InputConstants.KEY_Z, Key.Z)
    put(InputConstants.KEY_0, Key.Zero)
    put(InputConstants.KEY_1, Key.One)
    put(InputConstants.KEY_2, Key.Two)
    put(InputConstants.KEY_3, Key.Three)
    put(InputConstants.KEY_4, Key.Four)
    put(InputConstants.KEY_5, Key.Five)
    put(InputConstants.KEY_6, Key.Six)
    put(InputConstants.KEY_7, Key.Seven)
    put(InputConstants.KEY_8, Key.Eight)
    put(InputConstants.KEY_9, Key.Nine)
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
