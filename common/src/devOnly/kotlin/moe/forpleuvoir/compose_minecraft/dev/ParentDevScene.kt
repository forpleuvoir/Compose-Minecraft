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
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 父屏幕能力测试(T.25):从 dev 菜单进入本屏(parent = dev 菜单 ComposeScreen),
 * 验证:
 * 1. 「← 返回」或 Esc 关闭时自动 `setScreen(parent)` 回到 dev 菜单(而非主菜单/游戏内);
 * 2. 「渲染父屏」开关运行时切换 —— 开时半透明背景下方透出 dev 菜单内容
 *    (Compose 父屏经 absorbAndClear 并入本屏渲染器,画在 Compose 之下);
 * 3. 返回 dev 菜单后其场景/组合状态复活(reopenable,滚动位置等不丢)。
 */
@Composable
fun ParentDevSceneContent(
    renderParent: Boolean,
    onToggleRenderParent: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            // 开:半透明背景(下方透出父屏 dev 菜单);关:近不透明(下方无父屏内容)
            .background(if (renderParent) Color(0x80121212) else Color(0xCC121212))
    ) {
        Column(Modifier.padding(16.dp)) {
            BasicText(
                "父屏幕能力测试 (ParentScreen)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                if (renderParent) {
                    "渲染父屏:开 —— 半透明背景下方应透出 dev 菜单内容"
                } else {
                    "渲染父屏:关 —— 下方无父屏内容"
                },
                style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
            )
            DevMenuButton(
                title = "切换:渲染父屏 (Toggle renderParentScreen)",
                subtitle = "运行时开关,下方父屏内容 显示/隐藏",
                onClick = onToggleRenderParent,
            )
            DevMenuButton(
                title = "← 返回 (onClose → setScreen(parent))",
                subtitle = "关闭本屏,应自动打开父屏(dev 菜单),而非主菜单",
                onClick = onClose,
            )
        }
    }
}
