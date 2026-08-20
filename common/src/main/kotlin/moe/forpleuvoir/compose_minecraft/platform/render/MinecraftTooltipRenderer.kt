package moe.forpleuvoir.compose_minecraft.platform.render

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceAtMost
import com.mojang.blaze3d.pipeline.RenderPipeline
import moe.forpleuvoir.compose_minecraft.platform.ui.tooltip.TooltipLine
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.client.renderer.state.gui.TiledBlitRenderState
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling
import net.minecraft.locale.Language
import net.minecraft.network.chat.FormattedText
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Matrix3x2f

/**
 * 1:1 tooltip 渲染桥(T.39):把原版 `GuiGraphicsExtractor` 中 tooltip 相关的绘制
 * 全部转译为 Compose 渲染管线的元素,经 [GuiCommandSink] 提交 —— **完全不走
 * [net.minecraft.client.gui.GuiGraphicsExtractor] / 原版 GuiRenderState**。
 *
 * ## 坐标系
 * - [density] 为 null(默认):使用原版 guiScale([MinecraftGuiScale.current])作为缩放系数,
 *   布局坐标 = 原版 GUI 单位,提交时所有元素 pose 统一乘 `scale(guiScale)`;
 * - [density] 非 null:使用指定的值作为 Compose 场景密度,布局坐标 = 像素(1:1)。
 * 测量函数(font.width 等)的结果在 density=1 时即窗口像素,否则需乘 density。
 *
 * [basePose] 为调用方(Compose 图层)变换,叠加在最外层(先应用 basePose 再缩放),
 * 使 tooltip 在带 graphicsLayer 变换的图层内绘制时跟随图层位移/缩放。
 *
 * ## 覆盖的原版能力(与 `GuiGraphicsExtractor` 逐一对齐)
 * `text/centeredText/textWithWordWrap`、`fill`、`blitSprite`(stretch/tile/nine_slice,
 * 含原版九宫格 tiled 内部段)、`item`、`itemDecorations`(耐久条/冷却/数量)、
 * `TooltipRenderUtil.extractTooltipBackground` 同款背景、以及 `tooltip(...)` 行循环。
 */
class MinecraftTooltipRenderer(
    private val sink: GuiCommandSink,
    /** null = 使用原版 guiScale;非 null = 自定义 Compose 密度值 */
    private val density: Float? = null,
    /** 裁剪区域(屏幕坐标,像素;不在其中时元素不裁剪) */
    private val scissor: ScreenRectangle? = null,
    /** 调用方(Compose 图层)变换,叠加在缩放之外(默认恒等) */
    basePose: Matrix3x2f = Matrix3x2f(),
) {
    /** 最终缩放系数:null density → 原版 guiScale;非 null → density × DENSITY_TO_GUI_SCALE_MULTIPLIER */
    val finalScale: Float = if (density != null) density * DENSITY_TO_GUI_SCALE_MULTIPLIER else MinecraftGuiScale.current()

    /** 提交 pose:先 basePose(图层变换)再 finalScale 缩放 */
    val pose: Matrix3x2f = if (finalScale != 1f) {
        basePose.mul(Matrix3x2f().scale(finalScale), Matrix3x2f())
    } else {
        basePose
    }

    private val mc: Minecraft = Minecraft.getInstance()

    // ── 文本 ───────────────────────────────────────────────────────

    /** 绘制文本(已处理视觉顺序的序列),同原版 text 语义(颜色 + 阴影)。 */
    fun text(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int, dropShadow: Boolean = true) {
        if (ARGB.alpha(color) != 0) {
            sink.addText(GuiTextRenderState(font, text, pose, x, y, color, 0, dropShadow, false, scissor))
        }
    }

    /** 绘制文本(字符串),同原版 text 语义(经视觉顺序处理)。 */
    fun text(font: Font, str: String?, x: Int, y: Int, color: Int, dropShadow: Boolean = true) {
        if (str != null) {
            text(font, Language.getInstance().getVisualOrder(FormattedText.of(str)), x, y, color, dropShadow)
        }
    }

    /** 以 [x] 为中心绘制文本,同原版 centeredText 语义。 */
    fun centeredText(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int) {
        this.text(font, text, x - font.width(text) / 2, y, color)
    }

    /** 以 [x] 为中心绘制文本(字符串),同原版 centeredText 语义。 */
    fun centeredText(font: Font, str: String, x: Int, y: Int, color: Int) {
        text(font, str, x - font.width(str) / 2, y, color)
    }

    /** 以 [x] 为中心绘制文本(富文本),同原版 centeredText 语义。 */
    fun centeredText(font: Font, text: net.minecraft.network.chat.Component, x: Int, y: Int, color: Int) {
        val toRender = text.visualOrderText
        this.text(font, toRender, x - font.width(toRender) / 2, y, color)
    }

    /** 按宽度换行逐行绘制,同原版 textWithWordWrap 语义(每行 +9)。 */
    fun textWithWordWrap(font: Font, string: FormattedText, x: Int, y: Int, width: Int, color: Int, dropShadow: Boolean = true) {
        var localY = y
        for (line in font.split(string, width)) {
            text(font, line, x, localY, color, dropShadow)
            localY += 9
        }
    }

    // ── 实心矩形 ───────────────────────────────────────────────────

    /** 填充实心矩形(0xAARRGGBB),同原版 fill 语义(RenderPipelines.GUI)。 */
    fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        sink.addElement(
            ColoredRectangleRenderState(
                RenderPipelines.GUI, TextureSetup.noTexture(), pose, x0, y0, x1, y1, color, color, scissor
            )
        )
    }

    // ── sprite 绘制(stretch / tile / nine_slice) ──────────────────

    /**
     * 按 sprite 自身缩放模式绘制(location = GUI atlas sprite id),同原版
     * `blitSprite(pipeline, location, x, y, width, height, color)` 语义:
     * stretch 直接拉伸、tile 平铺、nine_slice 九宫格(内部段按 meta 拉伸或平铺)。
     * 拿不到 sprite(资源未就绪)时静默跳过。
     */
    fun blitSprite(pipeline: RenderPipeline, location: Identifier, x: Int, y: Int, width: Int, height: Int, color: Int) {
        if (width == 0 || height == 0) return
        val guiSprite = GuiSpriteResolver.resolve(location) ?: return
        when (val scaling = guiSprite.scaling) {
            is GuiSpriteScaling.Stretch   -> blitSprite(pipeline, guiSprite, x, y, width, height, color)
            is GuiSpriteScaling.Tile      -> blitTiledSprite(
                pipeline, guiSprite,
                x,
                y,
                width,
                height,
                0,
                0,
                scaling.width(),
                scaling.height(),
                scaling.width(),
                scaling.height(),
                color
            )

            is GuiSpriteScaling.NineSlice -> blitNineSlicedSprite(pipeline, guiSprite, scaling, x, y, width, height, color)
        }
    }

    /**
     * 以画布坐标裁剪 sprite 子区域绘制(原版 blitSprite(spriteWidth, spriteHeight,
     * textureX, textureY, x, y, width, height, color))。textureX/textureY 为 sprite
     * 画布(尺寸 spriteWidth x spriteHeight)中的像素偏移,UV 经 getU/getV 归一化。
     */
    private fun blitSprite(
        pipeline: RenderPipeline,
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
    ) {
        if (width != 0 && height != 0) {
            val u0 = guiSprite.sprite.getU(textureX.toFloat() / spriteWidth)
            val u1 = guiSprite.sprite.getU((textureX + width).toFloat() / spriteWidth)
            val v0 = guiSprite.sprite.getV(textureY.toFloat() / spriteHeight)
            val v1 = guiSprite.sprite.getV((textureY + height).toFloat() / spriteHeight)
            innerBlit(pipeline, guiSprite, x, x + width, y, y + height, u0, u1, v0, v1, color)
        }
    }

    /** 整 sprite 拉伸绘制(原版 blitSprite(TextureAtlasSprite) 语义)。 */
    private fun blitSprite(pipeline: RenderPipeline, guiSprite: GuiSprite, x: Int, y: Int, width: Int, height: Int, color: Int) {
        if (width != 0 && height != 0) {
            innerBlit(pipeline, guiSprite, x, x + width, y, y + height, guiSprite.u0, guiSprite.u1, guiSprite.v0, guiSprite.v1, color)
        }
    }

    /** 原版 blitNineSlicedSprite:九宫格拆 9 段,border 按宽高一半钳制。 */
    private fun blitNineSlicedSprite(
        pipeline: RenderPipeline,
        guiSprite: GuiSprite,
        nineSlice: GuiSpriteScaling.NineSlice,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int,
    ) {
        val border = nineSlice.border()
        val borderLeft = border.left().fastCoerceAtMost(width / 2)
        val borderRight = border.right().fastCoerceAtMost(width / 2)
        val borderTop = border.top().fastCoerceAtMost(height / 2)
        val borderBottom = border.bottom().fastCoerceAtMost(height / 2)
        if (width == nineSlice.width() && height == nineSlice.height()) {
            blitSprite(pipeline, guiSprite, nineSlice.width(), nineSlice.height(), 0, 0, x, y, width, height, color)
        } else if (height == nineSlice.height()) {
            blitSprite(pipeline, guiSprite, nineSlice.width(), nineSlice.height(), 0, 0, x, y, borderLeft, height, color)
            blitNineSliceInnerSegment(
                pipeline, guiSprite, nineSlice,
                x + borderLeft, y, width - borderRight - borderLeft, height,
                borderLeft, 0, nineSlice.width() - borderRight - borderLeft, nineSlice.height(),
                color,
            )
            blitSprite(
                pipeline,
                guiSprite,
                nineSlice.width(),
                nineSlice.height(),
                nineSlice.width() - borderRight,
                0,
                x + width - borderRight,
                y,
                borderRight,
                height,
                color
            )
        } else if (width == nineSlice.width()) {
            blitSprite(pipeline, guiSprite, nineSlice.width(), nineSlice.height(), 0, 0, x, y, width, borderTop, color)
            blitNineSliceInnerSegment(
                pipeline, guiSprite, nineSlice,
                x, y + borderTop, width, height - borderBottom - borderTop,
                0, borderTop, nineSlice.width(), nineSlice.height() - borderBottom - borderTop,
                color,
            )
            blitSprite(
                pipeline,
                guiSprite,
                nineSlice.width(),
                nineSlice.height(),
                0,
                nineSlice.height() - borderBottom,
                x,
                y + height - borderBottom,
                width,
                borderBottom,
                color
            )
        } else {
            //左上角
            blitSprite(pipeline, guiSprite, nineSlice.width(), nineSlice.height(), 0, 0, x, y, borderLeft, borderTop, color)
            //顶部
            blitNineSliceInnerSegment(
                pipeline, guiSprite, nineSlice,
                x + borderLeft, y, width - borderRight - borderLeft, borderTop,
                borderLeft, 0, nineSlice.width() - borderRight - borderLeft, borderTop,
                color,
            )
            //右上角
            blitSprite(
                pipeline,
                guiSprite,
                nineSlice.width(),
                nineSlice.height(),
                nineSlice.width() - borderRight,
                0,
                x + width - borderRight,
                y,
                borderRight,
                borderTop,
                color
            )
            //左下角
            blitSprite(
                pipeline,
                guiSprite,
                nineSlice.width(),
                nineSlice.height(),
                0,
                nineSlice.height() - borderBottom,
                x,
                y + height - borderBottom,
                borderLeft,
                borderBottom,
                color
            )
            //底部
            blitNineSliceInnerSegment(
                pipeline,
                guiSprite,
                nineSlice,
                x + borderLeft,
                y + height - borderBottom,
                width - borderRight - borderLeft,
                borderBottom,
                borderLeft,
                nineSlice.height() - borderBottom,
                nineSlice.width() - borderRight - borderLeft,
                borderBottom,
                color
            )
            //右下角
            blitSprite(
                pipeline,
                guiSprite,
                nineSlice.width(),
                nineSlice.height(),
                nineSlice.width() - borderRight,
                nineSlice.height() - borderBottom,
                x + width - borderRight,
                y + height - borderBottom,
                borderRight,
                borderBottom,
                color
            )
            //左边
            blitNineSliceInnerSegment(
                pipeline,
                guiSprite,
                nineSlice,
                x,
                y + borderTop,
                borderLeft,
                height - borderBottom - borderTop,
                0,
                borderTop,
                borderLeft,
                nineSlice.height() - borderBottom - borderTop,
                color
            )
            //中心
            blitNineSliceInnerSegment(
                pipeline,
                guiSprite,
                nineSlice,
                x + borderLeft,
                y + borderTop,
                width - borderRight - borderLeft,
                height - borderBottom - borderTop,
                borderLeft,
                borderTop,
                nineSlice.width() - borderRight - borderLeft,
                nineSlice.height() - borderBottom - borderTop,
                color
            )
            //右边
            blitNineSliceInnerSegment(
                pipeline,
                guiSprite,
                nineSlice,
                x + width - borderRight,
                y + borderTop,
                borderRight,
                height - borderBottom - borderTop,
                nineSlice.width() - borderRight,
                borderTop,
                borderRight,
                nineSlice.height() - borderBottom - borderTop,
                color
            )
        }
    }

    /**
     * 原版 blitNineSliceInnerSegment:中间段 —— stretchInner 时拉伸;
     * 否则以"画布中间块"为 tile 平铺(blitTiledSprite)。
     */
    private fun blitNineSliceInnerSegment(
        pipeline: RenderPipeline,
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
                    color,
                )
            } else {
                blitTiledSprite(
                    pipeline, guiSprite, x, y, width, height,
                    textureX, textureY, textureWidth, textureHeight,
                    nineSlice.width(), nineSlice.height(), color,
                )
            }
        }
    }

    /** 原版 blitTiledSprite:以画布子区域(textureX..textureX+tileWidth)为 tile 平铺目标矩形。 */
    private fun blitTiledSprite(
        pipeline: RenderPipeline,
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
    ) {
        if (width <= 0 || height <= 0) return
        if (tileWidth <= 0 || tileHeight <= 0) return
        val sprite = guiSprite.sprite
        sink.addElement(
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
    private fun innerBlit(
        pipeline: RenderPipeline,
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
    ) {
        sink.addElement(
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

    /**
     * 从独立纹理(非 GUI atlas)blit 子区域,原版
     * `blit(pipeline, texture, x, y, u, v, width, height, srcWidth, srcHeight, textureWidth, textureHeight, color)`
     * 同款 —— 玩家皮肤头像等 [Identifier] 直接纹理使用,UV 为像素偏移。
     */
    fun blit(
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
    ) {
        val texture = mc.textureManager.getTexture(textureId)
        sink.addElement(
            BlitRenderState(
                pipeline,
                TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()),
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

    /**
     * 逻辑屏幕尺寸:guiScale 模式为原版 GUI 单位(guiScaledWidth/Height),
     * 1:1 模式为窗口像素(framebuffer)。用于 tooltip 定位的屏幕边界判断。
     */
    fun screenSize(): Pair<Int, Int> {
        val w = mc.window
        return if (density == null) {
            // 使用原版 guiScale:逻辑屏幕 = guiScaled 尺寸
            w.guiScaledWidth to w.guiScaledHeight
        } else {
            w.width to w.height
        }
    }

    // ── 物品 ────────────────────────────────────────────────────────

    /** 渲染 GUI 物品(原版 item 语义:updateForTopItem + GuiItemRenderState)。 */
    fun item(itemStack: ItemStack, x: Int, y: Int, seed: Int = 0) {
        if (itemStack.isEmpty) return
        val state = TrackingItemStackRenderState()
        mc.itemModelResolver.updateForTopItem(
            state, itemStack, ItemDisplayContext.GUI,
            if (mc.level != null) mc.level else null,
            mc.player, seed,
        )
        sink.addItem(
            ItemRenderState(
                pose = pose,
                itemStackRenderState = state,
                x = x,
                y = y,
                size = IntSize(16, 16),
                color = -1,
                scissorArea = scissor,
                identityKey = itemStack.item,
            )
        )
    }

    /**
     * 物品角标(原版 itemDecorations):耐久条 + 冷却遮罩 + 数量文本。
     */
    fun itemDecorations(font: Font, itemStack: ItemStack, x: Int, y: Int) {
        if (itemStack.isEmpty) return
        itemBar(itemStack, x, y)
        itemCooldown(itemStack, x, y)
        itemCount(font, itemStack, x, y, null)
    }

    private fun itemBar(itemStack: ItemStack, x: Int, y: Int) {
        if (itemStack.isBarVisible) {
            val left = x + 2
            val top = y + 13
            fill(left, top, left + 13, top + 2, -16777216)
            fill(left, top, left + itemStack.barWidth, top + 1, ARGB.opaque(itemStack.barColor))
        }
    }

    private fun itemCount(font: Font, itemStack: ItemStack, x: Int, y: Int, countText: String?) {
        if (itemStack.count != 1 || countText != null) {
            val amount: String = countText ?: itemStack.count.toString()
            text(font, amount, x + 19 - 2 - font.width(amount), y + 6 + 3, -1, true)
        }
    }

    private fun itemCooldown(itemStack: ItemStack, x: Int, y: Int) {
        val player = mc.player
        val cooldown = player?.cooldowns
            ?.getCooldownPercent(itemStack, mc.deltaTracker.getGameTimeDeltaPartialTick(true)) ?: 0f
        if (cooldown > 0f) {
            val top = y + Mth.floor(16f * (1f - cooldown))
            val bottom = top + Mth.ceil(16f * cooldown)
            fill(x, top, x + 16, bottom, Integer.MAX_VALUE)
        }
    }

    // ── tooltip 背景与行循环 ────────────────────────────────────────

    companion object {
        /** 背景外扩:padding 3 + margin 9(原版 TooltipRenderUtil 常量,可覆盖)。 */
        const val PADDING = 3
        const val MARGIN = 9

        /** 背景外扩两侧合计 = 2 × (PADDING + MARGIN),popup 布局/定位用 */
        const val TOTAL_OUTER_PADDING = 2 * (PADDING + MARGIN)
        const val BACKGROUND_SPRITE_PATH = "tooltip/background"
        const val FRAME_SPRITE_PATH = "tooltip/frame"

        /** 原版 DefaultTooltipPositioner 定位常量(可覆盖):鼠标偏移 + 边界校正。 */
        const val MOUSE_OFFSET_X = 20
        const val MOUSE_OFFSET_Y = -12
        const val OVERFLOW_FLIP_BACK = 36
        const val EDGE_MIN = 4
        const val SCREEN_PADDING = 3

        /** Compose 密度 → guiScale 倍率:密度模式下 finalScale = density × 此值 */
        const val DENSITY_TO_GUI_SCALE_MULTIPLIER = 2.0f

        private val BACKGROUND_SPRITE: Identifier = Identifier.withDefaultNamespace(BACKGROUND_SPRITE_PATH)
        private val FRAME_SPRITE: Identifier = Identifier.withDefaultNamespace(FRAME_SPRITE_PATH)
    }

    /**
     * 原版 DefaultTooltipPositioner.positionTooltip 同款定位(数值参数化,默认 = 原版):
     * 起手 ([x]+12, [y]-12),超右边界左移翻转、超下边界贴底;返回 (x, y)。
     */
    fun positionTooltip(
        x: Int,
        y: Int,
        tooltipWidth: Int,
        tooltipHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        mouseOffsetX: Int = MOUSE_OFFSET_X,
        mouseOffsetY: Int = MOUSE_OFFSET_Y,
        overflowFlipBack: Int = OVERFLOW_FLIP_BACK,
        edgeMin: Int = EDGE_MIN,
        screenPadding: Int = SCREEN_PADDING,
    ): Pair<Int, Int> {
        var rx = x + mouseOffsetX
        var ry = y + mouseOffsetY
        if (rx + tooltipWidth > screenWidth) {
            rx = maxOf(rx - overflowFlipBack - tooltipWidth, edgeMin)
        }
        val paddedHeight = tooltipHeight + screenPadding
        if (ry + paddedHeight > screenHeight) {
            ry = screenHeight - paddedHeight
        }
        return rx to ry
    }

    /**
     * tooltip 背景(原版 TooltipRenderUtil.extractTooltipBackground 同款):
     * 内容区左上角 [x]/[y] + 尺寸 [w]/[h] 外扩 (PADDING + MARGIN),
     * 绘制 background 与 frame 两层九宫格 sprite([style] 变体路径)。
     */
    fun extractTooltipBackground(x: Int, y: Int, w: Int, h: Int, style: Identifier?) {
        val x0 = x - PADDING - MARGIN
        val y0 = y - PADDING - MARGIN
        val paddedWidth = w + (PADDING + MARGIN) * 2
        val paddedHeight = h + (PADDING + MARGIN) * 2
        //背景图
        blitSprite(
            RenderPipelines.GUI_TEXTURED, backgroundSprite(style),
            x0, y0, paddedWidth, paddedHeight, -1,
        )
        //边框
        blitSprite(
            RenderPipelines.GUI_TEXTURED, frameSprite(style),
            x0, y0, paddedWidth, paddedHeight, -1,
        )
    }

    private fun backgroundSprite(style: Identifier?): Identifier = style?.withPath { path -> "tooltip/$path" + "_background" } ?: BACKGROUND_SPRITE

    private fun frameSprite(style: Identifier?): Identifier = style?.withPath { path -> "tooltip/$path" + "_frame" } ?: FRAME_SPRITE

    /**
     * 渲染完整 tooltip 内容(原版 GuiGraphicsExtractor.tooltip 同款,不含定位):
     * [x]/[y] 为内容区左上角,[w]/[h] 为 [TooltipLines.measure] 结果 ——
     * 背景外扩绘制 + 文本行(localY 首行 +2 行距)+ 图片行(第二遍循环)。
     */
    fun renderTooltip(lines: List<TooltipLine>, font: Font, x: Int, y: Int, w: Int, h: Int, style: Identifier?) {
        extractTooltipBackground(x, y, w, h, style)
        var localY = y
        for ((i, line) in lines.withIndex()) {
            line.renderText(this, font, x, localY)
            localY += line.getHeight(font) + if (i == 0) 2 else 0
        }
        localY = y
        for ((i, line) in lines.withIndex()) {
            line.renderImage(this, font, x, localY, w, h)
            localY += line.getHeight(font) + if (i == 0) 2 else 0
        }
    }
}