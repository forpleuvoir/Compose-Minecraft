package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.MinecraftSprite
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.drawMinecraftSprite
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.minecraftSprite
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

/**
 * 原版 GUI atlas 精灵图测试(R.1):
 * Composable / Modifier / DrawScope 三种入口 + 原版 GuiSpriteScaling 缩放(九宫格)+ 调制色。
 */
@Composable
fun SpriteDevScene() {
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
                "原版精灵图测试 (R.1)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            Spacer(Modifier.height(8.dp))

            // ── 1. Composable ──
            SectionHeader("1. Composable — MinecraftSprite(widget/button 九宫格)")
            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MinecraftSprite(sprite("widget/button"), size = DpSize(100.dp, 40.dp))
                MinecraftSprite(sprite("widget/button_disabled"), size = DpSize(100.dp, 40.dp))
                MinecraftSprite(sprite("widget/button_highlighted"), size = DpSize(64.dp, 32.dp))
            }
            Spacer(Modifier.height(8.dp))

            // ── 2. Modifier ──
            SectionHeader("2. Modifier.minecraftSprite()(背景层 + 红色调制)")
            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(width = 200.dp, height = 60.dp)
                        .border(1.dp, Color(0xFF607D8B))
                        .minecraftSprite(sprite("widget/button"))
                )
                Box(
                    Modifier
                        .size(width = 120.dp, height = 40.dp)
                        .border(1.dp, Color(0xFF607D8B))
                        .minecraftSprite(sprite("widget/button"), color = Color(0xFFFF6060))
                )
            }
            Spacer(Modifier.height(8.dp))

            // ── 3. DrawScope 扩展 ──
            SectionHeader("3. DrawScope.drawMinecraftSprite()(drawBehind)")
            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(width = 160.dp, height = 40.dp)
                        .border(1.dp, Color(0xFF4CAF50))
                        .drawBehind {
                            drawMinecraftSprite(sprite("widget/button"))
                        }
                )
                Box(
                    Modifier
                        .size(width = 80.dp, height = 24.dp)
                        .border(1.dp, Color(0xFF4CAF50))
                        .drawBehind {
                            drawMinecraftSprite(sprite("widget/slider"), color = Color(0xFF80DEEA))
                        }
                )
            }
            Spacer(Modifier.height(8.dp))

            // ── 4. 大尺寸九宫格 ──
            SectionHeader("4. 大尺寸九宫格(tooltip/background)")
            Spacer(Modifier.height(4.dp))

            MinecraftSprite(sprite("tooltip/background"), size = DpSize(300.dp, 140.dp))
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

/** GUI atlas sprite id(minecraft 命名空间) */
private fun sprite(path: String): Identifier = Identifier.parse("minecraft:$path")

@Composable
private fun SectionHeader(text: String) {
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true).toTextStyle(),
    )
}
