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
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.EntityDrawData
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McEntityPlugin
import net.minecraft.world.entity.Entity
import kotlin.math.roundToInt

/**
 * 在背景绘制一个 MC 实体(与 [Modifier.minecraftTexture] 同模式 —— 节点参与 DrawScope 管道,
 * 捕获 alpha/colorFilter/blendMode,[color] 为色调色)。
 */
@Stable
fun Modifier.minecraftEntity(
    entity: Entity,
    color: Color = Color.White,
): Modifier = this.then(
    MinecraftEntityElement(
        entity = entity,
        color = color,
    )
)

private class MinecraftEntityElement(
    private val entity: Entity,
    private val color: Color,
) : ModifierNodeElement<MinecraftEntityNode>() {

    override fun create(): MinecraftEntityNode = MinecraftEntityNode(entity = entity, color = color)

    override fun update(node: MinecraftEntityNode) {
        node.entity = entity
        node.color = color
        node.invalidateDraw()
    }

    override fun equals(other: Any?): Boolean =
        other is MinecraftEntityElement && other.entity == entity && other.color == color

    override fun hashCode(): Int {
        var result = entity.hashCode()
        result = 31 * result + color.hashCode()
        return result
    }
}

private class MinecraftEntityNode(
    var entity: Entity,
    var color: Color,
) : DrawModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        val mcCanvas = drawContext.canvas
        if (mcCanvas !is MinecraftCanvas) { drawContent(); return }

        val size = this.size.width.coerceAtMost(this.size.height).roundToInt()
        if (size <= 0) { drawContent(); return }

        val paint = buildPaint(color)

        mcCanvas.drawCommands.add(
            DrawCustomCommand(
                matrix = mcCanvas.currentMatrix.values.copyOf(),
                clip = mcCanvas.currentClip,
                paint = paint.toPaintSnapshot(),
                layer3D = null,
                tag = McEntityPlugin.TAG,
                data = EntityDrawData(entity = entity, size = size),
            )
        )

        drawContent()
    }
}