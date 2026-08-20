package moe.forpleuvoir.compose_minecraft.platform.ui.draw

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DrawCustomCommand
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntSize
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.ItemStackDrawData
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McItemPlugin
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.Level
import kotlin.math.roundToInt

/**
 * 在背景绘制一个 MC 物品(与 [Modifier.minecraftTexture] 同模式 —— 节点参与 DrawScope 管道,
 * 捕获 alpha/colorFilter/blendMode,[color] 为色调色)。
 * 绘制尺寸 = 当前布局尺寸([DrawScope.size])。
 */
@Stable
fun Modifier.minecraftItem(
    stack: ItemStack,
    color: Color = Color.White,
    level: Level? = null,
    player: Player? = null,
    seed: Int = 0,
): Modifier = this.then(
    MinecraftItemElement(
        stack = stack,
        color = color,
        level = level,
        player = player,
        seed = seed,
    )
)

@Stable
fun Modifier.minecraftItem(
    stack: ItemLike,
    color: Color = Color.White,
    level: Level? = null,
    player: Player? = null,
    seed: Int = 0,
): Modifier = this.then(
    MinecraftItemElement(
        stack = ItemStack(stack),
        color = color,
        level = level,
        player = player,
        seed = seed,
    )
)


private class MinecraftItemElement(
    private val stack: ItemStack,
    private val color: Color,
    private val level: Level?,
    private val player: Player?,
    private val seed: Int,
) : ModifierNodeElement<MinecraftItemNode>() {

    override fun create(): MinecraftItemNode =
        MinecraftItemNode(stack = stack, color = color, level = level, player = player, seed = seed)

    override fun update(node: MinecraftItemNode) {
        node.stack = stack
        node.color = color
        node.level = level
        node.player = player
        node.seed = seed
        node.invalidateDraw()
    }

    override fun equals(other: Any?): Boolean =
        other is MinecraftItemElement &&
            other.stack == stack &&
            other.color == color &&
            other.level == level &&
            other.player == player &&
            other.seed == seed

    override fun hashCode(): Int {
        var result = stack.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + player.hashCode()
        result = 31 * result + seed
        return result
    }
}

private class MinecraftItemNode(
    var stack: ItemStack,
    var color: Color,
    var level: Level?,
    var player: Player?,
    var seed: Int,
) : DrawModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        val mcCanvas = drawContext.canvas
        if (mcCanvas !is MinecraftCanvas) {
            drawContent(); return
        }

        val w = this.size.width.roundToInt()
        val h = this.size.height.roundToInt()
        if (w <= 0 || h <= 0) {
            drawContent(); return
        }

        val paint = buildPaint(color)

        mcCanvas.drawCommands.add(
            DrawCustomCommand(
                matrix = mcCanvas.currentMatrix.values.copyOf(),
                clip = mcCanvas.currentClip,
                paint = paint.toPaintSnapshot(),
                layer3D = null,
                tag = McItemPlugin.TAG,
                data = ItemStackDrawData(stack = stack, size = IntSize(w, h), level = level, player = player, seed = seed),
            )
        )

        drawContent()
    }
}