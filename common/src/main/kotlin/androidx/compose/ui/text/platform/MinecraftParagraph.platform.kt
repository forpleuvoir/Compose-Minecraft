/*
 * Copyright 2026 forpleuvoir
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.compose.ui.text.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import kotlin.math.roundToInt
import moe.forpleuvoir.compose_minecraft.platform.ui.McTextStyle
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font as MinecraftFont

// ─────────────────────────────────────────────────────────────────────────────
// Minecraft 平台文本后端(文本系统 MC 化,T.2 + T.5 + T.6)
//
// 平台适配点(与官方 Paragraph.skiko.kt 的差异):
// - 样式类型为 [McTextStyle](MC Style 能力字段 1:1),TextStyle 已完全移除;
// - 度量统一用 MC [MinecraftFont]:行高 = Font.lineHeight(9),字号忽略;
// - 光标/命中/词界位置用**前缀精确宽度** Font.width(前缀)(T.5,同 EditBox.getScreenX);
// - 命中测试用 Font.plainSubstrByWidth(同 EditBox.findClickedPositionInText);
// - 水平滚动用 MC EditBox 的 displayPos 模型:截断绘制 + displayPos 跟随(T.6);
// - 富文本(AnnotatedString 多 SpanStyle)第一版不做,只支持统一 McTextStyle。
// ─────────────────────────────────────────────────────────────────────────────

/** 一行文本的布局结果 */
internal class MinecraftTextLine(val start: Int, val end: Int, val width: Float)

/**
 * Minecraft Font 度量布局模型。
 *
 * 布局与渲染统一使用 Minecraft 字形度量:
 * - 行宽 = [MinecraftFont.width](该行文本)(GUI 像素,场景密度 1f 下即场景像素);
 * - 行高 = [MinecraftFont.lineHeight](9);
 * - 字号第一版忽略(统一 MC 原生 9px GUI 文本)。
 */
internal class MinecraftTextLayout(
    val text: String,
    val maxWidth: Float,
) {
    private val font: MinecraftFont
        get() = Minecraft.getInstance().font

    val lineHeight: Float
        get() = font.lineHeight.toFloat()

    val lines: List<MinecraftTextLine> = computeLines()

    /** 段落宽度(最大行宽) */
    val width: Float
        get() = lines.maxOfOrNull { it.width } ?: 0f

    /** 段落高度 */
    val height: Float
        get() = lines.size * lineHeight

    /** 最宽单词宽度(MC 字形度量) */
    val minIntrinsicWidth: Float = run {
        var maxW = 0f
        var cur = 0
        while (cur < text.length) {
            if (text[cur] == ' ' || text[cur] == '\n') {
                cur++
                continue
            }
            val start = cur
            while (cur < text.length && text[cur] != ' ' && text[cur] != '\n') cur++
            maxW = maxOf(maxW, font.width(text.substring(start, cur)).toFloat())
        }
        maxW
    }

    /** 最宽自然行宽度(不换行,MC 字形度量) */
    val maxIntrinsicWidth: Float = run {
        var maxW = 0f
        var cur = 0
        while (cur < text.length) {
            val nl = text.indexOf('\n', cur)
            val end = if (nl == -1) text.length else nl
            maxW = maxOf(maxW, font.width(text.substring(cur, end)).toFloat())
            if (nl == -1) break
            cur = nl + 1
        }
        maxW
    }

    /**
     * 前缀精确宽度(T.5):文本 [from, to) 的 MC 字形宽度。
     * 替换第一版的 avgCharWidth 近似,与 MC EditBox.getScreenX 同源。
     */
    fun prefixWidth(from: Int, to: Int): Float {
        if (to <= from) return 0f
        return font.width(text.substring(from, to.coerceAtMost(text.length))).toFloat()
    }

    /**
     * 前缀截断(T.6):返回 [from] 起、宽度不超过 [maxWidth] 的最长前缀子串。
     * 与 MC EditBox 的 `font.plainSubstrByWidth(...)` 同源。
     */
    fun substrByWidth(from: Int, maxWidth: Int): String {
        if (from >= text.length) return ""
        return font.plainSubstrByWidth(text.substring(from), maxWidth.coerceAtLeast(0))
    }

    private fun computeLines(): List<MinecraftTextLine> {
        val result = ArrayList<MinecraftTextLine>()
        if (text.isEmpty()) {
            result.add(MinecraftTextLine(0, 0, 0f))
            return result
        }
        val wrap = maxWidth.isFinite() && maxWidth > 0
        var segStart = 0
        while (segStart < text.length) {
            val nl = text.indexOf('\n', segStart)
            val segEnd = if (nl == -1) text.length else nl
            if (segStart == segEnd) {
                result.add(MinecraftTextLine(segStart, segEnd, 0f))
            } else if (wrap) {
                var start = segStart
                while (start < segEnd) {
                    var end = start
                    var lastSpace = -1
                    // 前缀宽度用 MC 字形度量判定换行
                    while (end < segEnd && font.width(text.substring(start, end + 1)) <= maxWidth) {
                        if (text[end] == ' ') lastSpace = end
                        end++
                    }
                    if (end == segEnd) {
                        result.add(
                            MinecraftTextLine(start, segEnd, font.width(text.substring(start, segEnd)).toFloat())
                        )
                        start = segEnd
                    } else {
                        val lineEnd = if (lastSpace > start) lastSpace else end
                        result.add(
                            MinecraftTextLine(start, lineEnd, font.width(text.substring(start, lineEnd)).toFloat())
                        )
                        start = if (lastSpace > start) lastSpace + 1 else end
                    }
                }
            } else {
                result.add(
                    MinecraftTextLine(segStart, segEnd, font.width(text.substring(segStart, segEnd)).toFloat())
                )
            }
            if (nl == -1) break
            segStart = nl + 1
            if (segStart == text.length) {
                result.add(MinecraftTextLine(segStart, segStart, 0f))
                break
            }
        }
        return result
    }
}

/**
 * Minecraft 平台 ParagraphIntrinsics 实现。
 * 平台适配点(T.2):样式参数 TextStyle → [McTextStyle];
 * 排版方向固定 LTR(MC 文本第一版不支持 BiDi),fontSize/字号被忽略。
 */
internal class MinecraftParagraphIntrinsics(
    val text: String,
    internal val style: McTextStyle,
    private val annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    private val placeholders: List<AnnotatedString.Range<Placeholder>>,
    private val density: Density,
    private val fontFamilyResolver: FontFamily.Resolver,
) : ParagraphIntrinsics {
    val textDirection: ResolvedTextDirection = ResolvedTextDirection.Ltr

    private val layout = MinecraftTextLayout(text, Float.POSITIVE_INFINITY)

    override val minIntrinsicWidth: Float = layout.minIntrinsicWidth

    override val maxIntrinsicWidth: Float = layout.maxIntrinsicWidth
}

/**
 * Minecraft 平台 Paragraph 实现。
 * 平台适配点(T.5/T.6):精确前缀度量 + MC EditBox 风格 displayPos 水平滚动。
 */
internal class MinecraftParagraph(
    private val intrinsics: MinecraftParagraphIntrinsics,
    private val maxLines: Int,
    private val overflow: TextOverflow,
    private val constraints: Constraints,
) : Paragraph {

    private val layout: MinecraftTextLayout = run {
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat()
        else Float.POSITIVE_INFINITY
        MinecraftTextLayout(intrinsics.text, maxWidth)
    }

    private val visibleLineCount: Int =
        if (maxLines != DefaultMaxLines) minOf(layout.lines.size, maxLines) else layout.lines.size

    /**
     * 平台适配点(T.6):MC EditBox 风格的水平滚动 —— 文本左端被截断的字符数。
     * 由 TextField 绘制/命中路径驱动(对应 MC EditBox.displayPos);普通文本组件恒为 0。
     */
    internal var displayPos: Int = 0
        set(value) {
            field = value.coerceIn(0, intrinsics.text.length)
        }

    /**
     * 平台适配点(T.6):可见区域宽度(TextField 视口宽度,GUI 像素)。
     * <= 0 表示未启用截断(普通文本组件,行宽以布局约束为准)。
     */
    internal var visibleWidth: Float = -1f

    /**
     * 平台适配点(T.6):MC EditBox.scrollTo 同源的 displayPos 更新。
     * 保证 [cursorOffset] 始终位于可见区域内;光标越右则左端截断越多。
     */
    internal fun updateDisplayPosFor(cursorOffset: Int, viewportWidth: Float) {
        val innerWidth = viewportWidth.roundToInt().coerceAtLeast(0)
        if (innerWidth <= 0) {
            displayPos = 0
            return
        }
        val text = intrinsics.text
        var pos = minOf(displayPos, text.length)
        val displayed = font.plainSubstrByWidth(text.substring(pos), innerWidth)
        val lastPos = displayed.length + pos
        if (cursorOffset == pos) {
            pos -= font.plainSubstrByWidth(text, innerWidth, true).length
        }
        if (cursorOffset > lastPos) {
            pos += cursorOffset - lastPos
        } else if (cursorOffset <= pos) {
            pos -= pos - cursorOffset
        }
        displayPos = pos
    }

    private val font: MinecraftFont
        get() = Minecraft.getInstance().font

    override val width: Float get() = layout.width

    override val height: Float get() = visibleLineCount * layout.lineHeight

    override val minIntrinsicWidth: Float get() = intrinsics.minIntrinsicWidth

    override val maxIntrinsicWidth: Float get() = intrinsics.maxIntrinsicWidth

    override val firstBaseline: Float get() = layout.lineHeight * 0.8f

    override val lastBaseline: Float
        get() = (visibleLineCount - 1) * layout.lineHeight + layout.lineHeight * 0.8f

    override val didExceedMaxLines: Boolean
        get() = maxLines != DefaultMaxLines && layout.lines.size > maxLines

    override val lineCount: Int get() = visibleLineCount

    override val placeholderRects: List<Rect?> get() = emptyList()

    private fun lineAt(index: Int): MinecraftTextLine {
        val i = index.coerceIn(0, layout.lines.size - 1)
        return layout.lines[i]
    }

    private fun lineForOffset(offset: Int): Int {
        val clamped = offset.coerceIn(0, intrinsics.text.length)
        for (i in layout.lines.indices) {
            val line = layout.lines[i]
            if (clamped >= line.start && clamped <= line.end) return i
        }
        return layout.lines.size - 1
    }

    /** 行的绘制起点(T.6:首行受 displayPos 截断影响) */
    private fun lineDrawStart(lineIndex: Int, line: MinecraftTextLine): Int {
        if (lineIndex != 0) return line.start
        return (line.start + displayPos).coerceAtMost(line.end)
    }

    override fun getPathForRange(start: Int, end: Int): Path = Path()

    override fun getCursorRect(offset: Int): Rect {
        val lineIndex = lineForOffset(offset)
        val line = lineAt(lineIndex)
        val start = lineDrawStart(lineIndex, line)
        // T.5:前缀精确宽度(EditBox.getScreenX 同源)
        val x = layout.prefixWidth(start, offset.coerceIn(start, line.end))
        val top = lineIndex * layout.lineHeight
        return Rect(x, top, x, top + layout.lineHeight)
    }

    override fun getLineLeft(lineIndex: Int): Float = 0f

    override fun getLineRight(lineIndex: Int): Float = lineAt(lineIndex).width

    override fun getLineTop(lineIndex: Int): Float = lineIndex * layout.lineHeight

    override fun getLineBaseline(lineIndex: Int): Float =
        lineIndex * layout.lineHeight + layout.lineHeight * 0.8f

    override fun getLineBottom(lineIndex: Int): Float =
        (lineIndex + 1) * layout.lineHeight

    override fun getLineHeight(lineIndex: Int): Float = layout.lineHeight

    override fun getLineWidth(lineIndex: Int): Float = lineAt(lineIndex).width

    override fun getLineStart(lineIndex: Int): Int = lineAt(lineIndex).start

    override fun getLineEnd(lineIndex: Int, visibleEnd: Boolean): Int = lineAt(lineIndex).end

    override fun isLineEllipsized(lineIndex: Int): Boolean = false

    override fun getLineForOffset(offset: Int): Int = lineForOffset(offset)

    override fun getHorizontalPosition(offset: Int, usePrimaryDirection: Boolean): Float {
        val lineIndex = lineForOffset(offset)
        val line = lineAt(lineIndex)
        val start = lineDrawStart(lineIndex, line)
        return layout.prefixWidth(start, offset.coerceIn(start, line.end))
    }

    override fun getParagraphDirection(offset: Int): ResolvedTextDirection = intrinsics.textDirection

    override fun getBidiRunDirection(offset: Int): ResolvedTextDirection = intrinsics.textDirection

    override fun getLineForVerticalPosition(vertical: Float): Int =
        (vertical / layout.lineHeight).toInt().coerceIn(0, maxOf(0, layout.lines.size - 1))

    override fun getOffsetForPosition(position: Offset): Int {
        val lineIndex = getLineForVerticalPosition(position.y)
        val line = lineAt(lineIndex)
        val start = lineDrawStart(lineIndex, line)
        // T.5/T.6:前缀宽度定位(EditBox.findClickedPositionInText 同源)
        val rel = font
            .plainSubstrByWidth(
                intrinsics.text.substring(start, line.end),
                position.x.roundToInt().coerceAtLeast(0),
            ).length
        return (start + rel).coerceIn(line.start, line.end)
    }

    override fun getRangeForRect(
        rect: Rect,
        granularity: androidx.compose.ui.text.TextGranularity,
        inclusionStrategy: androidx.compose.ui.text.TextInclusionStrategy,
    ): TextRange = TextRange(0, intrinsics.text.length)

    override fun getBoundingBox(offset: Int): Rect = getCursorRect(offset)

    override fun fillBoundingBoxes(
        range: TextRange,
        array: FloatArray,
        @androidx.annotation.IntRange(from = 0) arrayStart: Int,
    ) {
        val start = range.start.coerceIn(0, intrinsics.text.length)
        val end = range.end.coerceIn(0, intrinsics.text.length)
        var idx = arrayStart
        for (offset in start until end) {
            val rect = getCursorRect(offset)
            if (idx + 4 <= array.size) {
                array[idx++] = rect.left
                array[idx++] = rect.top
                array[idx++] = rect.right
                array[idx++] = rect.bottom
            }
        }
    }

    override fun getWordBoundary(offset: Int): TextRange {
        val text = intrinsics.text
        if (offset < 0 || offset >= text.length) {
            return TextRange(offset.coerceIn(0, text.length))
        }
        val c = text[offset]
        if (!c.isWordChar()) {
            // 非单词字符(空格/标点):只包含该字符本身
            return TextRange(offset, (offset + 1).coerceAtMost(text.length))
        }
        var start = offset
        while (start > 0 && text[start - 1].isWordChar()) start--
        var end = offset
        while (end < text.length && text[end].isWordChar()) end++
        return TextRange(start, end)
    }

    override fun paint(
        canvas: Canvas,
        color: Color,
        shadow: Shadow?,
        textDecoration: TextDecoration?,
    ) = paint(canvas, color, 1f)

    override fun paint(
        canvas: Canvas,
        color: Color,
        shadow: Shadow?,
        textDecoration: TextDecoration?,
        drawStyle: DrawStyle?,
        blendMode: BlendMode,
    ) = paint(canvas, color, 1f)

    override fun paint(
        canvas: Canvas,
        brush: Brush,
        alpha: Float,
        shadow: Shadow?,
        textDecoration: TextDecoration?,
        drawStyle: DrawStyle?,
        blendMode: BlendMode,
    ) {
        // 平台适配点:Brush 只支持纯色(SolidColor),渐变第一版不支持
        val color = (brush as? SolidColor)?.value ?: Color.White
        paint(canvas, color, alpha)
    }

    private fun paint(canvas: Canvas, color: Color, alpha: Float) {
        val mc = canvas as? MinecraftCanvas
            ?: throw UnsupportedOperationException(
                "MinecraftParagraph.paint 仅支持 MinecraftCanvas,实际: ${canvas::class.simpleName}"
            )
        // T.1/T.2:记录完整 MC 样式快照;paint 传入的 color 覆盖样式色
        // (shadow/textDecoration 等 Compose 绘制参数已被 McTextStyle 能力取代,第一版忽略)
        val style = intrinsics.style.copy(
            color = color.copy(alpha = color.alpha * alpha),
        )
        val lineCount = visibleLineCount
        for (i in 0 until lineCount) {
            val line = layout.lines[i]
            val start = lineDrawStart(i, line)
            if (line.end > start) {
                // T.6:displayPos 截断 + 视口宽度截断(EditBox:plainSubstrByWidth)
                val drawText =
                    if (i == 0 && visibleWidth > 0f && visibleWidth.isFinite()) {
                        layout.substrByWidth(start, visibleWidth.roundToInt())
                    } else {
                        intrinsics.text.substring(start, line.end)
                    }
                if (drawText.isNotEmpty()) {
                    mc.recordTextDraw(
                        text = drawText,
                        x = 0f,
                        y = i * layout.lineHeight,
                        style = style,
                    )
                }
            }
        }
    }
}

private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

internal fun ActualParagraph(
    text: String,
    style: McTextStyle,
    annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    maxLines: Int,
    ellipsis: Boolean,
    width: Float,
    density: Density,
    @Suppress("DEPRECATION") resourceLoader: Font.ResourceLoader,
): Paragraph = ActualParagraph(
    text = text,
    style = style,
    annotations = annotations,
    placeholders = placeholders,
    maxLines = maxLines,
    overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
    constraints = Constraints(maxWidth = ceil(width).toInt()),
    density = density,
    fontFamilyResolver = androidx.compose.ui.text.font.createFontFamilyResolver(resourceLoader),
)

internal fun ActualParagraph(
    text: String,
    style: McTextStyle,
    annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    maxLines: Int,
    overflow: TextOverflow,
    constraints: Constraints,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
): Paragraph = MinecraftParagraph(
    MinecraftParagraphIntrinsics(
        text = text,
        style = style,
        annotations = annotations,
        placeholders = placeholders,
        density = density,
        fontFamilyResolver = fontFamilyResolver,
    ),
    maxLines,
    overflow,
    constraints,
)

internal fun ActualParagraph(
    paragraphIntrinsics: ParagraphIntrinsics,
    maxLines: Int,
    overflow: TextOverflow,
    constraints: Constraints,
): Paragraph = MinecraftParagraph(
    paragraphIntrinsics as MinecraftParagraphIntrinsics,
    maxLines,
    overflow,
    constraints,
)

internal fun ActualParagraphIntrinsics(
    text: String,
    style: McTextStyle,
    annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
): ParagraphIntrinsics = MinecraftParagraphIntrinsics(
    text = text,
    style = style,
    annotations = annotations,
    placeholders = placeholders,
    density = density,
    fontFamilyResolver = fontFamilyResolver,
)

/** 与官方 Paragraph.skiko.kt 一致的 DefaultMaxLines */
private val DefaultMaxLines = Int.MAX_VALUE
