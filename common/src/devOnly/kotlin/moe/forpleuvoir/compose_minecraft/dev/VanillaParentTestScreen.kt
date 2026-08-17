package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

/**
 * 原版父屏测试(T.25):一个**原版 Screen**(非 ComposeScreen),仅一个按钮。
 * 点击按钮 → [ComposeScreen.open](parent = 本屏, renderParentScreen = true)
 * —— 打开 Compose 屏幕并**渲染原版父屏**:Compose 内容(半透明背景)下方应透出
 * 原版按钮与菜单遮罩背景(模糊 + 半透明黑,原版 [Screen.extractBackground] 默认行为)。
 *
 * 验证「渲染父屏」对**原版父屏**的路径:父屏元素走原版 GuiRenderState
 * (原版 guiRenderer 先画),Compose 内容经 ComposeGuiRenderer 画在上面。
 *
 * 返回:Compose 子屏「← 返回」→ onClose → setScreen(本屏);本屏 Esc/关闭
 * 走原版默认(setScreen(null) → 主菜单,dev 场景自动打开逻辑兜底)。
 */
class VanillaParentTestScreen : Screen(Component.literal("Vanilla Parent Test")) {

    override fun init() {
        val btnWidth = 220
        val btnHeight = 20
        val holder = arrayOfNulls<ComposeScreen>(1)
        val button = Button.builder(
            Component.literal("打开 Compose 屏幕(渲染本屏)"),
            Button.OnPress {
                holder[0] = ComposeScreen.open(
                    parent = this@VanillaParentTestScreen,
                    renderParentScreen = true,
                ) {
                    VanillaParentComposeContent(
                        onClose = { holder[0]?.onClose() },
                    )
                }
            },
        ).bounds((width - btnWidth) / 2, (height - btnHeight) / 2, btnWidth, btnHeight).build()
        addRenderableWidget(button)
    }
}

/** Compose 子屏内容:半透明背景,下方透出原版父屏(按钮 + 菜单遮罩) */
@Composable
fun VanillaParentComposeContent(onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x80121212))
    ) {
        Column(Modifier.padding(16.dp)) {
            BasicText(
                "原版父屏渲染测试",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "父屏 = 原版 Screen,下方应透出:原版按钮 + 菜单遮罩背景(模糊/半透明黑)",
                style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
            )
            BasicText(
                "本屏背景半透明 0x80 —— 透出部分即原版父屏的渲染结果",
                style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
            )
            DevMenuButton(
                title = "← 返回 (onClose → setScreen(parent))",
                subtitle = "关闭本屏,应回到原版测试屏(按钮仍在)",
                onClick = onClose,
            )
        }
    }
}

/** dev 菜单入口:打开原版测试屏(替代当前 Compose 屏) */
fun openVanillaParentTest() {
    Minecraft.getInstance().gui.setScreen(VanillaParentTestScreen())
}
