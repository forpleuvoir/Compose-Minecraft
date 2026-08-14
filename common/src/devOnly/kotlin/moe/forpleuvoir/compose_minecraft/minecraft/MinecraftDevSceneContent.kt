package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * 开发环境最小验证 Scene(阶段 D.4 + E + F + 焦点)。
 *
 * 全屏半透明背景 + 纯色矩形(底层)+ MC Font 文本,并附带输入与焦点验证面板:
 * - 点击计数区域:验证鼠标 Press/Release 转发(clickable);
 * - 焦点键盘区域:验证焦点系统(foundation focusable + FocusRequester 程序化请求焦点 +
 *   onFocusChanged 显示焦点状态 + onKeyEvent 接收按键);
 * - 滚动列表:验证滚轮转发(verticalScroll)。
 */
@Composable
fun MinecraftDevSceneContent() {
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
            .background(Color(0x66000000))
    ) {
        // 色块先绘制(底层),避免遮挡上方的输入验证面板
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFFE53935), topLeft = Offset(32f, 32f), size = Size(128f, 64f))
            drawRect(Color(0xFF43A047), topLeft = Offset(64f, 144f), size = Size(96f, 96f))
            drawRect(Color(0xFF1E88E5), topLeft = Offset(240f, 64f), size = Size(160f, 96f))
            // 圆角为 0 → 第一版按矩形渲染
            drawRoundRect(
                Color(0xFFFB8C00),
                topLeft = Offset(448f, 128f),
                size = Size(120f, 72f),
                cornerRadius = CornerRadius(0f, 0f),
            )
        }

        Column(Modifier.padding(16.dp)) {
            BasicText(
                "Compose Minecraft Platform",
                style = TextStyle(color = Color.White),
            )
            BasicText(
                "Stage F + Focus",
                style = TextStyle(color = Color(0xFFFFEB3B)),
            )

            // ── 鼠标点击验证 ──
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .height(48.dp)
                    .background(Color(0xAA1E88E5))
                    .clickable { clickCount++ }
            ) {
                BasicText(
                    "Click me: $clickCount",
                    modifier = Modifier.padding(8.dp),
                    style = TextStyle(color = Color.White),
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
                BasicText(
                    "Focus A: $focusStateText | Key: $focusedKey",
                    modifier = Modifier.padding(8.dp),
                    style = TextStyle(color = Color.White),
                )
            }

            // ── 第二个焦点目标(验证焦点转移)──
            // 注意:onFocusChanged 必须在 focusable 之前(FocusEvent 节点要是焦点目标的
            // 祖先才能收到事件;放在之后则收不到)
            var focusBFocused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .height(48.dp)
                    .background(if (focusBFocused) Color(0xFFEF6C00) else Color(0xAAEF6C00))
                    .onFocusChanged { focusBFocused = it.isFocused }
                    .focusable()
            ) {
                BasicText(
                    "Focus B: $focusBFocused (tab/direction to move)",
                    modifier = Modifier.padding(8.dp),
                    style = TextStyle(color = Color.White),
                )
            }

            // ── 滚轮验证 ──
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .height(160.dp)
                    .background(Color(0xAA00695C))
                    .verticalScroll(scrollState)
            ) {
                repeat(40) { index ->
                    BasicText(
                        "scroll item $index",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = TextStyle(color = Color.White),
                    )
                }
            }
        }
    }
}
