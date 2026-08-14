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
import androidx.compose.ui.text.StrongDirectionType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.firstStrongDirectionType
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.intl.isRtl
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import kotlin.math.ceil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font as MinecraftFont

// ─────────────────────────────────────────────────────────────────────────────
// Minecraft 平台文本后端(第一版)
//
// 设计说明:
// - 不依赖 Skia/ICU 排版。第一版采用等宽布局模型:字符宽 = 字号 * 0.5,
//   行高 = 字号 * 1.2,按空格贪心换行。
// - paint() 通过 MinecraftCanvas.recordTextDraw 记录文本绘制命令,
//   后续阶段用 MinecraftRenderContext 由 Minecraft 字体渲染器回放。
// - 该模型保证 Text/TextField 等文本组件的布局与测量语义可用,
//   精确排版在后续阶段替换为 Minecraft 字形度量。
// ─────────────────────────────────────────────────────────────────────────────

/** 一行文本的布局结果 */
internal class MinecraftTextLine(val start: Int, val end: Int, val width: Float)

/**
 * Minecraft Font 度量布局模型(阶段 E,方案 A)。
 *
 * 布局与渲染统一使用 Minecraft 字形度量:
 * - 行宽 = [Font.width](该行文本)(GUI 像素,场景密度 1f 下即场景像素);
 * - 行高 = [Font.lineHeight](9);
 * - 字号第一版忽略(统一 MC 原生 9px GUI 文本),fontSizePx 不再参与度量。
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

    /** 行平均字符宽(光标/命中测试用近似;逐字符精确度量后续阶段) */
    fun avgCharWidth(line: MinecraftTextLine): Float =
        if (line.end > line.start) line.width / (line.end - line.start) else 0f

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
 * Minecraft 平台 ParagraphIntrinsics 实现。 */
internal class MinecraftParagraphIntrinsics(
    val text: String,
    private val style: TextStyle,
    private val annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    private val placeholders: List<AnnotatedString.Range<Placeholder>>,
    private val density: Density,
    private val fontFamilyResolver: FontFamily.Resolver,
) : ParagraphIntrinsics {
    val textDirection = resolveTextDirection(text, style.textDirection, style.localeList)

    val fontSizePx: Float = with(density) { style.fontSize.takeOrElse { 14.sp }.toPx() }

    private val layout = MinecraftTextLayout(text, Float.POSITIVE_INFINITY)

    override val minIntrinsicWidth: Float = layout.minIntrinsicWidth

    override val maxIntrinsicWidth: Float = layout.maxIntrinsicWidth
}

/**
 * Minecraft 平台 Paragraph 实现。 */
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

    override fun getPathForRange(start: Int, end: Int): Path = Path()

    override fun getCursorRect(offset: Int): Rect {
        val lineIndex = lineForOffset(offset)
        val line = lineAt(lineIndex)
        val x = (offset - line.start).coerceIn(0, line.end - line.start) * layout.avgCharWidth(line)
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
        val line = lineAt(lineForOffset(offset))
        return (offset - line.start).coerceIn(0, line.end - line.start) * layout.avgCharWidth(line)
    }

    override fun getParagraphDirection(offset: Int): ResolvedTextDirection = intrinsics.textDirection

    override fun getBidiRunDirection(offset: Int): ResolvedTextDirection = intrinsics.textDirection

    override fun getLineForVerticalPosition(vertical: Float): Int =
        (vertical / layout.lineHeight).toInt().coerceIn(0, maxOf(0, layout.lines.size - 1))

    override fun getOffsetForPosition(position: Offset): Int {
        val lineIndex = getLineForVerticalPosition(position.y)
        val line = lineAt(lineIndex)
        val avg = layout.avgCharWidth(line)
        val rel = if (avg > 0f) (position.x / avg).toInt() else 0
        return line.start + rel.coerceIn(0, line.end - line.start)
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

    override fun getWordBoundary(offset: Int): TextRange = TextRange(offset, offset)

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
        val color = (brush as? SolidColor)?.value ?: Color.Black
        paint(canvas, color, alpha)
    }

    private fun paint(canvas: Canvas, color: Color, alpha: Float) {
        val mc = canvas as? MinecraftCanvas
            ?: throw UnsupportedOperationException(
                "MinecraftParagraph.paint 仅支持 MinecraftCanvas,实际: ${canvas::class.simpleName}"
            )
        val lineCount = visibleLineCount
        for (i in 0 until lineCount) {
            val line = layout.lines[i]
            if (line.end > line.start) {
                mc.recordTextDraw(
                    text = intrinsics.text.substring(line.start, line.end),
                    x = 0f,
                    y = i * layout.lineHeight,
                    color = color.copy(alpha = color.alpha * alpha),
                    fontSize = intrinsics.fontSizePx,
                )
            }
        }
    }
}

internal fun ActualParagraph(
    text: String,
    style: TextStyle,
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
    style: TextStyle,
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
    style: TextStyle,
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

internal fun resolveTextDirection(
    text: String,
    textDirection: TextDirection? = null,
    localeList: LocaleList? = null,
): ResolvedTextDirection {
    return when (textDirection ?: TextDirection.Content) {
        TextDirection.Ltr -> ResolvedTextDirection.Ltr
        TextDirection.Rtl -> ResolvedTextDirection.Rtl
        TextDirection.Content, TextDirection.Unspecified ->
            contentBasedTextDirection(text) { localeBasedTextDirection(localeList?.firstOrNull()) }
        TextDirection.ContentOrLtr -> contentBasedTextDirection(text) { ResolvedTextDirection.Ltr }
        TextDirection.ContentOrRtl -> contentBasedTextDirection(text) { ResolvedTextDirection.Rtl }
        else -> error("Invalid TextDirection.")
    }
}

private fun contentBasedTextDirection(text: String, fallback: () -> ResolvedTextDirection) =
    when (text.firstStrongDirectionType()) {
        StrongDirectionType.Ltr -> ResolvedTextDirection.Ltr
        StrongDirectionType.Rtl -> ResolvedTextDirection.Rtl
        else -> fallback()
    }

private fun localeBasedTextDirection(locale: Locale?) =
    if ((locale ?: Locale.current).isRtl()) {
        ResolvedTextDirection.Rtl
    } else {
        ResolvedTextDirection.Ltr
    }

/** 与官方 Paragraph.skiko.kt 一致的 DefaultMaxLines */
private val DefaultMaxLines = Int.MAX_VALUE
