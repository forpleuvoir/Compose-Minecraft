package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

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
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            BasicText(
                "Compose Minecraft Dev Menu",
                style = Style.EMPTY.withColor(Color.White).withBold(true),
            )
            BasicText(
                "选择一项测试:",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)),
            )

            DevMenuButton(
                title = "文本字号测试 (Text / fontSize)",
                subtitle = "fontSize(sp) 驱动字号:8~48sp 阶梯、行高、窄宽换行、样式叠加",
                onClick = { ComposeScreen.open { TextDevScene() } },
            )

            DevMenuButton(
                title = "文本输入测试 (TextInput)",
                subtitle = "中英文/IME 上屏、光标移动、选区、剪贴板",
                onClick = { ComposeScreen.open { TextInputDevScene() } },
            )

            DevMenuButton(
                title = "样式与交互验证 (Style/Click/Focus/Scroll)",
                subtitle = "BasicText 样式矩阵、鼠标点击、焦点、滚轮",
                onClick = { ComposeScreen.open { StyleMatrixDevScene() } },
            )

            DevMenuButton(
                title = "滚动专项测试 (Scroll)",
                subtitle = "固定高度全宽列表,滚轮方向/速度/裁剪验证",
                onClick = { ComposeScreen.open { ScrollTestDevScene() } },
            )

            DevMenuButton(
                title = "图形绘制测试 (Geometry)",
                subtitle = "圆/椭圆/弧/圆角矩形/线/Path/点,填充与描边",
                onClick = { ComposeScreen.open { GeometryDevScene() } },
            )

            DevMenuButton(
                title = "旋转专项测试 (Rotation)",
                subtitle = "长文本/方块绕中心无限旋转,红十字/红点中心参考",
                onClick = { ComposeScreen.open { RotationTestDevScene() } },
            )

            DevMenuButton(
                title = "3D 透视测试 (Perspective 3D)",
                subtitle = "rotationX/rotationY 3D 透视,点击方块切换角度",
                onClick = { ComposeScreen.open { Perspective3DDevScene() } },
            )

            DevMenuButton(
                title = "图片管线测试 (Image)",
                subtitle = "程序生成位图/资源 PNG 解码/drawImageRect/变换组合",
                onClick = { ComposeScreen.open { ImageDevScene() } },
            )

            DevMenuButton(
                title = "图层快照测试 (GraphicsLayer toImageBitmap)",
                subtitle = "record 录制 → CPU 光栅化快照,与 GPU 回放对照",
                onClick = { ComposeScreen.open { BitmapDevScene() } },
            )

            DevMenuButton(
                title = "颜色滤镜测试 (ColorFilter)",
                subtitle = "ColorMatrix 反相/灰度、tint、lighting、图层级滤镜",
                onClick = { ComposeScreen.open { ColorFilterDevScene() } },
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
        BasicText(title, style = Style.EMPTY.withColor(Color.White).withBold(true))
        BasicText(subtitle, style = Style.EMPTY.withColor(Color(0xFF90A4AE)))
    }
}
