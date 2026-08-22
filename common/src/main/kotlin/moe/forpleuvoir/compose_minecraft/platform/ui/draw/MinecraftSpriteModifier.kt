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
import com.mojang.blaze3d.pipeline.RenderPipeline
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McTexturePlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.SpriteDrawData
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * 在背景绘制一个 MC 原版 GUI atlas 精灵图(与 [Modifier.minecraftTexture] 同模式 ——
 * 节点参与 [ContentDrawScope] 管道,经 [McTexturePlugin] 的 [SpriteDrawData] 分支渲染)。
 *
 * 缩放遵循原版 `GuiSpriteScaling` 元数据:stretch / tile / nine_slice(tooltip 背景 /
 * 原版按钮同一套语义),尺寸 = 节点布局尺寸(像素 1:1,不受原版 guiScale 影响)。
 *
 * [color] 为调制色(着色器色彩调制器),默认白色 = sprite 原色;透明度由
 * graphicsLayer / replayFrom 的 alphaMultiplier 经 paint 烘焙。
 */
@Stable
fun Modifier.minecraftSprite(
    location: Identifier,
    pipeline: RenderPipeline = RenderPipelines.GUI_TEXTURED,
    color: Color = Color.White,
): Modifier = this.then(
    MinecraftSpriteElement(
        location = location,
        pipeline = pipeline,
        color = color,
    )
)

private class MinecraftSpriteElement(
    private val location: Identifier,
    private val pipeline: RenderPipeline,
    private val color: Color,
) : ModifierNodeElement<MinecraftSpriteNode>() {

    override fun create(): MinecraftSpriteNode = MinecraftSpriteNode(
        location = location,
        pipeline = pipeline,
        color = color,
    )

    override fun update(node: MinecraftSpriteNode) {
        node.location = location
        node.pipeline = pipeline
        node.color = color
        node.invalidateDraw()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val o = other as? MinecraftSpriteElement ?: return false
        return location == o.location &&
                pipeline == o.pipeline &&
                color == o.color
    }

    override fun hashCode(): Int {
        var result = location.hashCode()
        result = 31 * result + pipeline.hashCode()
        result = 31 * result + color.hashCode()
        return result
    }
}

private class MinecraftSpriteNode(
    var location: Identifier,
    var pipeline: RenderPipeline,
    var color: Color,
) : DrawModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        val mcCanvas = drawContext.canvas
        if (mcCanvas !is MinecraftCanvas) {
            drawContent(); return
        }

        val size = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
        if (size.width <= 0 || size.height <= 0) {
            drawContent(); return
        }

        // paint:color = 调制色,alpha = 1f(由 replayFrom 的 alphaMultiplier 烘焙),
        // 与 minecraftTexture 同路(插件端经 context.paint 取 tintColor)
        val paint = buildPaint(color)

        mcCanvas.drawCommands.add(
            DrawCustomCommand(
                matrix = mcCanvas.currentMatrix.values.copyOf(),
                clip = mcCanvas.currentClip,
                paint = paint.toPaintSnapshot(),
                layer3D = null,
                tag = McTexturePlugin.TAG,
                data = SpriteDrawData(
                    location = location,
                    size = size,
                    pipeline = pipeline,
                ),
            )
        )

        drawContent()
    }
}
