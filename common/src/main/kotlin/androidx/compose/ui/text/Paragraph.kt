/*
 * Copyright 2019 The Android Open Source Project
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
package androidx.compose.ui.text

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.internal.JvmDefaultWithCompatibility
import androidx.compose.ui.text.platform.ActualParagraph
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import net.minecraft.network.chat.Style

internal const val DefaultMaxLines = Int.MAX_VALUE
@JvmDefaultWithCompatibility
interface Paragraph {
    /** The amount of horizontal space this paragraph occupies. */
    val width: Float
    /** The amount of vertical space this paragraph occupies. */
    val height: Float
    /** The width for text if all soft wrap opportunities were taken. */
    val minIntrinsicWidth: Float
    /** Returns the smallest width beyond which increasing the width never decreases the height. */
    val maxIntrinsicWidth: Float
    val firstBaseline: Float
    val lastBaseline: Float
    val didExceedMaxLines: Boolean
    val lineCount: Int
    val placeholderRects: List<androidx.compose.ui.geometry.Rect?>
    fun getPathForRange(start: Int, end: Int): androidx.compose.ui.graphics.Path
    fun getCursorRect(offset: Int): androidx.compose.ui.geometry.Rect
    fun getLineLeft(lineIndex: Int): Float
    fun getLineRight(lineIndex: Int): Float
    fun getLineTop(lineIndex: Int): Float
    fun getLineBaseline(lineIndex: Int): Float
    fun getLineBottom(lineIndex: Int): Float
    fun getLineHeight(lineIndex: Int): Float
    fun getLineWidth(lineIndex: Int): Float
    fun getLineStart(lineIndex: Int): Int
    fun getLineEnd(lineIndex: Int, visibleEnd: Boolean): Int
    fun isLineEllipsized(lineIndex: Int): Boolean
    fun getLineForOffset(offset: Int): Int
    fun getHorizontalPosition(offset: Int, usePrimaryDirection: Boolean): Float
    fun getParagraphDirection(offset: Int): androidx.compose.ui.text.style.ResolvedTextDirection
    fun getBidiRunDirection(offset: Int): androidx.compose.ui.text.style.ResolvedTextDirection
    fun getLineForVerticalPosition(vertical: Float): Int
    fun getOffsetForPosition(position: androidx.compose.ui.geometry.Offset): Int
    fun getRangeForRect(
        rect: androidx.compose.ui.geometry.Rect,
        granularity: TextGranularity,
        inclusionStrategy: TextInclusionStrategy,
    ): TextRange
    fun getBoundingBox(offset: Int): androidx.compose.ui.geometry.Rect
    fun fillBoundingBoxes(
        range: TextRange,
        array: FloatArray,
        @androidx.annotation.IntRange(from = 0) arrayStart: Int,
    )
    fun getWordBoundary(offset: Int): TextRange
    fun paint(
        canvas: Canvas,
        color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
        shadow: androidx.compose.ui.graphics.Shadow? = null,
        textDecoration: androidx.compose.ui.text.style.TextDecoration? = null,
    )
    fun paint(
        canvas: Canvas,
        color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
        shadow: androidx.compose.ui.graphics.Shadow? = null,
        textDecoration: androidx.compose.ui.text.style.TextDecoration? = null,
        drawStyle: androidx.compose.ui.graphics.drawscope.DrawStyle? = null,
        blendMode: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.drawscope.DrawScope.DefaultBlendMode,
    )
    fun paint(
        canvas: Canvas,
        brush: androidx.compose.ui.graphics.Brush,
        alpha: Float = Float.NaN,
        shadow: androidx.compose.ui.graphics.Shadow? = null,
        textDecoration: androidx.compose.ui.text.style.TextDecoration? = null,
        drawStyle: androidx.compose.ui.graphics.drawscope.DrawStyle? = null,
        blendMode: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.drawscope.DrawScope.DefaultBlendMode,
    )
}

/**
 * A paragraph of text that is laid out.
 *
 * Paragraphs can be displayed on a [Canvas] using the [paint] method.
 */
fun Paragraph(
    text: String,
    style: Style,
    spanStyles: List<AnnotatedString.Range<SpanStyle>> = listOf(),
    placeholders: List<AnnotatedString.Range<Placeholder>> = listOf(),
    maxLines: Int = DefaultMaxLines,
    ellipsis: Boolean = false,
    width: Float,
    density: Density,
    resourceLoader: Font.ResourceLoader,
    // 平台适配点:段落水平对齐(默认未设置 = 旧行为)
    textAlign: TextAlign = TextAlign.Unspecified,
): Paragraph =
    ActualParagraph(
        text,
        style,
        spanStyles,
        placeholders,
        maxLines,
        ellipsis,
        width,
        density,
        resourceLoader,
        textAlign = textAlign,
    )

/**
 * Lays out a given [text] with the given constraints. A paragraph is a text that has a single
 * [ParagraphStyle].
 *
 * If the [style] does not contain any [androidx.compose.ui.text.style.TextDirection],
 * [androidx.compose.ui.text.style.TextDirection.Content] is used as the default value.
 *
 * @param text the text to be laid out
 * @param style the [Style] to be applied to the whole text
 * @param width how wide the text is allowed to be
 * @param density density of the device
 * @param fontFamilyResolver [FontFamily.Resolver] to be used to load the font given in [SpanStyle]s
 * @param spanStyles [SpanStyle]s to be applied to parts of text
 * @param placeholders a list of placeholder metrics which tells [Paragraph] where should be left
 *   blank to leave space for inline elements.
 * @param maxLines the maximum number of lines that the text can have
 * @param ellipsis whether to ellipsize text, applied only when [maxLines] is set
 * @throws IllegalArgumentException if [ParagraphStyle.textDirection] is not set
 */
@Deprecated(
    "Paragraph that takes maximum allowed width is deprecated, pass constraints instead.",
    ReplaceWith(
        "Paragraph(text, style, Constraints(maxWidth = ceil(width).toInt()), density, " +
            "fontFamilyResolver, spanStyles, placeholders, maxLines, ellipsis)",
        "kotlin.math.ceil",
        "androidx.compose.ui.unit.Constraints",
    ),
)
fun Paragraph(
    text: String,
    style: Style,
    width: Float,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    spanStyles: List<AnnotatedString.Range<SpanStyle>> = listOf(),
    placeholders: List<AnnotatedString.Range<Placeholder>> = listOf(),
    maxLines: Int = DefaultMaxLines,
    ellipsis: Boolean = false,
    // 平台适配点:段落水平对齐(默认未设置 = 旧行为)
    textAlign: TextAlign = TextAlign.Unspecified,
): Paragraph =
    ActualParagraph(
        text,
        style,
        spanStyles,
        placeholders,
        maxLines,
        if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        Constraints(maxWidth = width.ceilToInt()),
        density,
        fontFamilyResolver,
        textAlign = textAlign,
    )

/**
 * Lays out a given [text] with the given constraints. A paragraph is a text that has a single
 * [ParagraphStyle].
 *
 * If the [style] does not contain any [androidx.compose.ui.text.style.TextDirection],
 * [androidx.compose.ui.text.style.TextDirection.Content] is used as the default value.
 *
 * @param text the text to be laid out
 * @param style the [Style] to be applied to the whole text
 * @param constraints how wide and tall the text is allowed to be. [Constraints.maxWidth] will
 *   define the width of the Paragraph. [Constraints.maxHeight] helps defining the number of lines
 *   that fit with ellipsis is true. Minimum components of the [Constraints] object are no-op.
 * @param density density of the device
 * @param fontFamilyResolver [FontFamily.Resolver] to be used to load the font given in [SpanStyle]s
 * @param spanStyles [SpanStyle]s to be applied to parts of text
 * @param placeholders a list of placeholder metrics which tells [Paragraph] where should be left
 *   blank to leave space for inline elements.
 * @param maxLines the maximum number of lines that the text can have
 * @param ellipsis whether to ellipsize text, applied only when [maxLines] is set
 * @throws IllegalArgumentException if [ParagraphStyle.textDirection] is not set
 */
@Deprecated(
    "Paragraph that takes `ellipsis: Boolean` is deprecated, pass TextOverflow instead.",
    level = DeprecationLevel.HIDDEN,
)
fun Paragraph(
    text: String,
    style: Style,
    constraints: Constraints,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    spanStyles: List<AnnotatedString.Range<SpanStyle>> = listOf(),
    placeholders: List<AnnotatedString.Range<Placeholder>> = listOf(),
    maxLines: Int = DefaultMaxLines,
    ellipsis: Boolean = false,
    // 平台适配点:段落水平对齐(默认未设置 = 旧行为)
    textAlign: TextAlign = TextAlign.Unspecified,
): Paragraph =
    ActualParagraph(
        text,
        style,
        spanStyles,
        placeholders,
        maxLines,
        if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        constraints,
        density,
        fontFamilyResolver,
        textAlign = textAlign,
    )

/**
 * Lays out a given [text] with the given constraints. A paragraph is a text that has a single
 * [ParagraphStyle].
 *
 * If the [style] does not contain any [androidx.compose.ui.text.style.TextDirection],
 * [androidx.compose.ui.text.style.TextDirection.Content] is used as the default value.
 *
 * @param text the text to be laid out
 * @param style the [Style] to be applied to the whole text
 * @param constraints how wide and tall the text is allowed to be. [Constraints.maxWidth] will
 *   define the width of the Paragraph. [Constraints.maxHeight] helps defining the number of lines
 *   that fit with ellipsis is true. Minimum components of the [Constraints] object are no-op.
 * @param density density of the device
 * @param fontFamilyResolver [FontFamily.Resolver] to be used to load the font given in [SpanStyle]s
 * @param spanStyles [SpanStyle]s to be applied to parts of text
 * @param placeholders a list of placeholder metrics which tells [Paragraph] where should be left
 *   blank to leave space for inline elements.
 * @param maxLines the maximum number of lines that the text can have
 * @param overflow specifies how visual overflow should be handled
 * @throws IllegalArgumentException if [ParagraphStyle.textDirection] is not set
 */
fun Paragraph(
    text: String,
    style: Style,
    constraints: Constraints,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    spanStyles: List<AnnotatedString.Range<SpanStyle>> = listOf(),
    placeholders: List<AnnotatedString.Range<Placeholder>> = listOf(),
    maxLines: Int = DefaultMaxLines,
    overflow: TextOverflow = TextOverflow.Clip,
    // 平台适配点:段落水平对齐(默认未设置 = 旧行为)
    textAlign: TextAlign = TextAlign.Unspecified,
): Paragraph =
    ActualParagraph(
        text,
        style,
        spanStyles,
        placeholders,
        maxLines,
        overflow,
        constraints,
        density,
        fontFamilyResolver,
        textAlign = textAlign,
    )

/**
 * Lays out the text in [ParagraphIntrinsics] with the given constraints. A paragraph is a text that
 * has a single [ParagraphStyle].
 *
 * @param paragraphIntrinsics [ParagraphIntrinsics] instance
 * @param maxLines the maximum number of lines that the text can have
 * @param ellipsis whether to ellipsize text, applied only when [maxLines] is set
 * @param width how wide the text is allowed to be
 */
@Deprecated(
    "Paragraph that takes maximum allowed width is deprecated, pass constraints instead.",
    ReplaceWith(
        "Paragraph(paragraphIntrinsics, Constraints(maxWidth = ceil(width).toInt()), maxLines, " +
            "ellipsis)",
        "kotlin.math.ceil",
        "androidx.compose.ui.unit.Constraints",
    ),
)
fun Paragraph(
    paragraphIntrinsics: ParagraphIntrinsics,
    maxLines: Int = DefaultMaxLines,
    ellipsis: Boolean = false,
    width: Float,
): Paragraph =
    ActualParagraph(
        paragraphIntrinsics,
        maxLines,
        if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        Constraints(maxWidth = width.ceilToInt()),
    )

/**
 * Lays out the text in [ParagraphIntrinsics] with the given constraints. A paragraph is a text that
 * has a single [ParagraphStyle].
 *
 * @param paragraphIntrinsics [ParagraphIntrinsics] instance
 * @param constraints how wide and tall the text is allowed to be. [Constraints.maxWidth] will
 *   define the width of the Paragraph. [Constraints.maxHeight] helps defining the number of lines
 *   that fit with ellipsis is true. Minimum components of the [Constraints] object are no-op.
 * @param maxLines the maximum number of lines that the text can have
 * @param ellipsis whether to ellipsize text, applied only when [maxLines] is set
 */
@Deprecated(
    "Paragraph that takes ellipsis: Boolean is deprecated, pass TextOverflow instead.",
    ReplaceWith(
        "Paragraph(paragraphIntrinsics, constraints, maxLines, " +
            "if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip)"
    ),
    level = DeprecationLevel.HIDDEN,
)
fun Paragraph(
    paragraphIntrinsics: ParagraphIntrinsics,
    constraints: Constraints,
    maxLines: Int = DefaultMaxLines,
    ellipsis: Boolean = false,
): Paragraph =
    ActualParagraph(
        paragraphIntrinsics,
        maxLines,
        if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        constraints,
    )

/**
 * Lays out the text in [ParagraphIntrinsics] with the given constraints. A paragraph is a text that
 * has a single [ParagraphStyle].
 *
 * @param paragraphIntrinsics [ParagraphIntrinsics] instance
 * @param constraints how wide and tall the text is allowed to be. [Constraints.maxWidth] will
 *   define the width of the Paragraph. [Constraints.maxHeight] helps defining the number of lines
 *   that fit with ellipsis is true. Minimum components of the [Constraints] object are no-op.
 * @param maxLines the maximum number of lines that the text can have
 * @param overflow specifies how visual overflow should be handled
 */
fun Paragraph(
    paragraphIntrinsics: ParagraphIntrinsics,
    constraints: Constraints,
    maxLines: Int = DefaultMaxLines,
    overflow: TextOverflow = TextOverflow.Clip,
): Paragraph = ActualParagraph(paragraphIntrinsics, maxLines, overflow, constraints)

internal fun Float.ceilToInt(): Int = ceil(this).toInt()
