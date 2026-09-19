package moe.forpleuvoir.compose_minecraft.platform.ui.draw

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DrawCustomCommand
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.MinecraftPaint
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntSize
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.textures.FilterMode
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.Corner
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McTexturePlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.TextureDrawData
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.UVMapping
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * 在背景绘制一个 MC 原生纹理(与 [Modifier.background] 同模式 —— 节点参与 [DrawScope] 管道,
 * 捕获当前绘制状态:alpha 经 [graphicsLayer] / [replayFrom] 的 [alphaMultiplier] 烘焙进 paint,
 * [color] 为调制色(着色器色彩调制器),默认白色 = 纹理原色)。
 */
@Stable
fun Modifier.minecraftTexture(
    textureId: Identifier,
    pipeline: RenderPipeline = RenderPipelines.GUI_TEXTURED,
    filterMode: FilterMode? = null,
    uv: UVMapping = UVMapping.Full,
    corner: Corner = Corner.Unspecified,
    tileSize: IntSize? = null,
    color: Color = Color.White,
): Modifier = this.then(
    MinecraftTextureElement(
        textureId = textureId,
        pipeline = pipeline,
        filterMode = filterMode,
        uv = uv,
        corner = corner,
        tileSize = tileSize,
        color = color,
    )
)

private class MinecraftTextureElement(
    private val textureId: Identifier,
    private val pipeline: RenderPipeline,
    private val filterMode: FilterMode?,
    private val uv: UVMapping,
    private val corner: Corner,
    private val tileSize: IntSize?,
    private val color: Color,
) : ModifierNodeElement<MinecraftTextureNode>() {

    override fun create(): MinecraftTextureNode = MinecraftTextureNode(
        textureId = textureId,
        pipeline = pipeline,
        filterMode = filterMode,
        uv = uv,
        corner = corner,
        tileSize = tileSize,
        color = color,
    )

    override fun update(node: MinecraftTextureNode) {
        node.textureId = textureId
        node.pipeline = pipeline
        node.filterMode = filterMode
        node.uv = uv
        node.corner = corner
        node.tileSize = tileSize
        node.color = color
        node.invalidateDraw()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val o = other as? MinecraftTextureElement ?: return false
        return textureId == o.textureId &&
                pipeline == o.pipeline &&
                filterMode == o.filterMode &&
                uv == o.uv &&
                corner == o.corner &&
                tileSize == o.tileSize &&
                color == o.color
    }

    override fun hashCode(): Int {
        var result = textureId.hashCode()
        result = 31 * result + pipeline.hashCode()
        result = 31 * result + (filterMode?.hashCode() ?: 0)
        result = 31 * result + uv.hashCode()
        result = 31 * result + corner.hashCode()
        result = 31 * result + (tileSize?.hashCode() ?: 0)
        result = 31 * result + color.hashCode()
        return result
    }
}

private class MinecraftTextureNode(
    var textureId: Identifier,
    var pipeline: RenderPipeline,
    var filterMode: FilterMode?,
    var uv: UVMapping,
    var corner: Corner,
    var tileSize: IntSize?,
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

        // 创建画笔:color = 调制色(着色器色彩调制器),alpha = 1f(由 replayFrom 的 alphaMultiplier 烘焙)
        val paint = buildPaint(color)

        mcCanvas.drawCommands.add(
            DrawCustomCommand(
                matrix = mcCanvas.currentMatrix.values.copyOf(),
                clip = mcCanvas.currentClip,
                paint = paint.toPaintSnapshot(),
                layer3D = null,
                tag = McTexturePlugin.TAG,
                data = TextureDrawData(
                    textureId = textureId,
                    size = size,
                    pipeline = pipeline,
                    filterMode = filterMode,
                    uv = uv,
                    corner = corner,
                    tileSize = tileSize,
                ),
            )
        )

        drawContent()
    }
}

/**
 * 构建画笔:color = 色调色,alpha = 1f(由 replayFrom 的 alphaMultiplier 烘焙),
 * colorFilter/blendMode 从 graphicsLayer 透传。供 texture/item/entity 三个 modifier 复用。
 */
fun DrawScope.buildPaint(color: Color): MinecraftPaint =
    MinecraftPaint(
        color = color,
        alpha = 1f,
    ).apply {
        nativeColorFilter = drawContext.graphicsLayer?.colorFilter?.nativeColorFilter
        val layer = drawContext.graphicsLayer
        if (layer != null && blendMode == BlendMode.SrcOver && layer.blendMode != BlendMode.SrcOver) {
            blendMode = layer.blendMode
        }
    }

internal fun Paint.toPaintSnapshot(): MinecraftCanvas.PaintSnapshot =
    MinecraftCanvas.PaintSnapshot(
        color = color,
        alpha = alpha,
        style = style,
        strokeWidth = strokeWidth,
        strokeCap = strokeCap,
        filterQuality = filterQuality,
        colorFilter = if (this is MinecraftPaint) nativeColorFilter else colorFilter?.nativeColorFilter,
        blendMode = blendMode,
        shader = shader,
    )
