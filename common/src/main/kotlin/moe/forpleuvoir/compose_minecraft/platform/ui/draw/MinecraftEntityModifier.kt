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
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.world.entity.Entity
import org.joml.Quaternionf
import kotlin.math.roundToInt

/**
 * 在背景绘制一个 MC 实体(与 [Modifier.minecraftTexture] 同模式 —— 节点参与 DrawScope 管道,
 * 捕获 alpha/colorFilter/blendMode,[color] 为色调色)。
 *
 * @param rotationX X 轴旋转(弧度,正 = 前倾),配合 [rotationY] 构成实体朝向。
 * @param rotationY Y 轴旋转(弧度,正 = 向右转),为 0 时实体正面朝前。
 * @param fill 模型占渲染方块高度的比例(缺省 0.85),调小可给方块顶部腾出空间。
 * @param offsetY 模型在方块内的纵向位移(格,负值下沉)。
 * @param cameraAngle 覆写相机角度(缺省 null = 不覆写),用于整体倾斜视角。
 * @param renderState 直接使用调用方准备好的渲染状态(缺省 null = 由 [entity] 现场抽取)。
 * @param rotation 覆写整体旋转四元数(缺省 null = 用 [rotationX] / [rotationY] 现拼)。
 */
@Stable
fun Modifier.minecraftEntity(
    entity: Entity,
    color: Color = Color.White,
    rotationX: Float = 0f,
    rotationY: Float = 0f,
    fill: Float = 0.85f,
    offsetY: Float = 0f,
    cameraAngle: Quaternionf? = null,
    renderState: EntityRenderState? = null,
    rotation: Quaternionf? = null,
): Modifier = this.then(
    MinecraftEntityElement(
        entity = entity,
        color = color,
        rotationX = rotationX,
        rotationY = rotationY,
        fill = fill,
        offsetY = offsetY,
        cameraAngle = cameraAngle,
        renderState = renderState,
        rotation = rotation,
    )
)

private class MinecraftEntityElement(
    private val entity: Entity,
    private val color: Color,
    private val rotationX: Float,
    private val rotationY: Float,
    private val fill: Float,
    private val offsetY: Float,
    private val cameraAngle: Quaternionf?,
    private val renderState: EntityRenderState?,
    private val rotation: Quaternionf?,
) : ModifierNodeElement<MinecraftEntityNode>() {

    override fun create(): MinecraftEntityNode =
        MinecraftEntityNode(entity = entity, color = color, rotationX = rotationX, rotationY = rotationY, fill = fill, offsetY = offsetY, cameraAngle = cameraAngle, renderState = renderState, rotation = rotation)

    override fun update(node: MinecraftEntityNode) {
        node.entity = entity
        node.color = color
        node.rotationX = rotationX
        node.rotationY = rotationY
        node.fill = fill
        node.offsetY = offsetY
        node.cameraAngle = cameraAngle
        node.renderState = renderState
        node.rotation = rotation
        node.invalidateDraw()
    }

    override fun equals(other: Any?): Boolean =
        other is MinecraftEntityElement && other.entity == entity && other.color == color &&
            other.rotationX == rotationX && other.rotationY == rotationY && other.fill == fill &&
            other.offsetY == offsetY && other.cameraAngle == cameraAngle

    override fun hashCode(): Int {
        var result = entity.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + rotationX.hashCode()
        result = 31 * result + rotationY.hashCode()
        result = 31 * result + fill.hashCode()
        result = 31 * result + offsetY.hashCode()
        result = 31 * result + (cameraAngle?.hashCode() ?: 0)
        return result
    }
}

private class MinecraftEntityNode(
    var entity: Entity,
    var color: Color,
    var rotationX: Float,
    var rotationY: Float,
    var fill: Float,
    var offsetY: Float,
    var cameraAngle: Quaternionf?,
    var renderState: EntityRenderState?,
    var rotation: Quaternionf?,
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
                data = EntityDrawData(
                    entity = entity,
                    size = size,
                    color = -1, // 色调色经 paint 传递(与物品一致:节点 buildPaint 已含 color)
                    rotationX = rotationX,
                    rotationY = rotationY,
                    fill = fill,
                    offsetY = offsetY,
                    cameraAngle = cameraAngle,
                    renderState = renderState,
                    rotation = rotation,
                ),
            )
        )

        drawContent()
    }
}