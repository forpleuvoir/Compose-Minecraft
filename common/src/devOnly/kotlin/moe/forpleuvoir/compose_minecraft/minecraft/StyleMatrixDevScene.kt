package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp

/**
 * 样式与交互验证屏幕(独立 ComposeScreen,由总菜单按钮打开)。
 *
 * 验证项:
 * - McText 样式矩阵:颜色/加粗/斜体/下划线/删除线/乱码/阴影/背景/组合;
 * - 鼠标点击(clickable 计数);
 * - 焦点系统(focusable + FocusRequester + onFocusChanged + onKeyEvent);
 * - 滚轮(verticalScroll)。
 */
@Composable
fun StyleMatrixDevScene() {
    var clickCount by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    var focusPanelFocused by remember { mutableStateOf(false) }
    var focusStateText by remember { mutableStateOf("(waiting focus)") }
    var focusedKey by remember { mutableStateOf("(none)") }
    val scrollState = rememberScrollState()

    // 组合完成后把焦点交给焦点面板(验证程序化 requestFocus)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0121212))
    ) {
        // 色块先绘制(底层),避免遮挡上方的验证面板
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFFE53935), topLeft = Offset(32f, 32f), size = Size(128f, 64f))
            drawRect(Color(0xFF43A047), topLeft = Offset(64f, 144f), size = Size(96f, 96f))
            drawRect(Color(0xFF1E88E5), topLeft = Offset(240f, 64f), size = Size(160f, 96f))
            drawRoundRect(
                Color(0xFFFB8C00),
                topLeft = Offset(448f, 128f),
                size = Size(120f, 72f),
                cornerRadius = CornerRadius(0f, 0f),
            )
        }

        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            // ── McText 样式矩阵 ──
            McText(
                "McText 样式矩阵:",
                style = McTextStyle(color = Color(0xFFB0BEC5)),
            )
            McText("Default white (默认白色)", style = McTextStyle())
            McText("Color (颜色)", style = McTextStyle(color = Color(0xFFFF5252)))
            McText("Bold (加粗)", style = McTextStyle(color = Color.White, bold = true))
            McText("Italic (斜体)", style = McTextStyle(color = Color.White, italic = true))
            McText(
                "Underlined (下划线)",
                style = McTextStyle(color = Color.White, underlined = true),
            )
            McText(
                "Strikethrough (删除线)",
                style = McTextStyle(color = Color.White, strikethrough = true),
            )
            McText(
                "Obfuscated (乱码)",
                style = McTextStyle(color = Color.White, obfuscated = true),
            )
            McText(
                "Shadow (阴影)",
                style = McTextStyle(color = Color(0xFFFFF59D), shadow = true),
            )
            McText(
                "Background (文本背景)",
                style = McTextStyle(color = Color.Black, background = Color(0xFF90CAF9)),
            )
            McText(
                "All: bold+italic+under+strike (组合)",
                style =
                    McTextStyle(
                        color = Color(0xFFFFD54F),
                        bold = true,
                        italic = true,
                        underlined = true,
                        strikethrough = true,
                    ),
            )

            // ── 鼠标点击验证 ──
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .height(48.dp)
                    .background(Color(0xAA1E88E5))
                    .clickable { clickCount++ }
            ) {
                McText(
                    "Click me: $clickCount",
                    modifier = Modifier.padding(8.dp),
                    style = McTextStyle(color = Color.White),
                )
            }

            // ── 焦点 + 键盘验证 ──
            // 注意:clickable 自带焦点目标(AbstractClickableNode 内部 FocusableNode),
            // 不能叠加显式 .focusable(),否则同一元素有两个焦点目标,Tab 会在两者间循环
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .height(48.dp)
                    .background(if (focusPanelFocused) Color(0xFF8E24AA) else Color(0xAA8E24AA))
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focusPanelFocused = it.isFocused
                        focusStateText = "$it"
                    }
                    .onKeyEvent {
                        focusedKey = "${it.key}"
                        // 不消费:让 Tab/方向键继续进入焦点导航(handleFocusKeys),否则会被吞掉
                        false
                    }
                    .clickable { }
            ) {
                McText(
                    "Focus A: $focusStateText | Key: $focusedKey",
                    modifier = Modifier.padding(8.dp),
                    style = McTextStyle(color = Color.White),
                )
            }

            // ── 第二个焦点目标(验证焦点转移)──
            var focusBFocused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .height(48.dp)
                    .background(if (focusBFocused) Color(0xFFEF6C00) else Color(0xAAEF6C00))
                    .onFocusChanged { focusBFocused = it.isFocused }
                    .focusable()
            ) {
                McText(
                    "Focus B: $focusBFocused (tab/direction to move)",
                    modifier = Modifier.padding(8.dp),
                    style = McTextStyle(color = Color.White),
                )
            }

            // ── 滚轮验证 ──
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(Color(0xAA00695C))
                    .verticalScroll(rememberScrollState())
            ) {
                repeat(40) { index ->
                    McText(
                        "scroll item $index",
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp).background(Color(0xFF2F0000)),
                        style = McTextStyle(color = Color.White),
                    )
                }
            }
        }
    }
}
