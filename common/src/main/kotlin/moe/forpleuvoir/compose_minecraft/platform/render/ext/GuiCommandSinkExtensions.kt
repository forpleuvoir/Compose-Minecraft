package moe.forpleuvoir.compose_minecraft.platform.render.ext

import androidx.compose.ui.util.fastCoerceAtMost
import kotlin.math.absoluteValue
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.Corner
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink
import moe.forpleuvoir.compose_minecraft.platform.render.state.GuiSprite
import moe.forpleuvoir.compose_minecraft.platform.render.state.GuiSpriteResolver
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.UVMapping
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.TiledBlitRenderState
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f

/**
 * [GuiCommandSink] 的绘制扩展方法集合(R.1):把两套 **九宫格 / sprite 缩放 / blit** 绘制
 * 逻辑统一为 `GuiCommandSink` 的扩展方法,由 `MinecraftTooltipRenderer` 与 `McTexturePlugin`
 * 共用,消除重复的 9 段拆分与低层 `BlitRenderState` / `TiledBlitRenderState` 提交。
 *
 * 方法按输入模型分两组、用方法名与参数区分,**语义保持各自分离**:
 * - **原版 sprite 缩放组**([GuiCommandSink.blitSprite] 顶层分派):输入是原版 GUI atlas
 *   sprite([GuiSprite],经 [GuiSpriteResolver]),依 `GuiSpriteScaling` 走 stretch / tile /
 *   nine_slice —— 忠实原版 `GuiGraphicsExtractor` 语义(sprite 的 border 天然非负,`Math.min`
 *   向下钳制,无负值外扩);
 * - **像素九宫格组**([GuiCommandSink.pushNineSliced]):输入是手动像素 `UVMapping` + `Corner`,
 *   中心恒拉伸,support 负值外扩(角向区域 / UV 同步外扩、中心只减正值宽度)。
 *
 * 两组共用同一低层提交(私有 `innerBlit` / `segment` 扩展)。所有方法显式携带
 * `pose`(图层变换)与 `scissor`(裁剪区域),不持有渲染状态。
 */

// ─────────────────────────────────────────────────────────────────────────────────────
// 一、原版 sprite 缩放组(blitSprite 顶层分派 stretch / tile / nine_slice,忠实原版)
// ─────────────────────────────────────────────────────────────────────────────────────

/**
 * 按 sprite 自身缩放模式绘制(location = GUI atlas sprite id),同原版
 * `blitSprite(pipeline, location, x, y, width, height, color)` 语义:
 * stretch 直接拉伸、tile 平铺、nine_slice 九宫格(内部段按 meta 拉伸或平铺)。
 * 拿不到 sprite(资源未就绪)时静默跳过。
 */
fun GuiCommandSink.blitSprite(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    location: Identifier,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    color: Int,
    pose: Matrix3x2f = Matrix3x2f(),
    scissor: ScreenRectangle? = null,
) {
    if (width == 0 || height == 0) return
    val guiSprite = GuiSpriteResolver.resolve(location) ?: return
    when (val scaling = guiSprite.scaling) {
        is GuiSpriteScaling.Stretch   -> blitSprite(pipeline, guiSprite, x, y, width, height, color, pose, scissor)
        is GuiSpriteScaling.Tile      -> blitTiledSprite(
            pipeline, guiSprite,
            x, y, width, height,
            0, 0, scaling.width(), scaling.height(), scaling.width(), scaling.height(),
            color, pose, scissor,
        )

        is GuiSpriteScaling.NineSlice -> blitNineSlicedSprite(pipeline, guiSprite, scaling, x, y, width, height, color, pose, scissor)
    }
}

/**
 * 从独立纹理(非 GUI atlas)blit 子区域,原版
 * `blit(pipeline, texture, x, y, u, v, width, height, srcWidth, srcHeight, textureWidth, textureHeight, color)`
 * 同款 —— 玩家皮肤头像等 [Identifier] 直接纹理使用,UV 为像素偏移。
 */
fun GuiCommandSink.blit(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    textureId: Identifier,
    x: Int,
    y: Int,
    u: Float,
    v: Float,
    width: Int,
    height: Int,
    srcWidth: Int,
    srcHeight: Int,
    textureWidth: Int,
    textureHeight: Int,
    color: Int = -1,
    pose: Matrix3x2f = Matrix3x2f(),
    scissor: ScreenRectangle? = null,
) {
    val texture = mc.textureManager.getTexture(textureId)
    addElement(
        BlitRenderState(
            pipeline,
            net.minecraft.client.gui.render.TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()),
            pose,
            x, x + width, y, y + height,
            u / textureWidth,
            (u + srcWidth) / textureWidth,
            v / textureHeight,
            (v + srcHeight) / textureHeight,
            color,
            scissor,
        )
    )
}

/** 整 sprite 拉伸绘制(原版 blitSprite(TextureAtlasSprite) 语义)。 */
private fun GuiCommandSink.blitSprite(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    guiSprite: GuiSprite,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    color: Int,
    pose: Matrix3x2f,
    scissor: ScreenRectangle?,
) {
    if (width != 0 && height != 0) {
        innerBlit(pipeline, guiSprite, x, x + width, y, y + height, guiSprite.u0, guiSprite.u1, guiSprite.v0, guiSprite.v1, color, pose, scissor)
    }
}

/**
 * 以画布坐标裁剪 sprite 子区域绘制(原版 blitSprite(spriteWidth, spriteHeight,
 * textureX, textureY, x, y, width, height, color))。textureX/textureY 为 sprite
 * 画布(尺寸 spriteWidth x spriteHeight)中的像素偏移,UV 经 getU/getV 归一化。
 */
private fun GuiCommandSink.blitSprite(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    guiSprite: GuiSprite,
    spriteWidth: Int,
    spriteHeight: Int,
    textureX: Int,
    textureY: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    color: Int,
    pose: Matrix3x2f,
    scissor: ScreenRectangle?,
) {
    if (width != 0 && height != 0) {
        val u0 = guiSprite.sprite.getU(textureX.toFloat() / spriteWidth)
        val u1 = guiSprite.sprite.getU((textureX + width).toFloat() / spriteWidth)
        val v0 = guiSprite.sprite.getV(textureY.toFloat() / spriteHeight)
        val v1 = guiSprite.sprite.getV((textureY + height).toFloat() / spriteHeight)
        innerBlit(pipeline, guiSprite, x, x + width, y, y + height, u0, u1, v0, v1, color, pose, scissor)
    }
}

/** 原版 blitNineSlicedSprite:九宫格拆 9 段,border 按宽高一半钳制。 */
private fun GuiCommandSink.blitNineSlicedSprite(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    guiSprite: GuiSprite,
    nineSlice: GuiSpriteScaling.NineSlice,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    color: Int,
    pose: Matrix3x2f,
    scissor: ScreenRectangle?,
) {
    val border = nineSlice.border()
    val borderLeft = border.left().fastCoerceAtMost(width / 2)
    val borderRight = border.right().fastCoerceAtMost(width / 2)
    val borderTop = border.top().fastCoerceAtMost(height / 2)
    val borderBottom = border.bottom().fastCoerceAtMost(height / 2)
    if (width == nineSlice.width() && height == nineSlice.height()) {
        blitSprite(pipeline, guiSprite, nineSlice.width(), nineSlice.height(), 0, 0, x, y, width, height, color, pose, scissor)
    } else if (height == nineSlice.height()) {
        blitSprite(pipeline, guiSprite, nineSlice.width(), nineSlice.height(), 0, 0, x, y, borderLeft, height, color, pose, scissor)
        blitNineSliceInnerSegment(
            pipeline, guiSprite, nineSlice,
            x + borderLeft, y, width - borderRight - borderLeft, height,
            borderLeft, 0, nineSlice.width() - borderRight - borderLeft, nineSlice.height(),
            color, pose, scissor,
        )
        blitSprite(
            pipeline, guiSprite, nineSlice.width(), nineSlice.height(),
            nineSlice.width() - borderRight, 0,
            x + width - borderRight, y, borderRight, height,
            color, pose, scissor,
        )
    } else if (width == nineSlice.width()) {
        blitSprite(pipeline, guiSprite, nineSlice.width(), nineSlice.height(), 0, 0, x, y, width, borderTop, color, pose, scissor)
        blitNineSliceInnerSegment(
            pipeline, guiSprite, nineSlice,
            x, y + borderTop, width, height - borderBottom - borderTop,
            0, borderTop, nineSlice.width(), nineSlice.height() - borderBottom - borderTop,
            color, pose, scissor,
        )
        blitSprite(
            pipeline, guiSprite, nineSlice.width(), nineSlice.height(),
            0, nineSlice.height() - borderBottom,
            x, y + height - borderBottom, width, borderBottom,
            color, pose, scissor,
        )
    } else {
        // 左上角
        blitSprite(pipeline, guiSprite, nineSlice.width(), nineSlice.height(), 0, 0, x, y, borderLeft, borderTop, color, pose, scissor)
        // 顶部
        blitNineSliceInnerSegment(
            pipeline, guiSprite, nineSlice,
            x + borderLeft, y, width - borderRight - borderLeft, borderTop,
            borderLeft, 0, nineSlice.width() - borderRight - borderLeft, borderTop,
            color, pose, scissor,
        )
        // 右上角
        blitSprite(
            pipeline, guiSprite, nineSlice.width(), nineSlice.height(),
            nineSlice.width() - borderRight, 0,
            x + width - borderRight, y, borderRight, borderTop,
            color, pose, scissor,
        )
        // 左下角
        blitSprite(
            pipeline, guiSprite, nineSlice.width(), nineSlice.height(),
            0, nineSlice.height() - borderBottom,
            x, y + height - borderBottom, borderLeft, borderBottom,
            color, pose, scissor,
        )
        // 底部
        blitNineSliceInnerSegment(
            pipeline, guiSprite, nineSlice,
            x + borderLeft, y + height - borderBottom, width - borderRight - borderLeft, borderBottom,
            borderLeft, nineSlice.height() - borderBottom, nineSlice.width() - borderRight - borderLeft, borderBottom,
            color, pose, scissor,
        )
        // 右下角
        blitSprite(
            pipeline, guiSprite, nineSlice.width(), nineSlice.height(),
            nineSlice.width() - borderRight, nineSlice.height() - borderBottom,
            x + width - borderRight, y + height - borderBottom, borderRight, borderBottom,
            color, pose, scissor,
        )
        // 左边
        blitNineSliceInnerSegment(
            pipeline, guiSprite, nineSlice,
            x, y + borderTop, borderLeft, height - borderBottom - borderTop,
            0, borderTop, borderLeft, nineSlice.height() - borderBottom - borderTop,
            color, pose, scissor,
        )
        // 中心
        blitNineSliceInnerSegment(
            pipeline, guiSprite, nineSlice,
            x + borderLeft, y + borderTop, width - borderRight - borderLeft, height - borderBottom - borderTop,
            borderLeft, borderTop, nineSlice.width() - borderRight - borderLeft, nineSlice.height() - borderBottom - borderTop,
            color, pose, scissor,
        )
        // 右边
        blitNineSliceInnerSegment(
            pipeline, guiSprite, nineSlice,
            x + width - borderRight, y + borderTop, borderRight, height - borderBottom - borderTop,
            nineSlice.width() - borderRight, borderTop, borderRight, nineSlice.height() - borderBottom - borderTop,
            color, pose, scissor,
        )
    }
}

/**
 * 原版 blitNineSliceInnerSegment:中间段 —— stretchInner 时拉伸;
 * 否则以"画布中间块"为 tile 平铺(GuiCommandSink.blitTiledSprite)。
 */
private fun GuiCommandSink.blitNineSliceInnerSegment(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    guiSprite: GuiSprite,
    nineSlice: GuiSpriteScaling.NineSlice,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    textureX: Int,
    textureY: Int,
    textureWidth: Int,
    textureHeight: Int,
    color: Int,
    pose: Matrix3x2f,
    scissor: ScreenRectangle?,
) {
    if (width > 0 && height > 0) {
        if (nineSlice.stretchInner()) {
            innerBlit(
                pipeline, guiSprite,
                x, x + width, y, y + height,
                guiSprite.sprite.getU(textureX.toFloat() / nineSlice.width()),
                guiSprite.sprite.getU((textureX + textureWidth).toFloat() / nineSlice.width()),
                guiSprite.sprite.getV(textureY.toFloat() / nineSlice.height()),
                guiSprite.sprite.getV((textureY + textureHeight).toFloat() / nineSlice.height()),
                color, pose, scissor,
            )
        } else {
            blitTiledSprite(
                pipeline, guiSprite, x, y, width, height,
                textureX, textureY, textureWidth, textureHeight,
                nineSlice.width(), nineSlice.height(),
                color, pose, scissor,
            )
        }
    }
}

/** 原版 blitTiledSprite:以画布子区域(textureX..textureX+tileWidth)为 tile 平铺目标矩形。 */
private fun GuiCommandSink.blitTiledSprite(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    guiSprite: GuiSprite,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    textureX: Int,
    textureY: Int,
    tileWidth: Int,
    tileHeight: Int,
    spriteWidth: Int,
    spriteHeight: Int,
    color: Int,
    pose: Matrix3x2f,
    scissor: ScreenRectangle?,
) {
    if (width <= 0 || height <= 0) return
    if (tileWidth <= 0 || tileHeight <= 0) return
    val sprite = guiSprite.sprite
    addElement(
        TiledBlitRenderState(
            pipeline,
            guiSprite.textureSetup,
            pose,
            tileWidth,
            tileHeight,
            x,
            y,
            x + width,
            y + height,
            sprite.getU(textureX.toFloat() / spriteWidth),
            sprite.getU((textureX + tileWidth).toFloat() / spriteWidth),
            sprite.getV(textureY.toFloat() / spriteHeight),
            sprite.getV((textureY + tileHeight).toFloat() / spriteHeight),
            color,
            scissor,
        )
    )
}

/** 原版 innerBlit:一张 UV 矩形提交 [BlitRenderState](默认 GUI_TEXTURED 管线)。 */
private fun GuiCommandSink.innerBlit(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    guiSprite: GuiSprite,
    x0: Int,
    x1: Int,
    y0: Int,
    y1: Int,
    u0: Float,
    u1: Float,
    v0: Float,
    v1: Float,
    color: Int,
    pose: Matrix3x2f,
    scissor: ScreenRectangle?,
) {
    addElement(
        BlitRenderState(
            pipeline,
            guiSprite.textureSetup,
            pose,
            x0, y0, x1, y1,
            u0, u1, v0, v1,
            color,
            scissor,
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────────────
// 二、像素九宫格组(pushNineSliced:UVMapping + Corner,中心恒拉伸,支持负值外扩)
// ─────────────────────────────────────────────────────────────────────────────────────

/**
 * 九宫格 9 段绘制(参考 ibukigourd pushNineSlicedBlit,自 `McTexturePlugin` 迁移):
 * [corner] 正值向内收缩、负值向区域外扩(绝对值),中心区域只减正值宽度,UV 同步外扩。
 * [uv] 为像素子区域(uStart..uEnd / vStart..vEnd),[size] 为目标绘制尺寸,
 * UV 归一化以纹理实际尺寸 [tw]/[th] 为基准。
 */
@Suppress("UnnecessaryVariable")
fun GuiCommandSink.pushNineSliced(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    uv: UVMapping,
    corner: Corner,
    size: androidx.compose.ui.unit.IntSize,
    color: Int,
    textureSetup: net.minecraft.client.gui.render.TextureSetup,
    tw: Int,
    th: Int,
    pose: Matrix3x2f = Matrix3x2f(),
    scissor: ScreenRectangle? = null,
) {
    val u0 = uv.uStart
    val v0 = uv.vStart
    val u1 = uv.width
    val v1 = uv.height
    val x = 0
    val y = 0
    val width = size.width
    val height = size.height

    val cl = corner.left.absoluteValue
    val cr = corner.right.absoluteValue
    val ct = corner.top.absoluteValue
    val cb = corner.bottom.absoluteValue

    // 中心区域:只减正值(负值外扩,中心覆盖整个区域)
    val cw = width - (corner.left.coerceAtLeast(0) + corner.right.coerceAtLeast(0))
    val ch = height - (corner.top.coerceAtLeast(0) + corner.bottom.coerceAtLeast(0))

    val leftX = if (corner.left >= 0) x else x - cl
    val centerX = if (corner.left >= 0) x + cl else x
    val rightX = if (corner.right >= 0) x + (width - corner.right) else x + width

    val topY = if (corner.top >= 0) y else y - ct
    val centerY = if (corner.top >= 0) y + ct else y
    val bottomY = if (corner.bottom >= 0) y + (height - corner.bottom) else y + height

    val leftU = if (corner.left >= 0) u0 else u0 - cl
    val centerU = if (corner.left >= 0) u0 + cl else u0
    val rightU = if (corner.right >= 0) u0 + (u1 - cr) else u0 + u1

    val topV = if (corner.top >= 0) v0 else v0 - ct
    val centerV = if (corner.top >= 0) v0 + ct else v0
    val bottomV = if (corner.bottom >= 0) v0 + (v1 - cb) else v0 + v1

    val leftUS = cl
    val centerUS = u1 - (corner.left.coerceAtLeast(0) + corner.right.coerceAtLeast(0))
    val rightUS = cr

    val topVS = ct
    val centerVS = v1 - (corner.top.coerceAtLeast(0) + corner.bottom.coerceAtLeast(0))
    val bottomVS = cb

    // 9 段:四个角原尺寸、四条边单向拉伸、中心双向拉伸
    segment(pipeline, leftX, topY, cl, ct, leftU, topV, leftUS, topVS, color, tw, th, textureSetup, pose, scissor)
    segment(pipeline, centerX, topY, cw, ct, centerU, topV, centerUS, topVS, color, tw, th, textureSetup, pose, scissor)
    segment(pipeline, rightX, topY, cr, ct, rightU, topV, rightUS, topVS, color, tw, th, textureSetup, pose, scissor)

    segment(pipeline, leftX, centerY, cl, ch, leftU, centerV, leftUS, centerVS, color, tw, th, textureSetup, pose, scissor)
    segment(pipeline, centerX, centerY, cw, ch, centerU, centerV, centerUS, centerVS, color, tw, th, textureSetup, pose, scissor)
    segment(pipeline, rightX, centerY, cr, ch, rightU, centerV, rightUS, centerVS, color, tw, th, textureSetup, pose, scissor)

    segment(pipeline, leftX, bottomY, cl, cb, leftU, bottomV, leftUS, bottomVS, color, tw, th, textureSetup, pose, scissor)
    segment(pipeline, centerX, bottomY, cw, cb, centerU, bottomV, centerUS, bottomVS, color, tw, th, textureSetup, pose, scissor)
    segment(pipeline, rightX, bottomY, cr, cb, rightU, bottomV, rightUS, bottomVS, color, tw, th, textureSetup, pose, scissor)
}

/** 九宫格单段:像素 UV 归一化后提交 [BlitRenderState](像素九宫格组内部用)。 */
private fun GuiCommandSink.segment(
    pipeline: com.mojang.blaze3d.pipeline.RenderPipeline,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    u: Int,
    v: Int,
    uSize: Int,
    vSize: Int,
    color: Int,
    tw: Int,
    th: Int,
    textureSetup: net.minecraft.client.gui.render.TextureSetup,
    pose: Matrix3x2f,
    scissor: ScreenRectangle?,
) {
    if (w <= 0 || h <= 0 || color and 0xFF000000.toInt() == 0) return
    addElement(
        BlitRenderState(
            pipeline,
            textureSetup,
            pose,
            x, y, x + w, y + h,
            u.toFloat() / tw,
            (u + uSize).toFloat() / tw,
            v.toFloat() / th,
            (v + vSize).toFloat() / th,
            color,
            scissor,
        )
    )
}
