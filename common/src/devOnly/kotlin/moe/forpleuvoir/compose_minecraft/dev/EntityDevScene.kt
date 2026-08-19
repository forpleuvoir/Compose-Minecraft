package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.minecraftEntity
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * 实体渲染测试 (T.37):MinecraftEntity Modifier —— 实体离屏 PIP 渲染,
 * 验证旋转(rotationX/rotationY)/色调色/graphicsLayer alpha/多实体共存。
 */
@Composable
fun EntityDevScene() {
    val mc = Minecraft.getInstance()
    val player = mc.player

    // 场景内收集世界中前几个可渲染生物(玩家 + 附近活体),供测试展示
    val sampleEntities = remember {
        val list = ArrayList<Entity>()
        if (player != null) list.add(player)
        mc.level?.let { level ->
            level.entitiesForRendering().filterIsInstance<LivingEntity>()
                .filter { it !== player && it.isAlive }
                .take(3)
                .forEach { list.add(it) }
        }
        list
    }

    var rotationY by remember { mutableStateOf(0f) }
    var rotationX by remember { mutableStateOf(0f) }
    var tintRed by remember { mutableStateOf(false) }

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
            BasicText(
                "实体渲染测试 (T.37)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            Spacer(Modifier.height(8.dp))

            // ── 1. 玩家实体 ──
            SectionHeader("1. 玩家 — minecraftEntity(player)")
            Spacer(Modifier.height(4.dp))

            if (player != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(96.dp)
                            .background(Color(0xFF263238))
                            .minecraftEntity(player)
                    )
                    Box(
                        Modifier
                            .size(96.dp)
                            .background(Color(0xFF263238))
                            .minecraftEntity(player, rotationY = rotationY)
                    )
                    Box(
                        Modifier
                            .size(96.dp)
                            .background(Color(0xFF263238))
                            .minecraftEntity(
                                player,
                                color = if (tintRed) Color(0xFFFF0000) else Color.White,
                                rotationX = rotationX,
                                rotationY = rotationY,
                            )
                    )
                }
                Spacer(Modifier.height(4.dp))

                // 旋转控制
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .background(Color(0xFF37474F))
                            .clickable { rotationY = (rotationY + PI.toFloat() / 6f) % (2f * PI.toFloat()) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        BasicText("rotationY += 30°", style = Style.EMPTY.withColor(Color.White).toTextStyle())
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .background(Color(0xFF37474F))
                            .clickable { rotationX = (rotationX + PI.toFloat() / 6f) % (2f * PI.toFloat()) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        BasicText("rotationX += 30°", style = Style.EMPTY.withColor(Color.White).toTextStyle())
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .background(if (tintRed) Color(0xFFB71C1C) else Color(0xFF37474F))
                            .clickable { tintRed = !tintRed }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        BasicText("色调色: ${if (tintRed) "红" else "白"}", style = Style.EMPTY.withColor(Color.White).toTextStyle())
                    }
                }
            } else {
                BasicText("未进入世界(无玩家)", style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle())
            }
            Spacer(Modifier.height(8.dp))

            // ── 2. 生物实体(世界中前 3 个活体) ──
            SectionHeader("2. 生物 — 世界中前 3 个活体")
            Spacer(Modifier.height(4.dp))

            if (sampleEntities.size <= 1) {
                BasicText(
                    "世界中未找到其他生物(可先召唤:/summon sheep)",
                    style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (entity in sampleEntities.drop(1)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(80.dp)
                                    .background(Color(0xFF263238))
                                    .minecraftEntity(entity)
                            )
                            Spacer(Modifier.height(2.dp))
                            BasicText(
                                entity.type.description.string,
                                style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── 3. 透明度(graphicsLayer alpha 透传 PIP 输出) ──
            SectionHeader("3. graphicsLayer alpha 透传")
            Spacer(Modifier.height(4.dp))

            if (player != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(80.dp)
                            .graphicsLayer { alpha = 0.25f }
                            .minecraftEntity(player)
                    )
                    Box(
                        Modifier
                            .size(80.dp)
                            .graphicsLayer { alpha = 0.6f }
                            .minecraftEntity(player, rotationY = PI.toFloat() / 4f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── 4. 可拖动遮挡弹层(弹出层盖住实体,验证遮挡/穿透) ──
            SectionHeader("4. 可拖动遮挡弹层")
            Spacer(Modifier.height(4.dp))

            var popupVisible by remember { mutableStateOf(false) }
            var popupOffset by remember { mutableStateOf(IntOffset(150, 200)) }
            var popupClickCount by remember { mutableStateOf(0) }
            var underClickCount by remember { mutableStateOf(0) }

            if (player != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(96.dp).background(Color(0xFF263238)).minecraftEntity(player))
                    Box(Modifier.size(96.dp).background(Color(0xFF263238)).minecraftEntity(player, rotationY = PI.toFloat() / 3f))
                    Box(Modifier.size(96.dp).background(Color(0xFF263238)).minecraftEntity(player, color = Color(0xFF4FC3F7)))
                }
                Spacer(Modifier.height(4.dp))

                if (!popupVisible) {
                    DevMenuButton(
                        title = "打开可拖动遮挡弹层",
                        subtitle = "拖动条移动弹层;弹层盖住实体,底层点击应被拦截",
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
                        onDismissRequest = {
                            popupVisible = false
                            popupClickCount = 0
                        },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Column(
                            Modifier
                                .border(2.dp, Color(0xFFFF9800))
                                .background(Color(0xFF263238))
                                .padding(8.dp)
                        ) {
                            // 拖动条:拖动移动弹层(像物品测试那样)
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
                                "弹层内容;拖到实体上方可遮挡实体。\n" +
                                        "若点击穿透会触发底层计数。",
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
                                    BasicText("弹层点击: $popupClickCount", style = Style.EMPTY.withColor(Color.White).toTextStyle())
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier
                                        .background(Color(0xFF8D6E63))
                                        .clickable { popupVisible = false }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    BasicText("关闭", style = Style.EMPTY.withColor(Color.White).toTextStyle())
                                }
                            }
                        }
                    }
                }

                // 底层可点实体:验证弹出层拖动/点击不穿透到实体
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(Color(0xFF455A64))
                            .clickable { underClickCount++ }
                            .minecraftEntity(player)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        "底层实体点击: $underClickCount (弹出层应拦截,不增加)",
                        style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
                    )
                }
            } else {
                BasicText("未进入世界(无玩家)", style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle())
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

@Composable
private fun SectionHeader(text: String) {
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true).toTextStyle(),
    )
}
