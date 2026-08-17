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
import androidx.compose.foundation.text.MC_TEXT_SCALE_BASE_PX
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.ceilToIntPx
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.StyleSegment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import kotlin.math.min
import net.minecraft.network.chat.Style

/**
 * Performs text layout using [MultiParagraph].
 *
 * Results are cached whenever possible, for example when only constraints change in a way that
 * cannot reflow text.
 *
 * All measurements are cached.
 */
internal class MultiParagraphLayoutCache(
    private var text: AnnotatedString,
    style: Style,
    private var fontFamilyResolver: FontFamily.Resolver,
    private var overflow: TextOverflow = TextOverflow.Clip,
    private var softWrap: Boolean = true,
    private var maxLines: Int = Int.MAX_VALUE,
    private var minLines: Int = DefaultMinLines,
    private var placeholders: List<AnnotatedString.Range<Placeholder>>? = null,
    private var autoSize: TextAutoSize? = null,
    // 平台适配点(T.29 富文本):spanStyles 切分后的段列表(全覆盖,渲染端逐段绘制)
    private var segments: List<StyleSegment> = emptyList(),
    // 平台适配点(T.29):字号渲染缩放(18sp → 2x)。AnnotatedString 版文本的
    // fontSize 经 BasicText 组合端换算后一路透传到这里,布局与绘制共用。
    private var scale: Float = 1f,
) {
    /** Convert min max lines into actual constraints */
    private var mMinLinesConstrainer: MinLinesConstrainer? = null

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

    /**
     * The style used for layout. Marks style-affected cache properties dirty if the new style's
     * layout-affecting attributes are different.
     * 平台适配点:Style 无布局/绘制属性分离,整样式参与比较。
     */
    private var style: Style = style
        set(value) {
            val newStyleHasSameLayoutAffectingAttrs = value == field
            field = value
            if (!newStyleHasSameLayoutAffectingAttrs) {
                markStyleAffectedDirty()
            }
        }

    /** [MultiParagraphIntrinsics] will be initialized lazily */
    private var paragraphIntrinsics: MultiParagraphIntrinsics? = null

    /** [LayoutDirection] used to compute [MultiParagraphIntrinsics] */
    private var intrinsicsLayoutDirection: LayoutDirection? = null

    /** 平台适配点(T.20):intrinsics 缓存键中的 scale 分量(TextAutoSize 多档字号布局) */
    private var intrinsicsScale: Float = 1f

    /** 最近一次 [layoutWithConstraints] 的 [LayoutDirection],TextAutoSize 搜索布局复用 */
    private var lastLayoutDirection: LayoutDirection = LayoutDirection.Ltr

    /** Cached value of final [TextLayoutResult] */
    private var layoutCache: TextLayoutResult? = null

    /** Input width for the last call to [intrinsicHeight] */
    private var cachedIntrinsicHeightInputWidth: Int = -1

    /** Output height for last call to [intrinsicHeight] at [cachedIntrinsicHeightInputWidth] */
    private var cachedIntrinsicHeight: Int = -1

    /** Backing property for [fontSizeSearchScope] */
    private var _textAutoSizeLayoutScope: TextAutoSizeLayoutScopeImpl? = null

    /** Used to get the font size if AutoSize is enabled and perform layout with many font sizes */
    private val fontSizeSearchScope: TextAutoSizeLayoutScopeImpl
        get() {
            if (_textAutoSizeLayoutScope == null)
                _textAutoSizeLayoutScope = TextAutoSizeLayoutScopeImpl()
            return _textAutoSizeLayoutScope!!
        }

    /** The last computed TextLayoutResult, or throws if not initialized. */
    val textLayoutResult: TextLayoutResult
        get() =
            layoutCache
                ?: throw IllegalStateException(
                    "Internal Error: MultiParagraphLayoutCache could not provide TextLayoutResult during the draw phase. Please report this bug on the official Issue Tracker with the following diagnostic information: ${toString()}"
                )

    /** The last computed TextLayoutResult, or null if not initialized. */
    val layoutOrNull: TextLayoutResult?
        get() = layoutCache

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
        lastLayoutDirection = layoutDirection
        val finalConstraints =
            if (minLines > 1) {
                useMinLinesConstrainer(constraints, layoutDirection)
            } else {
                constraints
            }

        if (!layoutCache.newLayoutWillBeDifferent(finalConstraints, layoutDirection)) {
            if (finalConstraints == layoutCache!!.layoutInput.constraints) return false
            // we need to regen the input, constraints aren't the same
            layoutCache =
                textLayoutResult(
                    layoutDirection = layoutDirection,
                    finalConstraints = finalConstraints,
                    multiParagraph = layoutCache!!.multiParagraph,
                )
            return true
        }
        if (autoSize != null) {
            // 平台适配点(T.20/T.25):TextAutoSize 二分搜索最大适配字号(sp → 渲染 scale,
            // 基准 = MC 1x 行高 9px,18sp → 2x = 18px 行高),用搜索得到的字号重新布局
            val localAutoSize = autoSize!!
            val scale =
                with(localAutoSize) {
                    with(fontSizeSearchScope) {
                        getFontSize(finalConstraints, text).toPx() / MC_TEXT_SCALE_BASE_PX
                    }
                }
            val multiParagraph = layoutText(finalConstraints, layoutDirection, scale)
            layoutCache = textLayoutResult(layoutDirection, finalConstraints, multiParagraph)
            return true
        }

        val multiParagraph = layoutText(finalConstraints, layoutDirection, scale)

        layoutCache = textLayoutResult(layoutDirection, finalConstraints, multiParagraph)
        return true
    }

    private fun useMinLinesConstrainer(
        constraints: Constraints,
        layoutDirection: LayoutDirection,
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

    private fun textLayoutResult(
        layoutDirection: LayoutDirection,
        finalConstraints: Constraints,
        multiParagraph: MultiParagraph,
    ): TextLayoutResult {
        val layoutWidth = min(multiParagraph.intrinsics.maxIntrinsicWidth, multiParagraph.width)
        return TextLayoutResult(
            TextLayoutInput(
                text,
                style,
                placeholders.orEmpty(),
                maxLines,
                softWrap,
                overflow,
                density!!,
                layoutDirection,
                fontFamilyResolver,
                finalConstraints,
                intrinsicsScale,
            ),
            multiParagraph,
            finalConstraints.constrain(
                IntSize(layoutWidth.ceilToIntPx(), multiParagraph.height.ceilToIntPx())
            ),
        )
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
            layoutText(finalConstraints, layoutDirection, 1f)
                .height
                .ceilToIntPx()
                .coerceAtLeast(finalConstraints.minHeight)

        cachedIntrinsicHeightInputWidth = width
        cachedIntrinsicHeight = result
        return result
    }

    /** Call when any parameters change, invalidation is a result of calling this method. */
    fun update(
        text: AnnotatedString,
        style: Style,
        fontFamilyResolver: FontFamily.Resolver,
        overflow: TextOverflow,
        softWrap: Boolean,
        maxLines: Int,
        minLines: Int,
        placeholders: List<AnnotatedString.Range<Placeholder>>?,
        autoSize: TextAutoSize?,
        // 平台适配点(T.29 富文本):spanStyles 切分后的段列表(全覆盖)
        segments: List<StyleSegment> = emptyList(),
        // 平台适配点(T.29):字号渲染缩放(18sp → 2x),见构造注释
        scale: Float = 1f,
    ) {
        this.text = text
        this.style = style
        this.fontFamilyResolver = fontFamilyResolver
        this.overflow = overflow
        this.softWrap = softWrap
        this.maxLines = maxLines
        this.minLines = minLines
        this.placeholders = placeholders
        this.autoSize = autoSize
        this.segments = segments
        this.scale = scale
        recordHistory(LayoutCacheOperation.MarkDirtyNode)
        markDirty()
    }

    /**
     * Minimum information required to compute [MultiParagraphIntrinsics].
     *
     * After calling paragraphIntrinsics is cached.
     */
    private fun setLayoutDirection(
        layoutDirection: LayoutDirection,
        scale: Float,
    ): MultiParagraphIntrinsics {
        val localIntrinsics = paragraphIntrinsics
        val intrinsics =
            if (
                localIntrinsics == null ||
                    layoutDirection != intrinsicsLayoutDirection ||
                    scale != intrinsicsScale ||
                    localIntrinsics.hasStaleResolvedFonts
            ) {
                intrinsicsLayoutDirection = layoutDirection
                intrinsicsScale = scale
                MultiParagraphIntrinsics(
                    annotatedString = text,
                    // 平台适配点:resolveDefaults 随 TextStyle 移除,Style 无缺省解析
                    style = style,
                    density = density!!,
                    fontFamilyResolver = fontFamilyResolver,
                    placeholders = placeholders.orEmpty(),
                    scale = scale,
                    // 平台适配点(T.29 富文本):段列表透传到 intrinsics → Paragraph → 渲染端
                    segments = segments,
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
    private fun layoutText(
        constraints: Constraints,
        layoutDirection: LayoutDirection,
        scale: Float,
    ): MultiParagraph {
        val localParagraphIntrinsics = setLayoutDirection(layoutDirection, scale)

        return MultiParagraph(
            intrinsics = localParagraphIntrinsics,
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
    private fun TextLayoutResult?.newLayoutWillBeDifferent(
        constraints: Constraints,
        layoutDirection: LayoutDirection,
    ): Boolean {
        // no layout yet
        if (this == null) return true

        // async typeface changes
        if (this.multiParagraph.intrinsics.hasStaleResolvedFonts) return true

        // layout direction changed
        if (layoutDirection != layoutInput.layoutDirection) return true

        // if we were passed identical constraints just skip more work
        if (constraints == layoutInput.constraints) return false

        if (constraints.maxWidth != layoutInput.constraints.maxWidth) return true
        if (constraints.minWidth != layoutInput.constraints.minWidth) return true

        // if we get here width won't change, height may be clipped
        if (constraints.maxHeight < multiParagraph.height || multiParagraph.didExceedMaxLines) {
            // vertical clip changes
            return true
        }

        // breaks can't change, height can't change
        return false
    }

    private fun markDirty() {
        paragraphIntrinsics = null
        layoutCache = null
        cachedIntrinsicHeight = -1
        cachedIntrinsicHeightInputWidth = -1
        _textAutoSizeLayoutScope = null
    }

    private fun markStyleAffectedDirty() {
        recordHistory(LayoutCacheOperation.MarkDirtyStyle)
        paragraphIntrinsics = null
        layoutCache = null
        cachedIntrinsicHeight = -1
        cachedIntrinsicHeightInputWidth = -1
    }

    /** The width at which increasing the width of the text no longer decreases the height. */
    fun maxIntrinsicWidth(layoutDirection: LayoutDirection): Int {
        return setLayoutDirection(layoutDirection, scale).maxIntrinsicWidth.ceilToIntPx()
    }

    /** The width for text if all soft wrap opportunities were taken. */
    fun minIntrinsicWidth(layoutDirection: LayoutDirection): Int {
        return setLayoutDirection(layoutDirection, scale).minIntrinsicWidth.ceilToIntPx()
    }

    /** [MultiParagraph] specific implementation of [TextAutoSizeLayoutScope] */
    private inner class TextAutoSizeLayoutScopeImpl : TextAutoSizeLayoutScope {

        // override Density attributes
        override val density
            get() = this@MultiParagraphLayoutCache.density!!.density

        override val fontScale
            get() = this@MultiParagraphLayoutCache.density!!.fontScale

        /** The last [TextLayoutResult] from measuring the text */
        var lastLayoutResult: TextLayoutResult? = null
            private set

        // 平台适配点(T.20):sp → px,与 T.19 的 TextUnit.toTextScale 同公式
        // (18sp = 18px = 渲染 scale 2x,含 fontScale)
        override fun TextUnit.toPx(): Float =
            when (type) {
                TextUnitType.Sp -> value * density * fontScale
                TextUnitType.Unspecified -> 0f
                else ->
                    throw IllegalArgumentException(
                        "TextAutoSize only supports sp font sizes (received $this)"
                    )
            }

        override fun performLayout(
            constraints: Constraints,
            text: AnnotatedString,
            fontSize: TextUnit,
        ): TextLayoutResult {
            // 平台适配点(T.20/T.25):MC 无原生字号系统,字号经渲染 scale 驱动
            // (基准 = MC 1x 行高 9px,18sp → 2x = 18px 行高);用局部 intrinsics
            // 布局(不污染主布局缓存)
            val scale = fontSize.toPx() / MC_TEXT_SCALE_BASE_PX
            val localIntrinsics =
                MultiParagraphIntrinsics(
                    annotatedString = text,
                    style = this@MultiParagraphLayoutCache.style,
                    density = this@MultiParagraphLayoutCache.density!!,
                    fontFamilyResolver = this@MultiParagraphLayoutCache.fontFamilyResolver,
                    placeholders = placeholders.orEmpty(),
                    scale = scale,
                    // 平台适配点(T.29 富文本):autoSize 探测布局同样携带段列表
                    segments = this@MultiParagraphLayoutCache.segments,
                )
            val multiParagraph =
                MultiParagraph(
                    intrinsics = localIntrinsics,
                    constraints =
                        finalConstraints(
                            constraints,
                            softWrap,
                            overflow,
                            localIntrinsics.maxIntrinsicWidth,
                        ),
                    maxLines = finalMaxLines(softWrap, overflow, maxLines),
                    overflow = overflow,
                )
            val result =
                TextLayoutResult(
                    TextLayoutInput(
                        text,
                        style,
                        placeholders.orEmpty(),
                        maxLines,
                        softWrap,
                        overflow,
                        this@MultiParagraphLayoutCache.density!!,
                        lastLayoutDirection,
                        fontFamilyResolver,
                        constraints,
                        scale,
                    ),
                    multiParagraph,
                    constraints.constrain(
                        IntSize(
                            min(
                                multiParagraph.intrinsics.maxIntrinsicWidth,
                                multiParagraph.width,
                            ).ceilToIntPx(),
                            multiParagraph.height.ceilToIntPx(),
                        ),
                    ),
                )
            lastLayoutResult = result
            return result
        }
    }

    override fun toString(): String =
        "MultiParagraphLayoutCache(textLayoutResult=${if (layoutCache != null) "<TextLayoutResult>" else "null"}, " +
            "lastDensity=$lastDensity, history=$historyFlag, constraints=${layoutCache?.layoutInput?.constraints ?: "null"})"
}

/**
 * Multiply [this] text unit by the [other] EM value.
 *
 * @return The multiplied text unit, or [DefaultFontSize] if [this] is [TextUnit.Unspecified]
 * @throws IllegalStateException If both [this] and the [other] are EM
 * @throws IllegalArgumentException If the [other] is not in EM
 */
private operator fun TextUnit.times(other: TextUnit): TextUnit {
    if (other.isEm) {
        if (this.isEm) {
            throw IllegalStateException(
                "Cannot convert Em to Px when style.fontSize is" +
                    " Em ($other). Please declare the style.fontSize" +
                    " with Sp units instead."
            )
        }
        if (this.isUnspecified) {
            // hardcoded here as DefaultFontSize is private in SpanStyle
            // TODO(b/364858402): Make DefaultFontSize public
            return DefaultFontSize * other.value
        }
        return this * other.value
    } else {
        throw IllegalArgumentException("The multiplier must be in em, but was $other.")
    }
}

private val DefaultFontSize = 14.sp
