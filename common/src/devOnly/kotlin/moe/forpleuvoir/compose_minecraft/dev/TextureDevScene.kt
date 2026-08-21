package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.MinecraftItem
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.MinecraftTexture
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.drawItemStack
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.drawMinecraftTexture
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.roundToInt

/**
 * MC 原生渲染测试 (T.37):
 * 纹理/物品渲染测试。
 */
@Composable
fun TextureDevScene() {
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
                "MC 原生渲染测试 (T.37)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            Spacer(Modifier.height(8.dp))

            // ── 1. 纹理 ──
            SectionHeader("1. 纹理 — MinecraftTexture")
            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                MinecraftTexture(id("textures/block/dirt.png"), size = DpSize(64.dp, 64.dp))
                MinecraftTexture(id("textures/block/stone.png"), size = DpSize(64.dp, 64.dp))
                MinecraftTexture(id("textures/block/grass_block_top.png"), size = DpSize(64.dp, 64.dp))
                MinecraftTexture(id("textures/gui/container/generic_54.png"), size = DpSize(256.dp, 256.dp))
            }
            Spacer(Modifier.height(8.dp))

            // ── 2. DrawScope 扩展 ──
            SectionHeader("2. DrawScope.drawMinecraftTexture()")
            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    alpha = 0.25f
                }
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .border(1.dp, Color(0xFF607D8B))
                        .drawBehind {
                            drawMinecraftTexture(id("textures/block/dirt.png"))
                        }
                )
                Box(
                    Modifier
                        .size(64.dp)
                        .border(1.dp, Color(0xFF607D8B))
                        .drawBehind {
                            drawMinecraftTexture(id("textures/block/stone.png"))
                        }
                )
            }
            Spacer(Modifier.height(8.dp))

            // ── 3. 物品 ──
            SectionHeader("3. 物品 — MinecraftItem / drawItemStack")
            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    alpha = 0.5f
                }
            ) {
                val items = listOf(
                    "钻石" to Items.DIAMOND,
                    "苹果" to Items.APPLE,
                    "钻石剑" to Items.DIAMOND_SWORD,
                    "弓" to Items.BOW,
                    "金苹果" to Items.GOLDEN_APPLE,
                )
                for ((name, item) in items) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MinecraftItem(
                            stack = ItemStack(item),
                            size = DpSize(64.dp, 64.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        BasicText(name, style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle())
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── 4. DrawScope 物品扩展 ──
            SectionHeader("4. DrawScope.drawItemStack()")
            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .background(Color(0xFF263238))
                        .border(1.dp, Color(0xFF4CAF50))
                        .drawWithContent {
                            drawItemStack(mc.player?.mainHandItem ?: ItemStack(Items.DIAMOND))
                        }
                )
                Box(
                    Modifier
                        .size(64.dp)
                        .background(Color(0xFF263238))
                        .border(1.dp, Color(0xFF4CAF50))
                        .drawWithContent {
                            drawItemStack(ItemStack(Items.APPLE))
                        }
                )
                Box(
                    Modifier
                        .size(64.dp)
                        .background(Color(0xFF263238))
                        .border(1.dp, Color(0xFF4CAF50))
                        .drawWithContent {
                            drawItemStack(ItemStack(Items.DIAMOND_SWORD))
                        }
                )
            }
            Spacer(Modifier.height(8.dp))

            // ── 6. 可拖动弹出层(测试穿透) ──
            SectionHeader("6. 可拖动弹出层(测试穿透)")
            Spacer(Modifier.height(4.dp))

            var popupVisible by remember { mutableStateOf(false) }
            var popupOffset by remember { mutableStateOf(IntOffset(120, 80)) }
            var popupClickCount by remember { mutableStateOf(0) }
            var underClickCount by remember { mutableStateOf(0) }

            if (!popupVisible) {
                DevMenuButton(
                    title = "打开可拖动弹出层",
                    subtitle = "顶部条拖动移动弹层;弹层按钮/底层计数验证不穿透",
                    onClick = { popupVisible = true },
                )
            }

            if (popupVisible) {
                Popup(
                    popupPositionProvider = object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset = popupOffset
                    },
                    onDismissRequest = { popupVisible = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier
                            .background(Color(0xFF263238))
                            .border(1.dp, Color(0xFF4FC3F7))
                            .padding(8.dp)
                    ) {
                        // 拖动条
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF37474F))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        popupOffset = IntOffset(
                                            popupOffset.x + dragAmount.x.roundToInt(),
                                            popupOffset.y + dragAmount.y.roundToInt(),
                                        )
                                    }
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            BasicText(
                                "┈ 拖动我移动弹层 ┈",
                                modifier = Modifier.align(Alignment.Center),
                                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
                            )
                        }
                        BasicText(
                            "弹层内容;若点击穿透会触发底层计数",
                            style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Box(
                                Modifier
                                    .background(Color(0xFF455A64))
                                    .clickable { popupClickCount++ }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                BasicText(
                                    "弹层点击: $popupClickCount",
                                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .background(Color(0xFF8D6E63))
                                    .clickable { popupVisible = false }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                BasicText(
                                    "关闭",
                                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                                )
                            }
                        }
                    }
                }
            }

            // 底层可点内容:验证弹出层拖动/点击不穿透到底层
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(Color(0xFF455A64))
                        .clickable { underClickCount++ }
                ) {
                    MinecraftTexture(
                        id("textures/block/dirt.png"),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                BasicText(
                    "底层点击: $underClickCount (弹层应拦截,不增加)",
                    style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
                )
            }
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

private fun id(path: String): Identifier = Identifier.parse("minecraft:$path")

@Composable
private fun SectionHeader(text: String) {
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true).toTextStyle(),
    )
}