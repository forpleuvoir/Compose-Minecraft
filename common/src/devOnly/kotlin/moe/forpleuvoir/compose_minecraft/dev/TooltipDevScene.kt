package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.render.util.MinecraftGuiScale
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import moe.forpleuvoir.compose_minecraft.platform.ui.tooltip.TooltipLines
import moe.forpleuvoir.compose_minecraft.platform.ui.tooltip.TooltipPositionProvider
import moe.forpleuvoir.compose_minecraft.platform.ui.tooltip.TooltipPopup
import moe.forpleuvoir.compose_minecraft.platform.ui.tooltip.minecraftTooltip
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BundleContents

private class SlotSpec(val label: String, val lines: () -> TooltipLines)

/**
 * 原版 tooltip 插件测试(T.39):悬停物品格触发,popup 跟随鼠标但偏移避开鼠标位置,
 * 避免闪烁。
 */
@Composable
fun TooltipDevScene() {
    var density by remember { mutableStateOf(1f) }
    CompositionLocalProvider(LocalDensity provides Density(density)) {
        val mc = Minecraft.getInstance()
        val font = mc.font

        var mousePosition by remember { mutableStateOf(Offset.Zero) }
        var guiScaleEnabled by remember { mutableStateOf(false) }
        var hoveredSlot by remember { mutableStateOf<Int?>(null) }
        val tooltipKey = remember { Any() }

        val sword = remember { ItemStack(Items.DIAMOND_SWORD) }
        val diamondX64 = remember { ItemStack(Items.DIAMOND, 64) }
        val fullBundle = remember {
            val stack = ItemStack(Items.BUNDLE)
            val templates = (1..20).map { ItemStackTemplate(Items.DIAMOND, 64) }
            stack.set(DataComponents.BUNDLE_CONTENTS, BundleContents(templates))
            stack
        }
        val emptyBundle = remember { ItemStack(Items.BUNDLE) }
        val textLines = remember(font) {
            TooltipLines.fromLines(
                listOf(
                    Component.literal("").withStyle(Style.EMPTY.withColor(Color(0xFFAAAAAA))),
                    Component.translatable("item.minecraft.diamond_sword"),
                    Component.literal("通用文本 tooltip 测试行"),
                ),
                font = font
            )
        }

        val slots = remember(font, sword, diamondX64, fullBundle, emptyBundle, textLines) {
            listOf(
                SlotSpec("钻石剑", { TooltipLines.fromItem(sword, font) }),
                SlotSpec("钻石 ×64", { TooltipLines.fromItem(diamondX64, font) }),
                SlotSpec("满 bundle", { TooltipLines.fromItem(fullBundle, font) }),
                SlotSpec("空 bundle", { TooltipLines.fromItem(emptyBundle, font) }),
                SlotSpec("文本", { textLines }),
            )
        }

        // 只在悬浮对应的物品格时才渲染 tooltip
        val activeLines: TooltipLines? = hoveredSlot?.let { slots[it].lines() }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x00121212))
                // 全局鼠标位置跟踪(仅用于 popup 定位,不用于 hover 判定)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            mousePosition = change.position
                        }
                    }
                }
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    "原版 Tooltip 插件测试 (T.39) — 悬停物品格显示",
                    style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
                )
                BasicText(
                    "guiScale: " + (if (guiScaleEnabled) MinecraftGuiScale.current().toString() else "1 (1:1)"),
                    style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    slots.forEachIndexed { index, slot ->
                        TooltipSlot(
                            label = slot.label,
                            hovered = hoveredSlot == index,
                            onHover = { inside ->
                                if (inside) hoveredSlot = index else if (hoveredSlot == index) hoveredSlot = null
                            },
                        )
                    }

                    val interactionSource = remember { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()
                    Box(
                        modifier = Modifier
                            .padding(12.dp)
                            .background(if (isHovered) Color(0xFF2E7D32) else Color(0xFF37474F))
                            .hoverable(interactionSource)
                            .minecraftTooltip(TooltipLines.fromItem(ItemStack(Items.MELON)), guiScaleEnabled),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "Modifier 测试",
                            style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                        )
                    }
                }
                BasicText(
                    "guiScale 开关: ${if (guiScaleEnabled) "开" else "关"}",
                    modifier = Modifier
                        .background(if (guiScaleEnabled) Color(0xFF2E7D32) else Color(0xFF37474F))
                        .clickable { guiScaleEnabled = !guiScaleEnabled }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
                BasicText(
                    "密度 $density",
                    modifier = Modifier
                        .background(if (density == 2f) Color(0xFF2E7D32) else Color(0xFF37474F))
                        .clickable {
                            density = if (density == 1f) 2f else 1f
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
            }

            if (activeLines != null) {
                TooltipPopup(
                    key = tooltipKey,
                    visible = true,
                    lines = activeLines,
                    guiScaleEnabled = guiScaleEnabled,
                    density = density,
                    positionProvider = TooltipPositionProvider(
                        mouse = { mousePosition },
                    ),
                )
            }
        }
    }
}

/** 物品格:官方 [Modifier.hoverable] + [collectIsHoveredAsState] 检测悬停。 */
@Composable
private fun TooltipSlot(
    label: String,
    hovered: Boolean,
    onHover: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) { onHover(isHovered) }
    Box(
        modifier = Modifier
            .padding(12.dp)
            .background(if (hovered) Color(0xFF2E7D32) else Color(0xFF37474F))
            .hoverable(interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = Style.EMPTY.withColor(Color.White).toTextStyle(),
        )
    }
}