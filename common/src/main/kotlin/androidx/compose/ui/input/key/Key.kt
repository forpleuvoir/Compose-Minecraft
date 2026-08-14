/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.input.key

import androidx.compose.ui.input.key.Key.Companion.Number
import androidx.compose.ui.util.packInts
import androidx.compose.ui.util.unpackInt1
import androidx.compose.ui.util.unpackInt2
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.KEY_LOCATION_LEFT
import java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD
import java.awt.event.KeyEvent.KEY_LOCATION_RIGHT
import java.awt.event.KeyEvent.KEY_LOCATION_STANDARD

// TODO(demin): implement most of key codes

/**
 * implementation of [Key] for Desktop.
 *
 * @param keyCode an integer code representing the key pressed. Note: This keycode can be used to
 * uniquely identify a hardware key. It is different from the native keycode.
 */
@JvmInline
value class Key(val keyCode: Long) {
    companion object {
        /** Unknown key. */
        val Unknown = Key(KeyEvent.VK_UNDEFINED)

        /**
         * System Home key.
         *
         * This key is handled by the framework and is never delivered to applications.
         */
        @Deprecated(
            "`Key.Home` was mapped to the keyboard \"Home\" key in error. It is meant to be the" +
                " \"system\" home key on Android, and should never be delivered to applications. " +
                "For the keyboard \"Home\" key use `Key.MoveHome`. For the Android system " +
                "\"Home\" key (unlikely to be needed), use `Key.SystemHome`",
            level = DeprecationLevel.ERROR,
        )
        val Home = Key(KeyEvent.VK_HOME)

        /**
         * System Home key.
         *
         * This key is handled by the framework and is never delivered to applications.
         */
        val SystemHome: Key = Key(-1000000184)

        /** Help key. */
        val Help = Key(KeyEvent.VK_HELP)

        /**
         * Up Arrow Key / Directional Pad Up key.
         *
         * May also be synthesized from trackball motions.
         */
        val DirectionUp = Key(KeyEvent.VK_UP)

        /**
         * Down Arrow Key / Directional Pad Down key.
         *
         * May also be synthesized from trackball motions.
         */
        val DirectionDown = Key(KeyEvent.VK_DOWN)

        /**
         * Left Arrow Key / Directional Pad Left key.
         *
         * May also be synthesized from trackball motions.
         */
        val DirectionLeft = Key(KeyEvent.VK_LEFT)

        /**
         * Right Arrow Key / Directional Pad Right key.
         *
         * May also be synthesized from trackball motions.
         */
        val DirectionRight = Key(KeyEvent.VK_RIGHT)

        /** '0' key. */
        val Zero = Key(KeyEvent.VK_0)

        /** '1' key. */
        val One = Key(KeyEvent.VK_1)

        /** '2' key. */
        val Two = Key(KeyEvent.VK_2)

        /** '3' key. */
        val Three = Key(KeyEvent.VK_3)

        /** '4' key. */
        val Four = Key(KeyEvent.VK_4)

        /** '5' key. */
        val Five = Key(KeyEvent.VK_5)

        /** '6' key. */
        val Six = Key(KeyEvent.VK_6)

        /** '7' key. */
        val Seven = Key(KeyEvent.VK_7)

        /** '8' key. */
        val Eight = Key(KeyEvent.VK_8)

        /** '9' key. */
        val Nine = Key(KeyEvent.VK_9)

        /** '+' key. */
        val Plus = Key(KeyEvent.VK_PLUS)

        /** '-' key. */
        val Minus = Key(KeyEvent.VK_MINUS)

        /** '*' key. */
        val Multiply = Key(KeyEvent.VK_MULTIPLY)

        /** '=' key. */
        val Equals = Key(KeyEvent.VK_EQUALS)

        /** '#' key. */
        val Pound = Key(KeyEvent.VK_NUMBER_SIGN)

        /** 'A' key. */
        val A = Key(KeyEvent.VK_A)

        /** 'B' key. */
        val B = Key(KeyEvent.VK_B)

        /** 'C' key. */
        val C = Key(KeyEvent.VK_C)

        /** 'D' key. */
        val D = Key(KeyEvent.VK_D)

        /** 'E' key. */
        val E = Key(KeyEvent.VK_E)

        /** 'F' key. */
        val F = Key(KeyEvent.VK_F)

        /** 'G' key. */
        val G = Key(KeyEvent.VK_G)

        /** 'H' key. */
        val H = Key(KeyEvent.VK_H)

        /** 'I' key. */
        val I = Key(KeyEvent.VK_I)

        /** 'J' key. */
        val J = Key(KeyEvent.VK_J)

        /** 'K' key. */
        val K = Key(KeyEvent.VK_K)

        /** 'L' key. */
        val L = Key(KeyEvent.VK_L)

        /** 'M' key. */
        val M = Key(KeyEvent.VK_M)

        /** 'N' key. */
        val N = Key(KeyEvent.VK_N)

        /** 'O' key. */
        val O = Key(KeyEvent.VK_O)

        /** 'P' key. */
        val P = Key(KeyEvent.VK_P)

        /** 'Q' key. */
        val Q = Key(KeyEvent.VK_Q)

        /** 'R' key. */
        val R = Key(KeyEvent.VK_R)

        /** 'S' key. */
        val S = Key(KeyEvent.VK_S)

        /** 'T' key. */
        val T = Key(KeyEvent.VK_T)

        /** 'U' key. */
        val U = Key(KeyEvent.VK_U)

        /** 'V' key. */
        val V = Key(KeyEvent.VK_V)

        /** 'W' key. */
        val W = Key(KeyEvent.VK_W)

        /** 'X' key. */
        val X = Key(KeyEvent.VK_X)

        /** 'Y' key. */
        val Y = Key(KeyEvent.VK_Y)

        /** 'Z' key. */
        val Z = Key(KeyEvent.VK_Z)

        /** ',' key. */
        val Comma = Key(KeyEvent.VK_COMMA)

        /** '.' key. */
        val Period = Key(KeyEvent.VK_PERIOD)

        /** Left Alt modifier key. */
        val AltLeft = Key(KeyEvent.VK_ALT, KEY_LOCATION_LEFT)

        /** Right Alt modifier key. */
        val AltRight = Key(KeyEvent.VK_ALT, KEY_LOCATION_RIGHT)

        /** Left Shift modifier key. */
        val ShiftLeft = Key(KeyEvent.VK_SHIFT, KEY_LOCATION_LEFT)

        /** Right Shift modifier key. */
        val ShiftRight = Key(KeyEvent.VK_SHIFT, KEY_LOCATION_RIGHT)

        /** Tab key. */
        val Tab = Key(KeyEvent.VK_TAB)

        /** Space key. */
        val Spacebar = Key(KeyEvent.VK_SPACE)

        /** Enter key. */
        val Enter = Key(KeyEvent.VK_ENTER)

        /**
         * Backspace key.
         *
         * Deletes characters before the insertion point, unlike [Delete].
         */
        val Backspace = Key(KeyEvent.VK_BACK_SPACE)

        /**
         * Delete key.
         *
         * Deletes characters ahead of the insertion point, unlike [Backspace].
         */
        val Delete = Key(KeyEvent.VK_DELETE)

        /** Escape key. */
        val Escape = Key(KeyEvent.VK_ESCAPE)

        /** Left Control modifier key. */
        val CtrlLeft = Key(KeyEvent.VK_CONTROL, KEY_LOCATION_LEFT)

        /** Right Control modifier key. */
        val CtrlRight = Key(KeyEvent.VK_CONTROL, KEY_LOCATION_RIGHT)

        /** Caps Lock key. */
        val CapsLock = Key(KeyEvent.VK_CAPS_LOCK)

        /** Scroll Lock key. */
        val ScrollLock = Key(KeyEvent.VK_SCROLL_LOCK)

        /** Left Meta modifier key. */
        val MetaLeft = Key(KeyEvent.VK_META, KEY_LOCATION_LEFT)

        /** Right Meta modifier key. */
        val MetaRight = Key(KeyEvent.VK_META, KEY_LOCATION_RIGHT)

        /** System Request / Print Screen key. */
        val PrintScreen = Key(KeyEvent.VK_PRINTSCREEN)

        /**
         * Home Movement key.
         *
         * Used for scrolling or moving the cursor around to the start of a line or to the top of a
         * list.
         */
        val MoveHome = Key(KeyEvent.VK_HOME)

        /**
         * End Movement key.
         *
         * Used for scrolling or moving the cursor around to the end of a line or to the bottom of a
         * list.
         */
        val MoveEnd = Key(KeyEvent.VK_END)

        /**
         * Insert key.
         *
         * Toggles insert / overwrite edit mode.
         */
        val Insert = Key(KeyEvent.VK_INSERT)

        /** Cut key. */
        val Cut = Key(KeyEvent.VK_CUT)

        /** Copy key. */
        val Copy = Key(KeyEvent.VK_COPY)

        /** Paste key. */
        val Paste = Key(KeyEvent.VK_PASTE)

        /** '`' (backtick) key. */
        val Grave = Key(KeyEvent.VK_BACK_QUOTE)

        /** '[' key. */
        val LeftBracket = Key(KeyEvent.VK_OPEN_BRACKET)

        /** ']' key. */
        val RightBracket = Key(KeyEvent.VK_CLOSE_BRACKET)

        /** '/' key. */
        val Slash = Key(KeyEvent.VK_SLASH)

        /** '\' key. */
        val Backslash = Key(KeyEvent.VK_BACK_SLASH)

        /** ';' key. */
        val Semicolon = Key(KeyEvent.VK_SEMICOLON)

        /** ''' (apostrophe) key. */
        val Apostrophe = Key(KeyEvent.VK_QUOTE)

        /** '@' key. */
        val At = Key(KeyEvent.VK_AT)

        /** Page Up key. */
        val PageUp = Key(KeyEvent.VK_PAGE_UP)

        /** Page Down key. */
        val PageDown = Key(KeyEvent.VK_PAGE_DOWN)

        /** F1 key. */
        val F1 = Key(KeyEvent.VK_F1)

        /** F2 key. */
        val F2 = Key(KeyEvent.VK_F2)

        /** F3 key. */
        val F3 = Key(KeyEvent.VK_F3)

        /** F4 key. */
        val F4 = Key(KeyEvent.VK_F4)

        /** F5 key. */
        val F5 = Key(KeyEvent.VK_F5)

        /** F6 key. */
        val F6 = Key(KeyEvent.VK_F6)

        /** F7 key. */
        val F7 = Key(KeyEvent.VK_F7)

        /** F8 key. */
        val F8 = Key(KeyEvent.VK_F8)

        /** F9 key. */
        val F9 = Key(KeyEvent.VK_F9)

        /** F10 key. */
        val F10 = Key(KeyEvent.VK_F10)

        /** F11 key. */
        val F11 = Key(KeyEvent.VK_F11)

        /** F12 key. */
        val F12 = Key(KeyEvent.VK_F12)

        /**
         * Num Lock key.
         *
         * This is the Num Lock key; it is different from [Number].
         * This key alters the behavior of other keys on the numeric keypad.
         */
        val NumLock = Key(KeyEvent.VK_NUM_LOCK, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '0' key. */
        val NumPad0 = Key(KeyEvent.VK_NUMPAD0, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '1' key. */
        val NumPad1 = Key(KeyEvent.VK_NUMPAD1, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '2' key. */
        val NumPad2 = Key(KeyEvent.VK_NUMPAD2, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '3' key. */
        val NumPad3 = Key(KeyEvent.VK_NUMPAD3, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '4' key. */
        val NumPad4 = Key(KeyEvent.VK_NUMPAD4, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '5' key. */
        val NumPad5 = Key(KeyEvent.VK_NUMPAD5, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '6' key. */
        val NumPad6 = Key(KeyEvent.VK_NUMPAD6, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '7' key. */
        val NumPad7 = Key(KeyEvent.VK_NUMPAD7, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '8' key. */
        val NumPad8 = Key(KeyEvent.VK_NUMPAD8, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '9' key. */
        val NumPad9 = Key(KeyEvent.VK_NUMPAD9, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '/' key (for division). */
        val NumPadDivide = Key(KeyEvent.VK_DIVIDE, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '*' key (for multiplication). */
        val NumPadMultiply = Key(KeyEvent.VK_MULTIPLY, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '-' key (for subtraction). */
        val NumPadSubtract = Key(KeyEvent.VK_SUBTRACT, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '+' key (for addition). */
        val NumPadAdd = Key(KeyEvent.VK_ADD, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '.' key (for decimals or digit grouping). */
        val NumPadDot = Key(KeyEvent.VK_PERIOD, KEY_LOCATION_NUMPAD)

        /** Numeric keypad ',' key (for decimals or digit grouping). */
        val NumPadComma = Key(KeyEvent.VK_COMMA, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Enter key. */
        val NumPadEnter = Key(KeyEvent.VK_ENTER, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '=' key. */
        val NumPadEquals = Key(KeyEvent.VK_EQUALS, KEY_LOCATION_NUMPAD)

        /** Numeric keypad '(' key. */
        val NumPadLeftParenthesis = Key(KeyEvent.VK_LEFT_PARENTHESIS, KEY_LOCATION_NUMPAD)

        /** Numeric keypad ')' key. */
        val NumPadRightParenthesis = Key(KeyEvent.VK_RIGHT_PARENTHESIS, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Up Arrow Key. */
        val NumPadDirectionUp = Key(KeyEvent.VK_UP, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Down Arrow Key. */
        val NumPadDirectionDown = Key(KeyEvent.VK_DOWN, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Left Arrow Key. */
        val NumPadDirectionLeft = Key(KeyEvent.VK_LEFT, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Right Arrow Key. */
        val NumPadDirectionRight = Key(KeyEvent.VK_RIGHT, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Home Key. */
        val NumPadMoveHome: Key = Key(KeyEvent.VK_HOME, KEY_LOCATION_NUMPAD)

        /** Numeric keypad End Key. */
        val NumPadMoveEnd = Key(KeyEvent.VK_END, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Page Up Key. */
        val NumPadPageUp = Key(KeyEvent.VK_PAGE_UP, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Page Down Key. */
        val NumPadPageDown = Key(KeyEvent.VK_PAGE_DOWN, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Insert Key. */
        val NumPadInsert = Key(KeyEvent.VK_INSERT, KEY_LOCATION_NUMPAD)

        /** Numeric keypad Delete Key. */
        val NumPadDelete: Key = Key(KeyEvent.VK_DELETE, KEY_LOCATION_NUMPAD)

        // Unsupported Keys. These keys will never be sent by the desktop. However we need unique
        // keycodes so that these constants can be used in a when statement without a warning.
        val SoftLeft = Key(-1000000001)
        val SoftRight = Key(-1000000002)
        val Back = Key(-1000000003)
        val NavigatePrevious = Key(-1000000004)
        val NavigateNext = Key(-1000000005)
        val NavigateIn = Key(-1000000006)
        val NavigateOut = Key(-1000000007)
        val SystemNavigationUp = Key(-1000000008)
        val SystemNavigationDown = Key(-1000000009)
        val SystemNavigationLeft = Key(-1000000010)
        val SystemNavigationRight = Key(-1000000011)
        val Call = Key(-1000000012)
        val EndCall = Key(-1000000013)
        val DirectionCenter = Key(-1000000014)
        val DirectionUpLeft = Key(-1000000015)
        val DirectionDownLeft = Key(-1000000016)
        val DirectionUpRight = Key(-1000000017)
        val DirectionDownRight = Key(-1000000018)
        val VolumeUp = Key(-1000000019)
        val VolumeDown = Key(-1000000020)
        val Power = Key(-1000000021)
        val Camera = Key(-1000000022)
        val Clear = Key(-1000000023)
        val Symbol = Key(-1000000024)
        val Browser = Key(-1000000025)
        val Envelope = Key(-1000000026)
        val Function = Key(-1000000027)
        val Break = Key(-1000000028)
        val Number = Key(-1000000031)
        val HeadsetHook = Key(-1000000032)
        val Focus = Key(-1000000033)
        val Menu = Key(-1000000034)
        val Notification = Key(-1000000035)
        val Search = Key(-1000000036)
        val PictureSymbols = Key(-1000000037)
        val SwitchCharset = Key(-1000000038)
        val ButtonA = Key(-1000000039)
        val ButtonB = Key(-1000000040)
        val ButtonC = Key(-1000000041)
        val ButtonX = Key(-1000000042)
        val ButtonY = Key(-1000000043)
        val ButtonZ = Key(-1000000044)
        val ButtonL1 = Key(-1000000045)
        val ButtonR1 = Key(-1000000046)
        val ButtonL2 = Key(-1000000047)
        val ButtonR2 = Key(-1000000048)
        val ButtonThumbLeft = Key(-1000000049)
        val ButtonThumbRight = Key(-1000000050)
        val ButtonStart = Key(-1000000051)
        val ButtonSelect = Key(-1000000052)
        val ButtonMode = Key(-1000000053)
        val Button1 = Key(-1000000054)
        val Button2 = Key(-1000000055)
        val Button3 = Key(-1000000056)
        val Button4 = Key(-1000000057)
        val Button5 = Key(-1000000058)
        val Button6 = Key(-1000000059)
        val Button7 = Key(-1000000060)
        val Button8 = Key(-1000000061)
        val Button9 = Key(-1000000062)
        val Button10 = Key(-1000000063)
        val Button11 = Key(-1000000064)
        val Button12 = Key(-1000000065)
        val Button13 = Key(-1000000066)
        val Button14 = Key(-1000000067)
        val Button15 = Key(-1000000068)
        val Button16 = Key(-1000000069)
        val Forward = Key(-1000000070)
        val MediaPlay = Key(-1000000071)
        val MediaPause = Key(-1000000072)
        val MediaPlayPause = Key(-1000000073)
        val MediaStop = Key(-1000000074)
        val MediaRecord = Key(-1000000075)
        val MediaNext = Key(-1000000076)
        val MediaPrevious = Key(-1000000077)
        val MediaRewind = Key(-1000000078)
        val MediaFastForward = Key(-1000000079)
        val MediaClose = Key(-1000000080)
        val MediaAudioTrack = Key(-1000000081)
        val MediaEject = Key(-1000000082)
        val MediaTopMenu = Key(-1000000083)
        val MediaSkipForward = Key(-1000000084)
        val MediaSkipBackward = Key(-1000000085)
        val MediaStepForward = Key(-1000000086)
        val MediaStepBackward = Key(-1000000087)
        val MicrophoneMute = Key(-1000000088)
        val VolumeMute = Key(-1000000089)
        val Info = Key(-1000000090)
        val ChannelUp = Key(-1000000091)
        val ChannelDown = Key(-1000000092)
        val ZoomIn = Key(-1000000093)
        val ZoomOut = Key(-1000000094)
        val Tv = Key(-1000000095)
        val Window = Key(-1000000096)
        val Guide = Key(-1000000097)
        val Dvr = Key(-1000000098)
        val Bookmark = Key(-1000000099)
        val Captions = Key(-1000000100)
        val Settings = Key(-1000000101)
        val TvPower = Key(-1000000102)
        val TvInput = Key(-1000000103)
        val SetTopBoxPower = Key(-1000000104)
        val SetTopBoxInput = Key(-1000000105)
        val AvReceiverPower = Key(-1000000106)
        val AvReceiverInput = Key(-1000000107)
        val ProgramRed = Key(-1000000108)
        val ProgramGreen = Key(-1000000109)
        val ProgramYellow = Key(-1000000110)
        val ProgramBlue = Key(-1000000111)
        val AppSwitch = Key(-1000000112)
        val LanguageSwitch = Key(-1000000113)
        val MannerMode = Key(-1000000114)
        val Toggle2D3D = Key(-1000000125)
        val Contacts = Key(-1000000126)
        val Calendar = Key(-1000000127)
        val Music = Key(-1000000128)
        val Calculator = Key(-1000000129)
        val ZenkakuHankaru = Key(-1000000130)
        val Eisu = Key(-1000000131)
        val Muhenkan = Key(-1000000132)
        val Henkan = Key(-1000000133)
        val KatakanaHiragana = Key(-1000000134)
        val Yen = Key(-1000000135)
        val Ro = Key(-1000000136)
        val Kana = Key(-1000000137)
        val Assist = Key(-1000000138)
        val BrightnessDown = Key(-1000000139)
        val BrightnessUp = Key(-1000000140)
        val Sleep = Key(-1000000141)
        val WakeUp = Key(-1000000142)
        val SoftSleep = Key(-1000000143)
        val Pairing = Key(-1000000144)
        val LastChannel = Key(-1000000145)
        val TvDataService = Key(-1000000146)
        val VoiceAssist = Key(-1000000147)
        val TvRadioService = Key(-1000000148)
        val TvTeletext = Key(-1000000149)
        val TvNumberEntry = Key(-1000000150)
        val TvTerrestrialAnalog = Key(-1000000151)
        val TvTerrestrialDigital = Key(-1000000152)
        val TvSatellite = Key(-1000000153)
        val TvSatelliteBs = Key(-1000000154)
        val TvSatelliteCs = Key(-1000000155)
        val TvSatelliteService = Key(-1000000156)
        val TvNetwork = Key(-1000000157)
        val TvAntennaCable = Key(-1000000158)
        val TvInputHdmi1 = Key(-1000000159)
        val TvInputHdmi2 = Key(-1000000160)
        val TvInputHdmi3 = Key(-1000000161)
        val TvInputHdmi4 = Key(-1000000162)
        val TvInputComposite1 = Key(-1000000163)
        val TvInputComposite2 = Key(-1000000164)
        val TvInputComponent1 = Key(-1000000165)
        val TvInputComponent2 = Key(-1000000166)
        val TvInputVga1 = Key(-1000000167)
        val TvAudioDescription = Key(-1000000168)
        val TvAudioDescriptionMixingVolumeUp = Key(-1000000169)
        val TvAudioDescriptionMixingVolumeDown = Key(-1000000170)
        val TvZoomMode = Key(-1000000171)
        val TvContentsMenu = Key(-1000000172)
        val TvMediaContextMenu = Key(-1000000173)
        val TvTimerProgramming = Key(-1000000174)
        val StemPrimary = Key(-1000000175)
        val Stem1 = Key(-1000000176)
        val Stem2 = Key(-1000000177)
        val Stem3 = Key(-1000000178)
        val AllApps = Key(-1000000179)
        val Refresh = Key(-1000000180)
        val ThumbsUp = Key(-1000000181)
        val ThumbsDown = Key(-1000000182)
        val ProfileSwitch = Key(-1000000183)
}

    override fun toString(): String {
        return "Key: ${KeyEvent.getKeyText(nativeKeyCode)}"
    }
}

/**
 * Creates instance of [Key].
 *
 * @param nativeKeyCode represents this key as defined in [java.awt.event.KeyEvent]
 * @param nativeKeyLocation represents the location of key as defined in [java.awt.event.KeyEvent]
 */
fun Key(nativeKeyCode: Int, nativeKeyLocation: Int = KEY_LOCATION_STANDARD): Key {
    // Only 3 bits are required for nativeKeyLocation.
    return Key(packInts(nativeKeyLocation, nativeKeyCode))
}

/**
 * The native keycode corresponding to this [Key].
 */
val Key.nativeKeyCode: Int
    get() = unpackInt2(keyCode)

/**
 * The native location corresponding to this [Key].
 */
val Key.nativeKeyLocation: Int
    get() = unpackInt1(keyCode)
