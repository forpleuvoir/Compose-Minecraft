package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.graphicsLayer
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 样式与交互验证屏幕(独立 ComposeScreen,由总菜单按钮打开)。
 *
 * 验证项:
 * - BasicText 样式矩阵:颜色/加粗/斜体/下划线/删除线/乱码/阴影/背景/组合;
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
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            // ── BasicText 样式矩阵 ──
            BasicText(
                "BasicText 样式矩阵:",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            BasicText("Default white (默认白色)", style = Style.EMPTY.toTextStyle())
            BasicText("Color (颜色)", style = Style.EMPTY.withColor(Color(0xFFFF5252)).toTextStyle())
            BasicText("Bold (加粗)", style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle())
            BasicText("Italic (斜体)", style = Style.EMPTY.withColor(Color.White).withItalic(true).toTextStyle())
            BasicText(
                "Underlined (下划线)",
                style = Style.EMPTY.withColor(Color.White).withUnderlined(true).toTextStyle(),
            )
            BasicText(
                "Strikethrough (删除线)",
                style = Style.EMPTY.withColor(Color.White).withStrikethrough(true).toTextStyle(),
            )
            BasicText(
                "Obfuscated (乱码)",
                style = Style.EMPTY.withColor(Color.White).withObfuscated(true).toTextStyle(),
            )
            BasicText(
                "Shadow (阴影)",
                style = Style.EMPTY.withColor(Color(0xFFFFF59D)).toTextStyle(),
            )
            BasicText(
                "Background (文本背景)",
                style = Style.EMPTY.withColor(Color.Black).toTextStyle(),
            )
            BasicText(
                "All: bold+italic+under+strike (组合)",
                style =
                    Style.EMPTY.withColor(Color(0xFFFFD54F))
                        .withBold(true)
                        .withItalic(true)
                        .withUnderlined(true)
                        .withStrikethrough(true)
                        .toTextStyle(),
            )

            // ── 鼠标点击验证 ──
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .background(Color(0xAA1E88E5))
                    .clickable { clickCount++ }
            ) {
                BasicText(
                    "Click me: $clickCount",
                    modifier = Modifier.padding(8.dp),
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
            }

            // ── 焦点 + 键盘验证 ──
            // 注意:clickable 自带焦点目标(AbstractClickableNode 内部 FocusableNode),
            // 不能叠加显式 .focusable(),否则同一元素有两个焦点目标,Tab 会在两者间循环
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .background(if (focusPanelFocused) Color(0xFF8E24AA) else Color(0xAA8E24AA))
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focusPanelFocused = it.isFocused
                        focusStateText = "$it"
                    }
                    .onKeyEvent {
                        focusedKey = "${it.key}"
                        // 不消费:让 Tab 继续进入焦点导航(handleFocusKeys),否则会被吞掉
                        false
                    }
                    .clickable { }
            ) {
                BasicText(
                    "Focus A: $focusStateText | Key: $focusedKey",
                    modifier = Modifier.padding(8.dp),
                    // T.19:scale 参数已移除 → fontSize(sp) 并入 style;0.85 * 16sp = 13.6sp
                    style = Style.EMPTY.withColor(Color.White).toTextStyle().merge(
                        TextStyle(fontSize = if (focusPanelFocused) 13.6.sp else 16.sp)
                    )
                )
            }

            // ── 第二个焦点目标(验证焦点转移)──
            var focusBFocused by remember { mutableStateOf(false) }
            // 聚焦时无限循环旋转(0° → 360°),便于观察旋转中心是否稳定
            val spinTransition = rememberInfiniteTransition(label = "focusSpin")
            val spinRotation by spinTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "focusSpinRotation",
            )
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .graphicsLayer {
                        alpha = if (focusBFocused) 1f else 0.75f
                    }
                    .background(if (focusBFocused) Color(0xFFEF6C00) else Color(0xFFEF6C00))
                    .onFocusChanged { focusBFocused = it.isFocused }
                    .focusable()
            ) {
                BasicText(
                    "AB",
                    modifier = Modifier
                        .padding(8.dp)
                        // 固定十字参考:画在文本中心(与旋转 pivot 同一点),不随文本旋转,
                        // 旋转时十字不动、文本绕十字转,用于直观确认旋转中心
                        .drawBehind {
                            drawLine(
                                color = Color.Red,
                                start = Offset(size.width / 2f, 0f),
                                end = Offset(size.width / 2f, size.height),
                                strokeWidth = 1f,
                            )
                            drawLine(
                                color = Color.Red,
                                start = Offset(0f, size.height / 2f),
                                end = Offset(size.width, size.height / 2f),
                                strokeWidth = 1f,
                            )
                        }
                        .graphicsLayer {
                            rotationZ = if (focusBFocused) spinRotation else 0f
                        }
                        // 随文本旋转的方向箭头(在 graphicsLayer 内):从中心向右 8px(短箭头)。
                        // 旋转时若尾端钉在红十字上、头端只画 8px 小圈 => 绕中心旋转;
                        // 若整个短箭头大幅移动 => 旋转中心错误。
                        .drawBehind {
                            drawLine(
                                color = Color.Yellow,
                                start = Offset(size.width / 2f, size.height / 2f),
                                end = Offset(size.width / 2f + 8f, size.height / 2f),
                                strokeWidth = 2f,
                            )
                        },
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
            }

            // ── 正方形自转对照:同样只写一个 rotationZ,40x40 方块绕中心转 ──
            // 方块短(边缘离中心仅 20px),旋转时"原地转"观感明显,无"公转感";
            // 与上方长文本(138px)对比可知:"围着中心转"的观感来自元素长度,不是多了一个旋转。
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(40.dp)
                    // 固定中心红点(在 graphicsLayer 外层,不随旋转):旋转时红点若不动 = 纯自转;
                    // 红点若移动 = 方块中心真的在公转(渲染异常)
                    .drawBehind {
                        drawCircle(
                            color = Color.Red,
                            radius = 3f,
                            center = Offset(size.width / 2f, size.height / 2f),
                        )
                    }
                    .graphicsLayer {
                        rotationZ = if (focusBFocused) spinRotation else 0f
                    }
                    .background(Color(0xFFFFEB3B))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // 对角线方向标记:随方块一起旋转
                    drawLine(
                        color = Color.Black,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2f,
                    )
                }
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
                    BasicText(
                        "scroll item $index",
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp).background(Color(0xFF2F0000)),
                        style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                    )
                }
            }
        }
    }
}
