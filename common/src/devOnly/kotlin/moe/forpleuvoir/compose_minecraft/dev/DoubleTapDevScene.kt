package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style
import kotlin.math.roundToInt

/**
 * 双击测试屏幕:
 * 平台指针事件链(真实墙钟时间戳 + ViewConfiguration 300ms/40ms 双击常量)之上的
 * Compose 手势层自检 —— [detectTapGestures] 的 onTap/onDoubleTap 分发,以及
 * [BasicTextField] 双击选词。
 *
 * 语义说明:MC 原生 `MouseHandler` 的 doubleClick 标志(250ms、down-to-down、上次点击
 * 已消费)仅透传给 vanilla 子控件链;Compose 侧双击由手势检测器自行判定
 * (首次抬起后 40–300ms 内再次按下),两者实际使用基本无感差异。
 */
@Composable
fun DoubleTapDevScene() {
    var singleTaps by remember { mutableStateOf(0) }
    var doubleTaps by remember { mutableStateOf(0) }
    var lastAction by remember { mutableStateOf("(无)") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x00121212))
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ── 标题 ──
            BasicText(
                "双击测试",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            Spacer(Modifier.height(8.dp))

            // ── 1. detectTapGestures ──
            SectionHeader("1. detectTapGestures — onTap / onDoubleTap")
            Spacer(Modifier.height(4.dp))

            Box(
                Modifier
                    .size(width = 240.dp, height = 100.dp)
                    .background(Color(0xFF263238))
                    .border(1.dp, Color(0xFF4CAF50))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                singleTaps++
                                lastAction = "onTap (${offset.x.roundToInt()}, ${offset.y.roundToInt()})"
                            },
                            onDoubleTap = { offset ->
                                doubleTaps++
                                lastAction =
                                    "onDoubleTap (${offset.x.roundToInt()}, ${offset.y.roundToInt()})"
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "在此区域单击 / 双击",
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
            }
            Spacer(Modifier.height(4.dp))
            BasicText(
                "单击: $singleTaps    双击: $doubleTaps    最近动作: $lastAction",
                style = Style.EMPTY.withColor(Color(0xFF80CBC4)).toTextStyle(),
            )
            BasicText(
                "注:官方语义 —— 设置 onDoubleTap 后,单击回调会延迟约 300ms 触发(等待第二次按下判定)",
                style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle(),
            )
            Spacer(Modifier.height(8.dp))

            // ── 2. BasicTextField 双击选词 ──
            SectionHeader("2. BasicTextField 双击选词")
            Spacer(Modifier.height(4.dp))
            val state = rememberTextFieldState("double click any word to select it")
            BasicTextField(
                state = state,
                modifier = Modifier
                    .width(320.dp)
                    .background(Color(0xFF263238))
                    .border(1.dp, Color(0xFF607D8B))
                    .padding(4.dp),
                textStyle = Style.EMPTY.withColor(Color.White),
                cursorBrush = SolidColor(Color.White),
            )
            Spacer(Modifier.height(8.dp))

            // ── 返回按钮 ──
            Box(
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF37474F))
                    .clickable { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                BasicText(
                    "← 返回主菜单",
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true).toTextStyle(),
    )
}
