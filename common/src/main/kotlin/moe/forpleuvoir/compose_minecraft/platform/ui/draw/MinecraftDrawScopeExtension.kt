package moe.forpleuvoir.compose_minecraft.platform.ui.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.recordCustomDraw
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.textures.FilterMode
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.EntityDrawData
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.ItemStackDrawData
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McEntityPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McItemPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.Corner
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McTexturePlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.TextureDrawData
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.UVMapping
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

/**
 * 在 [DrawScope] 中绘制一个 MC 原生纹理。
 */
fun DrawScope.drawMinecraftTexture(
    textureId: Identifier,
    size: Size = this.size,
    color: Color = Color.White,
    pipeline: RenderPipeline = RenderPipelines.GUI_TEXTURED,
    filterMode: FilterMode? = null,
    uv: UVMapping = UVMapping.Full,
    corner: Corner = Corner.Unspecified,
    tileSize: IntSize? = null,
) {
    drawIntoCanvas { canvas ->
        canvas.recordCustomDraw(
            McTexturePlugin.TAG,
            TextureDrawData(
                textureId = textureId,
                size = size.toIntSize(),
                pipeline = pipeline,
                filterMode = filterMode,
                uv = uv,
                corner = corner,
                tileSize = tileSize,
            ),
            buildPaint(color),
            null
        )
    }
}

/**
 * 在 [DrawScope] 中绘制一个 MC 物品。
 */
fun DrawScope.drawItemStack(
    stack: ItemStack,
    size: Float = this.size.width.coerceAtMost(this.size.height),
    color: Color = Color.White,
) {
    val s = size.roundToInt().coerceAtLeast(1)
    drawIntoCanvas { canvas ->
        canvas.recordCustomDraw(
            McItemPlugin.TAG,
            ItemStackDrawData(stack, IntSize(s, s)),
            buildPaint(color),
            null
        )
    }
}

/**
 * 在 [DrawScope] 中绘制一个 MC 实体。
 */
fun DrawScope.drawEntity(
    entity: Entity,
    size: Float = this.size.width.coerceAtMost(this.size.height),
    color: Color = Color.White,
) {
    drawIntoCanvas { canvas ->
        canvas.recordCustomDraw(
            McEntityPlugin.TAG,
            EntityDrawData(entity, size.roundToInt()),
            buildPaint(color),
            null
        )
    }
}

// ── Composable 便捷函数 ───────────────────────────────────────────

@Composable
fun MinecraftTexture(
    textureId: Identifier,
    modifier: Modifier = Modifier,
    size: DpSize = DpSize(16.dp, 16.dp),
) {
    Canvas(modifier.size(size)) {
        drawMinecraftTexture(textureId, this.size)
    }
}

@Composable
fun MinecraftItem(
    stack: ItemStack,
    modifier: Modifier = Modifier,
    size: DpSize = DpSize(24.dp, 24.dp),
) {
    Canvas(modifier.size(size)) {
        drawItemStack(stack, this.size.width.coerceAtMost(this.size.height))
    }
}

@Composable
fun MinecraftEntity(
    entity: Entity,
    modifier: Modifier = Modifier,
    size: DpSize = DpSize(48.dp, 64.dp),
) {
    Canvas(modifier.size(size)) {
        drawEntity(entity, this.size.width.coerceAtMost(this.size.height))
    }
}