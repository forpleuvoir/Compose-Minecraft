package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.LocalCharFilter
import moe.forpleuvoir.compose_minecraft.platform.ui.McText
import moe.forpleuvoir.compose_minecraft.platform.ui.McTextStyle

/**
 * 文本输入测试屏幕(独立 ComposeScreen,由总菜单按钮打开)。
 *
 * 验证项:
 * - 英文/数字/标点直接输入;
 * - 中文输入法上屏(Mc charTyped → typed KeyEvent(codePoint));
 * - 退格 / 方向键 / Home / End / Delete;
 * - Shift+方向键选区;鼠标点击定位光标;displayPos 水平滚动(输入超出视口);
 * - Ctrl+C / Ctrl+V / Ctrl+X 剪贴板(MC KeyboardHandler → 系统剪贴板);
 * - LocalCharFilter 字符过滤开关(第二个输入框屏蔽节号 §)。
 */
@Composable
fun TextInputDevScene() {
    val textFieldState = remember { TextFieldState() }
    val filterFieldState = remember { TextFieldState() }
    val focusRequester = remember { FocusRequester() }

    // 打开即聚焦主输入框
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0121212))
    ) {
        Column(Modifier.padding(16.dp)) {
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            McText(
                "文本输入测试",
                style = McTextStyle(color = Color.White, bold = true),
            )
            McText(
                "直接键入(英文/中文输入法上屏);退格/方向键/Home/End/Delete;\n" +
                        "Shift+方向键选区;Ctrl+C/V/X 剪贴板;输入超出宽度时水平滚动",
                style = McTextStyle(color = Color(0xFFB0BEC5)),
            )

            BasicTextField(
                state = textFieldState,
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .width(220.dp)
                        .height(20.dp)
                        .focusRequester(focusRequester)
                        .background(Color(0xFF263238)),
                textStyle = McTextStyle(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                lineLimits = TextFieldLineLimits.SingleLine,
            )
            McText(
                "text = ${textFieldState.text.toString().ifEmpty { "(empty)" }}",
                style = McTextStyle(color = Color(0xFF80CBC4)),
            )

            McText(
                "LocalCharFilter 演示(此框屏蔽节号 §):",
                modifier = Modifier.padding(top = 8.dp),
                style = McTextStyle(color = Color(0xFFB0BEC5)),
            )
            val charFilter: (Int) -> Boolean = remember { { codepoint -> codepoint != 0x00A7 } }
            CompositionLocalProvider(LocalCharFilter provides charFilter) {
                BasicTextField(
                    state = filterFieldState,
                    modifier =
                        Modifier
                            .padding(top = 4.dp)
                            .width(220.dp)
                            .height(20.dp)
                            .background(Color(0xFF263238)),
                    textStyle = McTextStyle(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                )
            }
            McText(
                "text = ${filterFieldState.text.toString().ifEmpty { "(empty)" }}",
                style = McTextStyle(color = Color(0xFF80CBC4)),
            )
        }
    }
}
