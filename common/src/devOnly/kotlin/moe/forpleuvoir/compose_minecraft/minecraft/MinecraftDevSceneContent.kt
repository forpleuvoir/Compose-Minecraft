package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 开发环境总菜单(dev scene 主界面):
 *
 * 每项测试一个按钮,点击打开专属测试屏幕(独立的 ComposeScreen)。
 * 按钮为最简 Basic 层级外观(矩形 + 文本),无平台预设风格 —— 外观由业务方自定。
 */
@Composable
fun MinecraftDevSceneContent() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0121212))
    ) {
        Column(Modifier.padding(16.dp)) {
            McText(
                "Compose Minecraft Dev Menu",
                style = McTextStyle(color = Color.White, bold = true),
            )
            McText(
                "选择一项测试:",
                style = McTextStyle(color = Color(0xFFB0BEC5)),
            )

            DevMenuButton(
                title = "文本输入测试 (TextInput)",
                subtitle = "中英文/IME 上屏、光标移动、选区、剪贴板",
                onClick = { ComposeScreen.open { TextInputDevScene() } },
            )

            DevMenuButton(
                title = "样式与交互验证 (Style/Click/Focus/Scroll)",
                subtitle = "McText 样式矩阵、鼠标点击、焦点、滚轮",
                onClick = { ComposeScreen.open { StyleMatrixDevScene() } },
            )

            DevMenuButton(
                title = "滚动专项测试 (Scroll)",
                subtitle = "固定高度全宽列表,滚轮方向/速度/裁剪验证",
                onClick = { ComposeScreen.open { ScrollTestDevScene() } },
            )
        }
    }
}

/** 总菜单按钮:最简矩形 + 文本(Basic 层级,无平台预设外观) */
@Composable
internal fun DevMenuButton(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .background(Color(0xFF37474F))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        McText(title, style = McTextStyle(color = Color.White, bold = true))
        McText(subtitle, style = McTextStyle(color = Color(0xFF90A4AE)))
    }
}
