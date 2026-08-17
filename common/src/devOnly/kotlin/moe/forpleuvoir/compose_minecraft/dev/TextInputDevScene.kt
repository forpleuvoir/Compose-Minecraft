package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalCharFilter
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

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
    val textFieldState = rememberTextFieldState("待到秋来九月八，我花开后百花杀。\n冲天香阵透长安，满城尽带黄金甲。")
    val filterFieldState = rememberTextFieldState()
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
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            BasicText(
                "文本输入测试",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "直接键入(英文/中文输入法上屏);退格/方向键/Home/End/Delete;\n" +
                        "Shift+方向键选区;Ctrl+C/V/X 剪贴板;输入超出宽度时水平滚动",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Row {
                BasicTextField(
                    state = textFieldState,
                    modifier =
                        Modifier
                            .width(220.dp)
                            .height(160.dp)
                            .focusRequester(focusRequester)
                            .background(Color(0xFF263238))
                            .padding(2.dp)
                    ,
                    textStyle = Style.EMPTY.withColor(Color.White),
                    cursorBrush = SolidColor(Color.White),
                    lineLimits = TextFieldLineLimits.Default,
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicText(
                    "text = ${textFieldState.text.toString().ifEmpty { "(empty)" }}",
                    style = Style.EMPTY.withColor(Color(0xFF80CBC4)).toTextStyle(),
                )
            }

            BasicText(
                "LocalCharFilter 演示(此框屏蔽节号 §):",
                modifier = Modifier.padding(top = 8.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
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
                    textStyle = Style.EMPTY.withColor(Color.White),
                    cursorBrush = SolidColor(Color.White),
                )
            }
            BasicText(
                "text = ${filterFieldState.text.toString().ifEmpty { "(empty)" }}",
                style = Style.EMPTY.withColor(Color(0xFF80CBC4)).toTextStyle(),
            )
        }
    }
}
