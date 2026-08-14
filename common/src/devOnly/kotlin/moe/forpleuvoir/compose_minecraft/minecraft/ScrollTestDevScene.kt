package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 滚动专项测试页(独立 ComposeScreen,由总菜单按钮打开)。
 *
 * 只做一件事:一个固定高度的全宽滚动列表,验证滚轮的方向、速度、裁剪与内容位移。
 * - 顶部实时显示 scrollState.value,滚动时数字必须变化;
 * - 列表 40 行,每行带底色与行号,超出的部分被裁掉;
 * - 滚动到两端后继续滚动会被正确钳制。
 */
@Composable
fun ScrollTestDevScene() {
    val scrollState = rememberScrollState()

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
                "滚动测试(scrollState.value = ${scrollState.value} / max = ${scrollState.maxValue})",
                style = McTextStyle(color = Color(0xFF80CBC4)),
            )
            McText(
                "滚轮上下滚动,上方数字必须变化;列表 40 行,超出部分被裁剪",
                style = McTextStyle(color = Color(0xFFB0BEC5)),
            )

            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(Color(0xFF263238))
                    .verticalScroll(scrollState)
            ) {
                repeat(40) { index ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .background(if (index % 2 == 0) Color(0xFF37474F) else Color(0xFF2F3E46))
                    ) {
                        McText(
                            "scroll item $index",
                            modifier = Modifier.padding(horizontal = 8.dp),
                            style = McTextStyle(color = Color.White),
                        )
                    }
                }
            }

            McText(
                "列表底部标记(滚动到末尾时应可见)",
                modifier = Modifier.padding(top = 8.dp),
                style = McTextStyle(color = Color(0xFFB0BEC5)),
            )
        }
    }
}
