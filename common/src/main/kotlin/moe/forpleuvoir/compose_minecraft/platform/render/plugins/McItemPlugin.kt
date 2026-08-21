package moe.forpleuvoir.compose_minecraft.platform.render.plugins

import moe.forpleuvoir.compose_minecraft.platform.render.CustomDrawContext
import moe.forpleuvoir.compose_minecraft.platform.render.state.ItemRenderState
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.paint.toArgb
import moe.forpleuvoir.compose_minecraft.platform.render.toMatrix3x2f
import moe.forpleuvoir.compose_minecraft.platform.render.toScreenRectangle
import androidx.compose.ui.unit.IntSize
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/** 物品渲染数据:经 [ItemRenderState] 提交,渲染器 prepare 阶段统一 atlas 烘焙。 */
data class ItemStackDrawData(
    val stack: ItemStack,
    val size: IntSize,
    val level: Level? = null,
    val player: Player? = null,
    val seed: Int = 0,
)

/**
 * 内置物品插件(T.37):构造扁平化的 [ItemRenderState](pose + 物品渲染状态 + 局部坐标 +
 * 尺寸 + 调制色 + scissor)经 [GuiCommandSink.addItem] 提交。**不经 GuiGraphicsExtractor**
 * (避免其 guiScale 管线),atlas 烘焙与 blit 由渲染器 prepare 阶段统一完成(1:1 像素)。
 */
object McItemPlugin : MinecraftRenderPlugin {

    val TAG: Identifier = Identifier.fromNamespaceAndPath("compose_minecraft", "item")

    override fun onDraw(tag: Identifier, data: Any?, context: CustomDrawContext): Boolean {
        if (tag != TAG) return false
        val dd = data as? ItemStackDrawData ?: return false
        if (dd.stack.isEmpty) return false
        val mc = Minecraft.getInstance()

        // 解析物品模型为渲染状态(GUI display context,与坐标/缩放无关)
        val state = TrackingItemStackRenderState()
        mc.itemModelResolver.updateForTopItem(
            state, dd.stack, ItemDisplayContext.GUI, dd.level, dd.player, dd.seed,
        )

        // 调制色(着色器色彩调制器):取自 paint,无 paint 时 -1(白色不调制)
        val color = context.paint?.let { p -> p.color.toArgb(p.alpha) } ?: -1

        // 提交物品渲染状态(局部坐标原点 + 1:1 pose + size 目标尺寸 + 调制色)
        context.sink.addItem(
            ItemRenderState(
                pose = context.matrix.toMatrix3x2f(),
                itemStackRenderState = state,
                x = 0,
                y = 0,
                size = dd.size,
                color = color,
                scissorArea = context.scissor?.toScreenRectangle(),
                identityKey = dd.stack.item,
            )
        )
        return true
    }
}