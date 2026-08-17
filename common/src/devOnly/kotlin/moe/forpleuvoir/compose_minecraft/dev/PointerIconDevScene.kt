package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 指针图标测试屏幕(I9,独立 ComposeScreen,由总菜单按钮打开)。
 *
 * 验证项:
 * - 四种标准图标悬停切换:Default(箭头)/ Crosshair(十字)/ Text(I 形)/ Hand(手型);
 * - 移出区域后恢复默认箭头(displayIcon(null) → PointerIcon.Default → ARROW);
 * - 真实组件联动:BasicTextField 悬停 → Text(I 形,foundation 自带
 *   `.pointerHoverIcon(PointerIcon.Text)`);链接文本悬停 → Hand;
 * - `overrideDescendants` 语义:父级 override=true 时子级图标被压制为父级图标,
 *   override=false 时子级图标优先;
 * - 光标变化走原版管线(GuiGraphicsExtractor.requestCursor → applyCursor →
 *   Window.selectCursor),若「允许光标变化」设置项关闭则全部回退为 OS 默认箭头。
 */
@Composable
fun PointerIconDevScene() {
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
                "指针图标测试 (PointerIcon / I9)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "把鼠标移入各色块,观察光标形状:Default=箭头,Crosshair=十字,\n" +
                        "Text=I 形,Hand=手型;移出区域应恢复默认箭头。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // 1. 四种标准图标:独立色块,悬停切换
            Row {
                CursorIconBox("Default", PointerIcon.Default, Color(0xFF37474F))
                Spacer(Modifier.width(8.dp))
                CursorIconBox("Crosshair", PointerIcon.Crosshair, Color(0xFF455A64))
                Spacer(Modifier.width(8.dp))
                CursorIconBox("Text", PointerIcon.Text, Color(0xFF546E7A))
                Spacer(Modifier.width(8.dp))
                CursorIconBox("Hand", PointerIcon.Hand, Color(0xFF607D8B))
            }

            BasicText(
                "2. 真实组件联动:下方输入框悬停 → I 形;链接文本悬停 → 手型",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // 2a. BasicTextField:foundation 自带 pointerHoverIcon(PointerIcon.Text)
            val textFieldState: TextFieldState = rememberTextFieldState("把鼠标悬停在这里 → I 形光标")
            BasicTextField(
                state = textFieldState,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(Color(0xFF263238))
                        .padding(2.dp),
                textStyle = Style.EMPTY.withColor(Color.White),
                cursorBrush = SolidColor(Color.White),
            )

            // 2b. 链接文本:显式 pointerHoverIcon(PointerIcon.Hand)(与 TextLinkScope 同款用法)
            BasicText(
                "这是一个可点击的链接 → 手型光标(点击无副作用)",
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .background(Color(0xFF263238))
                        .clickable(onClick = {})
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(4.dp),
                style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withUnderlined(true).toTextStyle(),
            )
            BasicText(
                "3. overrideDescendants 语义:左边父级 override=true,子级(手型)\n" +
                        "应被压制为父级十字;右边 override=false,子级手型优先",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // 3a. 父级 override=true:子级图标被压制
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(120.dp)
                    .background(Color(0xFF4E342E))
                    .pointerHoverIcon(PointerIcon.Crosshair, overrideDescendants = true)
            ) {
                Box(
                    Modifier
                        .padding(12.dp)
                        .size(40.dp)
                        .background(Color(0xFF8D6E63))
                        .pointerHoverIcon(PointerIcon.Hand)
                )
            }

            // 3b. 父级 override=false(默认):子级图标优先
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(120.dp)
                    .background(Color(0xFF1B5E20))
                    .pointerHoverIcon(PointerIcon.Crosshair, overrideDescendants = false)
            ) {
                Box(
                    Modifier
                        .padding(12.dp)
                        .size(40.dp)
                        .background(Color(0xFF66BB6A))
                        .pointerHoverIcon(PointerIcon.Hand)
                )
            }

            BasicText(
                "4. 普通 clickable(无 pointerHoverIcon):悬停应保持默认箭头",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            DevMenuButton(
                title = "普通按钮(无自定义光标)",
                subtitle = "Hover: default arrow",
                onClick = {},
            )
        }
    }
}

/** 单个光标演示块:色块 + 标签,悬停触发指定 [icon] */
@Composable
private fun CursorIconBox(iconName: String, icon: PointerIcon, color: Color) {
    Column(
        Modifier
            .padding(top = 4.dp)
            .size(width = 100.dp, height = 60.dp)
            .background(color)
            .pointerHoverIcon(icon)
            .padding(4.dp)
    ) {
        BasicText(
            iconName,
            style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
        )
        BasicText(
            "hover me",
            style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
        )
    }
}
