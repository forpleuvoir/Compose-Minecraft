/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.compose.foundation.text.modifiers

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.text.DefaultMinLines
import androidx.compose.foundation.text.ceilToIntPx
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.StyleSegment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.isOffsetAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrain
import kotlin.jvm.JvmInline
import net.minecraft.network.chat.Style

/**
 * Performs text layout using [Paragraph].
 *
 * Results are cached whenever possible, for example when only constraints change in a way that
 * cannot reflow text.
 *
 * All measurements are cached.
 */
internal class ParagraphLayoutCache(
    private var text: String,
    private var style: Style,
    private var fontFamilyResolver: FontFamily.Resolver,
    private var overflow: TextOverflow = TextOverflow.Clip,
    private var softWrap: Boolean = true,
    private var maxLines: Int = Int.MAX_VALUE,
    private var minLines: Int = DefaultMinLines,
    /** 平台适配点:MC Component 展平后的多段样式;空 = 单样式(旧行为)。 */
    private var segments: List<StyleSegment> = emptyList(),
    /** 平台适配点:文本缩放;1f = 原样。 */
    private var scale: Float = 1f,
    /** 平台适配点:段落水平对齐(布局端逐行计算起点偏移)。 */
    private var textAlign: TextAlign = TextAlign.Unspecified,
) {

    /**
     * Density is an interface which makes it behave like a provider, rather than a final class.
     * Whenever Density changes, the object itself may remain the same, making the below density
     * variable mutate internally. This value holds the last seen density whenever Compose sends us
     * a Density may have changed notification via layout or draw phase.
     */
    private var lastDensity: InlineDensity = InlineDensity.Unspecified

    /** Density that text layout is performed in */
    internal var density: Density? = null
        set(value) {
            val localField = field
            val newDensity = value?.let { InlineDensity(it) } ?: InlineDensity.Unspecified
            if (localField == null) {
                field = value
                lastDensity = newDensity
                return
            }

            if (value == null || lastDensity != newDensity) {
                field = value
                lastDensity = newDensity
                recordHistory(LayoutCacheOperation.MarkDirtyDensity)
                markDirty()
            }
        }

    /** Read to set up a snapshot observer observe changes to fonts. */
    internal val observeFontChanges: Unit
        get() {
            paragraphIntrinsics?.hasStaleResolvedFonts
        }

    /** The last computed paragraph */
    internal var paragraph: Paragraph? = null

    /** The text did overflow */
    internal var didOverflow: Boolean = false

    /** The last computed layout size (as would have been reported in TextLayoutResult) */
    internal var layoutSize: IntSize = IntSize(0, 0)

    /** Convert min max lines into actual constraints */
    private var mMinLinesConstrainer: MinLinesConstrainer? = null

    /** [ParagraphIntrinsics] will be initialized lazily */
    private var paragraphIntrinsics: ParagraphIntrinsics? = null

    /** [LayoutDirection] used to compute [ParagraphIntrinsics] */
    private var intrinsicsLayoutDirection: LayoutDirection? = null

    /** Constraints passed to last layout. */
    private var prevConstraints: Constraints = Constraints.fixed(0, 0)

    /** Input width for the last call to [intrinsicHeight] */
    private var cachedIntrinsicHeightInputWidth: Int = -1

    /** Output height for last call to [intrinsicHeight] at [cachedIntrinsicHeightInputWidth] */
    private var cachedIntrinsicHeight: Int = -1

    /**
     * A 64-bit flag that records the history of `markDirty`, `markStyleDirty`, and
     * `layoutWithConstraints` operations. Each 2-bit segment represents a distinct operation.
     * Consequently, this flag maintains a record of the last 32 operations performed on this cache.
     *
     * Bit representation:
     * ```
     *   | Operation                | Bits |
     *   | :----------------------- | :--- |
     *   | markStyleDirty           | 00   |
     *   | markDirtyDensity         | 01   |
     *   | markDirtyNodeUpdate      | 10   |
     *   | layoutWithConstraints    | 11   |
     * ```
     *
     * With the operations encoded in 2 bit segments and read from right to left. For example:
     * ```
     *   01111000 would represent that the last 4 operations performed were
     *   1. markStyleDirty (00)
     *   2. markDirtyNodeUpdate (10)
     *   3. layoutWithConstraints (11)
     *   4. markDirtyDensity (01)
     * ```
     *
     * This history can be used to debug or print as a log of what operations have been performed on
     * this [MultiParagraphLayoutCache].
     */
    @VisibleForTesting internal var historyFlag: Long = 0L

    private fun recordHistory(op: LayoutCacheOperation) {
        historyFlag = (historyFlag shl 2) or op.flag
    }

    /**
     * Update layout constraints for this text
     *
     * @return true if constraints caused a text layout invalidation
     */
    fun layoutWithConstraints(constraints: Constraints, layoutDirection: LayoutDirection): Boolean {
        recordHistory(LayoutCacheOperation.LayoutWithConstraints)
        val finalConstraints =
            if (minLines > 1) {
                useMinLinesConstrainer(constraints, layoutDirection)
            } else {
                constraints
            }

        if (!newLayoutWillBeDifferent(finalConstraints, layoutDirection)) {
            if (finalConstraints != prevConstraints) {
                // ensure size and overflow is still accurate
                val localParagraph = paragraph!!
                val layoutWidth = occupiedWidth(finalConstraints, localParagraph.width)
                val localSize =
                    finalConstraints.constrain(
                        IntSize(layoutWidth.ceilToIntPx(), localParagraph.height.ceilToIntPx())
                    )
                layoutSize = localSize
                didOverflow =
                    overflow != TextOverflow.Visible &&
                        (localSize.width < localParagraph.width ||
                            localSize.height < localParagraph.height)
                prevConstraints = finalConstraints
            }
            return false
        }

        paragraph =
            layoutText(finalConstraints, layoutDirection).also {
                prevConstraints = finalConstraints
                val localSize =
                    finalConstraints.constrain(
                        IntSize(
                            occupiedWidth(finalConstraints, it.width).ceilToIntPx(),
                            it.height.ceilToIntPx(),
                        )
                    )
                layoutSize = localSize
                didOverflow =
                    overflow != TextOverflow.Visible &&
                        (localSize.width < it.width || localSize.height < it.height)
            }
        return true
    }

    /**
     * 平台适配点:文本节点**占用宽度**。
     *
     * 默认 = 段落内容宽度([contentWidth],本平台 `Paragraph.width` 语义 = 最大行宽)。
     * 但请求了行偏移对齐(`Center`/`Right`/`End`)且容器宽度有界时,占用整个容器宽度:
     * 对齐会把行推离内容盒,若节点仍按内容宽上报,上层
     * `TextLayoutResult.hasVisualOverflow`(`size.width < 段落宽`)成立 → 偏移后的行被裁掉。
     * 语义即"请求对齐 = 请求容器宽度"(与块级元素 `text-align` 一致)。
     */
    private fun occupiedWidth(constraints: Constraints, contentWidth: Float): Float =
        if (textAlign.isOffsetAlign && constraints.hasBoundedWidth) {
            constraints.maxWidth.toFloat()
        } else {
            contentWidth
        }

    private fun useMinLinesConstrainer(
        constraints: Constraints,
        layoutDirection: LayoutDirection,
        style: Style = this.style,
    ): Constraints {
        val localMin =
            MinLinesConstrainer.from(
                    mMinLinesConstrainer,
                    layoutDirection,
                    style,
                    density!!,
                    fontFamilyResolver,
                )
                .also { mMinLinesConstrainer = it }
        return localMin.coerceMinLines(inConstraints = constraints, minLines = minLines)
    }

    /** The natural height of text at [width] in [layoutDirection] */
    fun intrinsicHeight(width: Int, layoutDirection: LayoutDirection): Int {
        val localWidth = cachedIntrinsicHeightInputWidth
        val localHeght = cachedIntrinsicHeight
        if (width == localWidth && localWidth != -1) return localHeght
        val constraints = Constraints(0, width, 0, Constraints.Infinity)
        val finalConstraints =
            if (minLines > 1) {
                useMinLinesConstrainer(constraints, layoutDirection)
            } else {
                constraints
            }
        val result =
            layoutText(finalConstraints, layoutDirection)
                .height
                .ceilToIntPx()
                .coerceAtLeast(finalConstraints.minHeight)

        cachedIntrinsicHeightInputWidth = width
        cachedIntrinsicHeight = result
        return result
    }

    /** Call when any parameters change, invalidation is a result of calling this method. */
    fun update(
        text: String,
        style: Style,
        fontFamilyResolver: FontFamily.Resolver,
        overflow: TextOverflow,
        softWrap: Boolean,
        maxLines: Int,
        minLines: Int,
        segments: List<StyleSegment> = emptyList(),
        /** 平台适配点:文本缩放;1f = 原样。 */
        scale: Float = 1f,
        /** 平台适配点:段落水平对齐(默认保持当前值 —— 省略即"不改",防止静默清空) */
        textAlign: TextAlign = this.textAlign,
    ) {
        this.text = text
        this.style = style
        this.fontFamilyResolver = fontFamilyResolver
        this.overflow = overflow
        this.softWrap = softWrap
        this.maxLines = maxLines
        this.minLines = minLines
        if (this.segments != segments) {
            this.segments = segments
        }
        if (this.scale != scale) {
            this.scale = scale
        }
        if (this.textAlign != textAlign) {
            this.textAlign = textAlign
        }
        recordHistory(LayoutCacheOperation.MarkDirtyNode)
        markDirty()
    }

    /**
     * Minimum information required to compute [MultiParagraphIntrinsics].
     *
     * After calling paragraphIntrinsics is cached.
     */
    private fun setLayoutDirection(layoutDirection: LayoutDirection): ParagraphIntrinsics {
        val localIntrinsics = paragraphIntrinsics
        val intrinsics =
            if (
                localIntrinsics == null ||
                    layoutDirection != intrinsicsLayoutDirection ||
                    localIntrinsics.hasStaleResolvedFonts
            ) {
                intrinsicsLayoutDirection = layoutDirection
                ParagraphIntrinsics(
                    text = text,
                    // 平台适配点:resolveDefaults 随 TextStyle 移除,Style 无缺省解析
                    style = style,
                    annotations = listOf(),
                    density = density!!,
                    fontFamilyResolver = fontFamilyResolver,
                    placeholders = listOf(),
                    segments = segments,
                    scale = scale,
                    // 平台适配点:段落水平对齐 —— 漏传会让简单路径(String/Component 无回调时)
                    // 永远拿不到对齐,且是静默失效(默认 Unspecified)
                    textAlign = textAlign,
                )
            } else {
                localIntrinsics
            }
        paragraphIntrinsics = intrinsics
        return intrinsics
    }

    /**
     * Computes the visual position of the glyphs for painting the text.
     *
     * The text will layout with a width that's as close to its max intrinsic width as possible
     * while still being greater than or equal to `minWidth` and less than or equal to `maxWidth`.
     */
    internal fun layoutText(constraints: Constraints, layoutDirection: LayoutDirection): Paragraph {
        val localParagraphIntrinsics = setLayoutDirection(layoutDirection)

        return Paragraph(
            paragraphIntrinsics = localParagraphIntrinsics,
            constraints =
                finalConstraints(
                    constraints,
                    softWrap,
                    overflow,
                    localParagraphIntrinsics.maxIntrinsicWidth,
                ),
            maxLines = finalMaxLines(softWrap, overflow, maxLines),
            overflow = overflow,
        )
    }

    /**
     * Attempt to compute if the new layout will be the same for the given constraints and
     * layoutDirection.
     */
    private fun newLayoutWillBeDifferent(
        constraints: Constraints,
        layoutDirection: LayoutDirection,
    ): Boolean {
        // paragraph and paragraphIntrinsics are from previous run
        val localParagraph = paragraph ?: return true
        val localParagraphIntrinsics = paragraphIntrinsics ?: return true
        // no layout yet

        // async typeface changes
        if (localParagraphIntrinsics.hasStaleResolvedFonts) return true

        // layout direction changed
        if (layoutDirection != intrinsicsLayoutDirection) return true

        // if we were passed identical constraints just skip more work
        if (constraints == prevConstraints) return false

        if (constraints.maxWidth != prevConstraints.maxWidth) return true
        if (constraints.minWidth != prevConstraints.minWidth) return true

        // if we get here width won't change, height may be clipped
        if (constraints.maxHeight < localParagraph.height || localParagraph.didExceedMaxLines) {
            // vertical clip changes
            return true
        }

        // breaks can't change, height can't change
        return false
    }

    private fun markDirty() {
        paragraph = null
        paragraphIntrinsics = null
        intrinsicsLayoutDirection = null
        cachedIntrinsicHeightInputWidth = -1
        cachedIntrinsicHeight = -1
        prevConstraints = Constraints.fixed(0, 0)
        layoutSize = IntSize(0, 0)
        didOverflow = false
    }

    /**
     * Compute a [TextLayoutResult] for the current Layout values.
     *
     * This does an entire Text layout to produce the result, it is slow.
     *
     * Exposed for semantics GetTextLayoutResult
     */
    fun slowCreateTextLayoutResultOrNull(style: Style): TextLayoutResult? {
        // make sure we're in a valid place
        val localLayoutDirection = intrinsicsLayoutDirection ?: return null
        val localDensity = density ?: return null
        val annotatedString = AnnotatedString(text)
        paragraph ?: return null
        paragraphIntrinsics ?: return null
        val finalConstraints = prevConstraints.copyMaxDimensions()

        // and redo layout with MultiParagraph
        return TextLayoutResult(
            TextLayoutInput(
                annotatedString,
                style,
                emptyList(),
                maxLines,
                softWrap,
                overflow,
                localDensity,
                localLayoutDirection,
                fontFamilyResolver,
                finalConstraints,
                scale,
                textAlign,
            ),
            MultiParagraph(
                MultiParagraphIntrinsics(
                    annotatedString = annotatedString,
                    style = style,
                    placeholders = emptyList(),
                    density = localDensity,
                    fontFamilyResolver = fontFamilyResolver,
                    scale = scale,
                    textAlign = textAlign,
                ),
                finalConstraints,
                maxLines,
                overflow,
            ),
            layoutSize,
        )
    }

    /** The width for text if all soft wrap opportunities were taken. */
    fun minIntrinsicWidth(layoutDirection: LayoutDirection): Int {
        return setLayoutDirection(layoutDirection).minIntrinsicWidth.ceilToIntPx()
    }

    /** The width at which increasing the width of the text no lonfger decreases the height. */
    fun maxIntrinsicWidth(layoutDirection: LayoutDirection): Int {
        return setLayoutDirection(layoutDirection).maxIntrinsicWidth.ceilToIntPx()
    }

    override fun toString(): String =
        "ParagraphLayoutCache(paragraph=${if (paragraph != null) "<paragraph>" else "null"}, " +
            "lastDensity=$lastDensity, history=$historyFlag, constraints=$)"
}

@JvmInline
internal value class LayoutCacheOperation private constructor(val flag: Long) {
    companion object {
        val MarkDirtyStyle = LayoutCacheOperation(0b00)
        val MarkDirtyDensity = LayoutCacheOperation(0b01)
        val MarkDirtyNode = LayoutCacheOperation(0b10)
        val LayoutWithConstraints = LayoutCacheOperation(0b11)
    }
}
