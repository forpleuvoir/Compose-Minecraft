package moe.forpleuvoir.compose_minecraft.platform.ui.tooltip

import androidx.compose.ui.unit.IntSize
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.MinecraftTooltipRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.tooltip.ClientActivePlayersTooltip.ActivePlayersTooltip
import net.minecraft.client.renderer.PlayerSkinRenderCache
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.inventory.tooltip.BundleTooltip
import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.component.BundleContents
import org.apache.commons.lang3.math.Fraction

/**
 * tooltip 行(1:1 渲染):文本行与图片行的统一接口,测量/渲染语义对齐原版
 * [net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent]。
 */
sealed interface TooltipLine {

    /** 行宽(font 逻辑单位,原版 [ClientTooltipComponent.getWidth] 语义) */
    fun getWidth(font: Font): Int

    /** 行高(font 逻辑单位,原版 [ClientTooltipComponent.getHeight] 语义) */
    fun getHeight(font: Font): Int

    /** 文本绘制(与原版 extractText 对应,默认空实现) */
    fun renderText(renderer: MinecraftTooltipRenderer, font: Font, x: Int, y: Int) = Unit

    /** 图片绘制(与原版 extractImage 对应,默认空实现;[w]/[h] 为 tooltip 内容区尺寸) */
    fun renderImage(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        x: Int,
        y: Int,
        tooltipWidth: Int,
        tooltipHeight: Int,
    ) = Unit
}

/**
 * 文本行:照原版 `ClientTextTooltip`(宽度 = font.width、行高 10、text 白色 -1 + 阴影)。
 */
class TextTooltipLine(val text: FormattedCharSequence) : TooltipLine {
    override fun getWidth(font: Font): Int = font.width(text)
    override fun getHeight(font: Font): Int = 10
    override fun renderText(renderer: MinecraftTooltipRenderer, font: Font, x: Int, y: Int) {
        renderer.text(font, text, x, y, -1, true)
    }
}

/**
 * 图片行:包装 [TooltipComponent](如 bundle 内容网格),测量与绘制经 [TooltipImages] 分派。
 */
class ImageTooltipLine(val component: TooltipComponent) : TooltipLine {
    override fun getWidth(font: Font): Int = TooltipImages.getWidth(component, font)
    override fun getHeight(font: Font): Int = TooltipImages.getHeight(component, font)
    override fun renderImage(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        x: Int,
        y: Int,
        tooltipWidth: Int,
        tooltipHeight: Int,
    ) {
        TooltipImages.render(component, renderer, font, x, y, tooltipWidth, tooltipHeight)
    }
}

/**
 * tooltip 数据闭包:字体 + 行列表 + 样式(背景 sprite 变体)。
 *
 * 测量语义照原版 [MinecraftTooltipRenderer.renderTooltip] 开头:
 * 宽度 = 最大行宽;高度 = 行高之和,单行时 -2(原版特判)。
 * 结果单位为 font 逻辑单位(1:1 模式下即窗口像素;guiScale 模式下即 GUI 单位)。
 */
data class TooltipLines(
    val font: Font,
    val lines: List<TooltipLine>,
    val style: Identifier? = null,
) {
    /** 内容区尺寸(不含背景外扩),照原版 tooltip() 计算。 */
    fun measure(): IntSize {
        var textWidth = 0
        var tempHeight = if (lines.size == 1) -2 else 0
        for (line in lines) {
            textWidth = maxOf(textWidth, line.getWidth(font))
            tempHeight += line.getHeight(font)
        }
        return IntSize(textWidth, tempHeight)
    }

    companion object {

        /** 通用文本 tooltip:任意 [Component] 列表 → 文本行。 */
        fun fromLines(texts: List<Component>, style: Identifier? = null, font: Font = mc.font): TooltipLines =
            TooltipLines(
                font = font,
                lines = texts.map { TextTooltipLine(it.visualOrderText) },
                style = style,
            )

        /**
         * 物品 tooltip:照原版 `Screen.getTooltipFromItem` 同款文本 + `getTooltipImage` +
         * `TOOLTIP_STYLE`;图片组件插到第 1 行(原版 components.isEmpty() ? 0 : 1 语义)。
         */
        fun fromItem(itemStack: ItemStack, font: Font = mc.font): TooltipLines {
            val mc = Minecraft.getInstance()
            val texts = Screen.getTooltipFromItem(mc, itemStack)
            val lines: MutableList<TooltipLine> = texts.map { TextTooltipLine(it.visualOrderText) }.toMutableList()
            val image = itemStack.tooltipImage.orElse(null)
            if (image != null) {
                lines.add(if (lines.isEmpty()) 0 else 1, ImageTooltipLine(image))
            }
            return TooltipLines(
                font = font,
                lines = lines,
                style = itemStack.get(DataComponents.TOOLTIP_STYLE),
            )
        }
    }
}

/**
 * 图片组件测量/渲染分派(T.39):`BundleTooltip`(/`BundleContents`)内容网格、
 * `ActivePlayersTooltip` 玩家列表的 1:1 绘制 —— 逻辑 1:1 翻译原版
 * `ClientBundleTooltip` / `ClientActivePlayersTooltip`(默认值一致,常量可覆盖)。
 */
internal object TooltipImages {

    // —— 常量(默认 = 原版,可覆盖) ——
    private const val SLOT_MARGIN = 4
    private const val GRID_WIDTH = 96
    private const val PROGRESSBAR_HEIGHT = 13
    private const val PROGRESSBAR_WIDTH = 96
    private const val PROGRESSBAR_BORDER = 1
    private const val PROGRESSBAR_FILL_MAX = 94
    private const val SLOT_CELL_SIZE = 24
    private const val SLOT_COLUMNS = 4
    private const val MAX_VISIBLE_SLOTS = 12
    private const val ACTIVE_PLAYER_SKIN_SIZE = 10
    private const val ACTIVE_PLAYER_PADDING = 2
    private const val ACTIVE_PLAYER_ROW_HEIGHT = 12

    private val BUNDLE_FULL_TEXT: Component = Component.translatable("item.minecraft.bundle.full")
    private val BUNDLE_EMPTY_TEXT: Component = Component.translatable("item.minecraft.bundle.empty")
    private val BUNDLE_EMPTY_DESCRIPTION: Component = Component.translatable("item.minecraft.bundle.empty.description")

    private val PROGRESSBAR_BORDER_SPRITE: Identifier = Identifier.withDefaultNamespace("container/bundle/bundle_progressbar_border")
    private val PROGRESSBAR_FILL_SPRITE: Identifier = Identifier.withDefaultNamespace("container/bundle/bundle_progressbar_fill")
    private val PROGRESSBAR_FULL_SPRITE: Identifier = Identifier.withDefaultNamespace("container/bundle/bundle_progressbar_full")
    private val SLOT_HIGHLIGHT_BACK_SPRITE: Identifier = Identifier.withDefaultNamespace("container/bundle/slot_highlight_back")
    private val SLOT_HIGHLIGHT_FRONT_SPRITE: Identifier = Identifier.withDefaultNamespace("container/bundle/slot_highlight_front")
    private val SLOT_BACKGROUND_SPRITE: Identifier = Identifier.withDefaultNamespace("container/bundle/slot_background")

    fun getWidth(component: TooltipComponent, font: Font): Int = when (component) {
        is BundleContents       -> GRID_WIDTH
        is BundleTooltip        -> GRID_WIDTH
        is ActivePlayersTooltip -> playersWidth(font, component.profiles())
        else                    -> 0
    }

    fun getHeight(component: TooltipComponent, font: Font): Int = when (component) {
        is BundleContents       -> bundleHeight(font, component)
        is BundleTooltip        -> bundleHeight(font, component.contents())
        is ActivePlayersTooltip -> component.profiles().size * ACTIVE_PLAYER_ROW_HEIGHT + ACTIVE_PLAYER_PADDING
        else                    -> 0
    }

    fun render(
        component: TooltipComponent,
        renderer: MinecraftTooltipRenderer,
        font: Font,
        x: Int,
        y: Int,
        tooltipWidth: Int,
        tooltipHeight: Int,
    ) {
        when (component) {
            is BundleContents       -> renderBundle(renderer, font, component, x, y, tooltipWidth, tooltipHeight)
            is BundleTooltip        -> renderBundle(renderer, font, component.contents(), x, y, tooltipWidth, tooltipHeight)
            is ActivePlayersTooltip -> renderActivePlayers(renderer, font, component.profiles(), x, y)
            else                    -> Unit
        }
    }

    // ── Bundle ─────────────────────────────────────────────────────

    private fun bundleHeight(font: Font, contents: BundleContents): Int =
        if (contents.isEmpty) {
            getEmptyBundleDescriptionTextHeight(font) + PROGRESSBAR_HEIGHT + 8
        } else {
            gridSizeY(contents) * SLOT_CELL_SIZE + PROGRESSBAR_HEIGHT + 8
        }

    private fun getEmptyBundleDescriptionTextHeight(font: Font): Int =
        font.split(BUNDLE_EMPTY_DESCRIPTION, GRID_WIDTH).size * 9

    private fun gridSizeY(contents: BundleContents): Int =
        Mth.positiveCeilDiv(slotCount(contents), SLOT_COLUMNS)

    private fun slotCount(contents: BundleContents): Int = minOf(MAX_VISIBLE_SLOTS, contents.size())

    private fun getContentXOffset(tooltipWidth: Int): Int = (tooltipWidth - GRID_WIDTH) / 2

    private fun renderBundle(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        contents: BundleContents,
        x: Int,
        y: Int,
        tooltipWidth: Int,
        tooltipHeight: Int,
    ) {
        val weight = contents.weight()
        if (weight.isError()) return
        if (contents.isEmpty) {
            extractEmptyBundleTooltip(renderer, font, x, y, tooltipWidth, tooltipHeight)
        } else {
            extractBundleWithItemsTooltip(renderer, font, contents, x, y, tooltipWidth, weight.getOrThrow())
        }
    }

    private fun extractEmptyBundleTooltip(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        x: Int,
        y: Int,
        tooltipWidth: Int,
        tooltipHeight: Int,
    ) {
        val left = x + getContentXOffset(tooltipWidth)
        renderer.textWithWordWrap(font, BUNDLE_EMPTY_DESCRIPTION, left, y, GRID_WIDTH, -5592406)
        extractProgressbar(renderer, font, left, y + getEmptyBundleDescriptionTextHeight(font) + 4, Fraction.ZERO)
    }

    private fun extractBundleWithItemsTooltip(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        contents: BundleContents,
        x: Int,
        y: Int,
        tooltipWidth: Int,
        weight: Fraction,
    ) {
        val isOverflowing = contents.size() > MAX_VISIBLE_SLOTS
        val shownItems = getShownItems(contents, contents.getNumberOfItemsToShow())
        val xStartPos = x + getContentXOffset(tooltipWidth) + GRID_WIDTH
        val yStartPos = y + gridSizeY(contents) * SLOT_CELL_SIZE
        var slotNumber = 1
        for (rowNumber in 1..gridSizeY(contents)) {
            for (columnNumber in 1..SLOT_COLUMNS) {
                val drawX = xStartPos - columnNumber * SLOT_CELL_SIZE
                val drawY = yStartPos - rowNumber * SLOT_CELL_SIZE
                if (isOverflowing && columnNumber * rowNumber == 1) {
                    extractCount(renderer, font, drawX, drawY, getAmountOfHiddenItems(contents, shownItems))
                } else if (shownItems.size >= slotNumber) {
                    extractSlot(renderer, font, contents, slotNumber, drawX, drawY, shownItems, slotNumber)
                    slotNumber++
                }
            }
        }
        extractSelectedItemTooltip(renderer, font, contents, x, y, tooltipWidth)
        extractProgressbar(renderer, font, x + getContentXOffset(tooltipWidth), y + gridSizeY(contents) * SLOT_CELL_SIZE + 4, weight)
    }

    private fun getShownItems(contents: BundleContents, amountOfItemsToShow: Int): List<ItemStackTemplate> {
        val lastToDisplay = minOf(contents.size(), amountOfItemsToShow)
        return contents.items().subList(0, lastToDisplay)
    }

    private fun getAmountOfHiddenItems(contents: BundleContents, shownItems: List<ItemStackTemplate>): Int =
        contents.items().drop(shownItems.size).sumOf { it.count() }

    private fun extractSlot(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        contents: BundleContents,
        slotNumber: Int,
        drawX: Int,
        drawY: Int,
        shownItems: List<ItemStackTemplate>,
        slotIndex: Int,
    ) {
        val itemVisualOrderIndex = shownItems.size - slotNumber
        val hasHighlight = itemVisualOrderIndex == contents.getSelectedItemIndex()
        val item = shownItems[itemVisualOrderIndex].create()
        if (hasHighlight) {
            renderer.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK_SPRITE, drawX, drawY, SLOT_CELL_SIZE, SLOT_CELL_SIZE, -1)
        } else {
            renderer.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_BACKGROUND_SPRITE, drawX, drawY, SLOT_CELL_SIZE, SLOT_CELL_SIZE, -1)
        }
        renderer.item(item, drawX + SLOT_MARGIN, drawY + SLOT_MARGIN, slotIndex)
        renderer.itemDecorations(font, item, drawX + SLOT_MARGIN, drawY + SLOT_MARGIN)
        if (hasHighlight) {
            renderer.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT_SPRITE, drawX, drawY, SLOT_CELL_SIZE, SLOT_CELL_SIZE, -1)
        }
    }

    private fun extractCount(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        drawX: Int,
        drawY: Int,
        hiddenItemCount: Int,
    ) {
        renderer.centeredText(font, "+$hiddenItemCount", drawX + 12, drawY + 10, -1)
    }

    private fun extractSelectedItemTooltip(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        contents: BundleContents,
        x: Int,
        y: Int,
        tooltipWidth: Int,
    ) {
        val selectedItem = contents.getSelectedItem() ?: return
        val itemStack = selectedItem.create()
        val name = itemStack.getStyledHoverName()
        val textWidth = font.width(name.getVisualOrderText())
        val centerTooltip = x + tooltipWidth / 2 - 12
        val subLines = listOf<TextTooltipLine>(TextTooltipLine(name.getVisualOrderText()))
        val subW = textWidth
        val subH = 10 - 2 // 单行 measure:tempHeight = -2 + 10
        val xo = centerTooltip - textWidth / 2
        val yo = y - 15
        val (screenW, screenH) = renderer.screenSize()
        val (sx, sy) = renderer.positionTooltip(xo, yo, subW, subH, screenW, screenH)
        renderer.renderTooltip(subLines, font, sx, sy, subW, subH, itemStack.get(DataComponents.TOOLTIP_STYLE))
    }

    private fun extractProgressbar(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        x: Int,
        y: Int,
        weight: Fraction,
    ) {
        renderer.blitSprite(
            RenderPipelines.GUI_TEXTURED, getProgressBarTexture(weight),
            x + PROGRESSBAR_BORDER, y, getProgressBarFill(weight), PROGRESSBAR_HEIGHT, -1,
        )
        renderer.blitSprite(RenderPipelines.GUI_TEXTURED, PROGRESSBAR_BORDER_SPRITE, x, y, PROGRESSBAR_WIDTH, PROGRESSBAR_HEIGHT, -1)
        val progressBarFillText = getProgressBarFillText(weight)
        if (progressBarFillText != null) {
            renderer.centeredText(font, progressBarFillText, x + 48, y + 3, -1)
        }
    }

    private fun getProgressBarFill(weight: Fraction): Int =
        Mth.clamp(Mth.mulAndTruncate(weight, PROGRESSBAR_FILL_MAX), 0, PROGRESSBAR_FILL_MAX)

    private fun getProgressBarTexture(weight: Fraction): Identifier =
        if (weight.compareTo(Fraction.ONE) >= 0) PROGRESSBAR_FULL_SPRITE else PROGRESSBAR_FILL_SPRITE

    private fun getProgressBarFillText(weight: Fraction): Component? = when {
        weight.compareTo(Fraction.ZERO) == 0 -> BUNDLE_EMPTY_TEXT
        weight.compareTo(Fraction.ONE) >= 0  -> BUNDLE_FULL_TEXT
        else                                 -> null
    }

    // ── ActivePlayers ──────────────────────────────────────────────

    private fun playersWidth(font: Font, activePlayers: List<PlayerSkinRenderCache.RenderInfo>): Int {
        var widest = 0
        for (activePlayer in activePlayers) {
            val width = font.width(activePlayer.gameProfile().name())
            if (width > widest) widest = width
        }
        return widest + 10 + 6
    }

    private fun renderActivePlayers(
        renderer: MinecraftTooltipRenderer,
        font: Font,
        activePlayers: List<PlayerSkinRenderCache.RenderInfo>,
        x: Int,
        y: Int,
    ) {
        for ((i, activePlayer) in activePlayers.withIndex()) {
            val y1 = y + ACTIVE_PLAYER_PADDING + i * ACTIVE_PLAYER_ROW_HEIGHT
            renderPlayerFace(renderer, activePlayer.playerSkin(), x + ACTIVE_PLAYER_PADDING, y1, ACTIVE_PLAYER_SKIN_SIZE)
            renderer.text(font, activePlayer.gameProfile().name(), x + ACTIVE_PLAYER_SKIN_SIZE + 4, y1 + ACTIVE_PLAYER_PADDING, -1)
        }
    }

    /** 原版 PlayerFaceExtractor.extractRenderState(hat=true, flip=false):base 头 + 帽子层。 */
    private fun renderPlayerFace(
        renderer: MinecraftTooltipRenderer,
        skin: PlayerSkin,
        x: Int,
        y: Int,
        size: Int,
    ) {
        val texture = skin.body().texturePath()
        val headV = 8f
        renderer.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 8f, headV, size, size, 8, 8, 64, 64, -1)
        renderer.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 40f, headV, size, size, 8, 8, 64, 64, -1)
    }
}