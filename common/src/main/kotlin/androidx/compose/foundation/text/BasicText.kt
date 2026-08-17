/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.foundation.text

import androidx.compose.foundation.text.modifiers.SelectableTextAnnotatedStringElement
import androidx.compose.foundation.text.modifiers.SelectionController
import androidx.compose.foundation.text.modifiers.TextAnnotatedStringElement
import androidx.compose.foundation.text.modifiers.TextAnnotatedStringNode
import androidx.compose.foundation.text.modifiers.TextStringSimpleElement
import androidx.compose.foundation.text.modifiers.hasLinks
import androidx.compose.foundation.text.selection.LocalSelectionRegistrar
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionRegistrar
import androidx.compose.foundation.text.selection.hasSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.StyleSegment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Constraints.Companion.fitPrioritizingWidth
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMapIndexedNotNull
import androidx.compose.ui.util.fastRoundToInt
import kotlin.math.floor
import moe.forpleuvoir.compose_minecraft.platform.ui.text.flatten
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

/**
 * Basic element that displays text and provides semantics / accessibility information. Typically
 * you will instead want to use [androidx.compose.material.Text], which is a higher level Text
 * element that contains semantics and consumes style information from a theme.
 *
 * @param text The text to be displayed.
 * @param modifier [Modifier] to apply to this layout node.
 * @param style Style configuration for the text such as color, font, line height etc.
 * @param onTextLayout Callback that is executed when a new text layout is calculated. A
 *   [TextLayoutResult] object that callback provides contains paragraph information, size of the
 *   text, baselines and other details. The callback can be used to add additional decoration or
 *   functionality to the text. For example, to draw selection around the text.
 * @param overflow How visual overflow should be handled.
 * @param softWrap Whether the text should break at soft line breaks. If false, the glyphs in the
 *   text will be positioned as if there was unlimited horizontal space. If [softWrap] is false,
 *   [overflow] and TextAlign may have unexpected effects.
 * @param maxLines An optional maximum number of lines for the text to span, wrapping if necessary.
 *   If the text exceeds the given number of lines, it will be truncated according to [overflow] and
 *   [softWrap]. It is required that 1 <= [minLines] <= [maxLines].
 * @param minLines The minimum height in terms of minimum number of visible lines. It is required
 *   that 1 <= [minLines] <= [maxLines].
 * @param color Overrides the text color provided in [style]
 * @param autoSize Enable auto sizing for this text composable. Finds the biggest font size that
 *   fits in the available space and lays the text out with this size. This performs multiple layout
 *   passes and can be slower than using a fixed font size. This takes precedence over sizes defined
 *   through [style]. See [TextAutoSize] and the sample code.
 * @param fontSize 平台适配点(T.19):字体大小,经渲染矩阵缩放实现(布局尺寸与字形
 *   矩阵同步缩放);仅支持 sp 单位。基准行高 [MC_TEXT_SCALE_BASE_PX] = 9px(T.26),
 *   **18sp = 2x 平台基准字号**(整数放大),9sp = 1x 原生像素;
 *   默认 18sp 与 [androidx.compose.foundation.text.input.BasicTextField] 默认一致。
 * @sample androidx.compose.foundation.samples.TextAutoSizeBasicTextSample
 */
@Composable
fun BasicText(
    text: String,
    modifier: Modifier = Modifier,
    style: Style = Style.EMPTY,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    color: ColorProducer? = null,
    autoSize: TextAutoSize? = null,
    fontSize: TextUnit = 18.sp,
) {
    validateMinMaxLines(minLines = minLines, maxLines = maxLines)
    val selectionRegistrar = LocalSelectionRegistrar.current
    val selectionController =
        if (selectionRegistrar != null) {
            val backgroundSelectionColor = LocalTextSelectionColors.current.backgroundColor
            val selectableId =
                rememberSaveable(selectionRegistrar, saver = selectionIdSaver(selectionRegistrar)) {
                    selectionRegistrar.nextSelectableId()
                }
            remember(selectableId, selectionRegistrar, backgroundSelectionColor) {
                SelectionController(selectableId, selectionRegistrar, backgroundSelectionColor)
            }
        } else {
            null
        }

    val fontFamilyResolver = LocalFontFamilyResolver.current

    // 平台适配点(T.19/T.26):fontSize(sp) → 渲染缩放(18sp = 2x 基准);布局/绘制端消费 scale
    val scale = fontSize.toTextScale(LocalDensity.current)

    BackgroundTextMeasurement(text = text, style = style, fontFamilyResolver = fontFamilyResolver)

    val finalModifier =
        if (selectionController != null || onTextLayout != null || autoSize != null) {
            modifier.textModifier(
                AnnotatedString(text = text),
                style = style,
                onTextLayout = onTextLayout,
                overflow = overflow,
                softWrap = softWrap,
                maxLines = maxLines,
                minLines = minLines,
                fontFamilyResolver = LocalFontFamilyResolver.current,
                placeholders = null,
                onPlaceholderLayout = null,
                selectionController = selectionController,
                color = color,
                onShowTranslation = null,
                autoSize = autoSize,
            )
        } else {
            modifier then
                TextStringSimpleElement(
                    text = text,
                    style = style,
                    fontFamilyResolver = fontFamilyResolver,
                    overflow = overflow,
                    softWrap = softWrap,
                    maxLines = maxLines,
                    minLines = minLines,
                    color = color,
                    scale = scale,
                )
        }
    Layout(finalModifier, EmptyMeasurePolicy)
}

/**
 * MC 化的 BasicText:直接输入 MC [Component](富文本,可含多段样式)。
 *
 * 平台适配点(T.3):把 [Component] 按 [flatten] 展平为带自身样式的段列表,
 * 每段与 [defaultStyle] 按属性合并 —— 段样式缺失的属性用 [defaultStyle] 补缺
 * (MC `Style.applyTo` 语义:自身属性优先、缺失用参数补)。布局用拼接文本,
 * 绘制按段切分样式。
 *
 * @param component 要显示的 MC 富文本组件。
 * @param modifier [Modifier] 应用于此布局节点。
 * @param defaultStyle 默认样式:Component 段缺失的属性(如颜色)从此样式补缺。
 * @param onTextLayout 新文本布局计算完成时的回调。
 * @param overflow 视觉溢出处理方式。
 * @param softWrap 是否软换行。
 * @param maxLines 最大可见行数。
 * @param minLines 最小可见行数。
 * @param color 覆盖文本颜色的颜色生产者(覆盖所有段)。
 * @param fontSize 平台适配点(T.19):字体大小,经渲染矩阵缩放实现(布局尺寸与字形
 *   矩阵同步缩放);仅支持 sp 单位。基准行高 [MC_TEXT_SCALE_BASE_PX] = 9px(T.26),
 *   **18sp = 2x 平台基准字号**(整数放大),9sp = 1x 原生像素;
 *   默认 18sp 与 [androidx.compose.foundation.text.input.BasicTextField] 默认一致。
 */
@Composable
fun BasicText(
    component: Component,
    modifier: Modifier = Modifier,
    defaultStyle: Style = Style.EMPTY,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    color: ColorProducer? = null,
    fontSize: TextUnit = 18.sp,
) {
    validateMinMaxLines(minLines = minLines, maxLines = maxLines)

    val fontFamilyResolver = LocalFontFamilyResolver.current

    // 平台适配点(T.19/T.26):fontSize(sp) → 渲染缩放(18sp = 2x 基准);布局/绘制端消费 scale
    val scale = fontSize.toTextScale(LocalDensity.current)

    // 平台适配点(T.3):展平为带自身样式的段;每段缺失属性用 defaultStyle 补缺(applyTo 语义)
    val segments =
        remember(component, defaultStyle) {
            component.flatten().map { seg ->
                StyleSegment(style = seg.style.applyTo(defaultStyle), text = seg.getString())
            }
        }
    val text = remember(segments) { segments.joinToString("") { it.text } }

    BackgroundTextMeasurement(text = text, style = defaultStyle, fontFamilyResolver = fontFamilyResolver)

    val finalModifier =
        modifier then
            TextStringSimpleElement(
                text = text,
                style = defaultStyle,
                fontFamilyResolver = fontFamilyResolver,
                overflow = overflow,
                softWrap = softWrap,
                maxLines = maxLines,
                minLines = minLines,
                color = color,
                segments = segments,
                scale = scale,
            )
    Layout(finalModifier, EmptyMeasurePolicy)
}

/**
 * Basic element that displays text and provides semantics / accessibility information. Typically
 * you will instead want to use [androidx.compose.material.Text], which is a higher level Text
 * element that contains semantics and consumes style information from a theme.
 *
 * @param text The text to be displayed.
 * @param modifier [Modifier] to apply to this layout node.
 * @param style Style configuration for the text such as color, font, line height etc.
 * @param onTextLayout Callback that is executed when a new text layout is calculated. A
 *   [TextLayoutResult] object that callback provides contains paragraph information, size of the
 *   text, baselines and other details. The callback can be used to add additional decoration or
 *   functionality to the text. For example, to draw selection around the text.
 * @param overflow How visual overflow should be handled.
 * @param softWrap Whether the text should break at soft line breaks. If false, the glyphs in the
 *   text will be positioned as if there was unlimited horizontal space. If [softWrap] is false,
 *   [overflow] and TextAlign may have unexpected effects.
 * @param maxLines An optional maximum number of lines for the text to span, wrapping if necessary.
 *   If the text exceeds the given number of lines, it will be truncated according to [overflow] and
 *   [softWrap]. It is required that 1 <= [minLines] <= [maxLines].
 * @param minLines The minimum height in terms of minimum number of visible lines. It is required
 *   that 1 <= [minLines] <= [maxLines].
 * @param inlineContent A map store composables that replaces certain ranges of the text. It's used
 *   to insert composables into text layout. Check [InlineTextContent] for more information.
 * @param color Overrides the text color provided in [style]
 * @param autoSize Enable auto sizing for this text composable. Finds the biggest font size that
 *   fits in the available space and lays the text out with this size. This performs multiple layout
 *   passes and can be slower than using a fixed font size. This takes precedence over sizes defined
 *   through [style]. See [TextAutoSize] and the sample code.
 * @sample androidx.compose.foundation.samples.TextAutoSizeBasicTextSample
 */
@Composable
internal fun BasicText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: Style = Style.EMPTY,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    color: ColorProducer? = null,
    autoSize: TextAutoSize? = null,
) {
    validateMinMaxLines(minLines = minLines, maxLines = maxLines)
    val selectionRegistrar = LocalSelectionRegistrar.current
    val selectionController =
        if (selectionRegistrar != null) {
            val backgroundSelectionColor = LocalTextSelectionColors.current.backgroundColor
            val selectableId =
                rememberSaveable(selectionRegistrar, saver = selectionIdSaver(selectionRegistrar)) {
                    selectionRegistrar.nextSelectableId()
                }
            remember(selectableId, selectionRegistrar, backgroundSelectionColor) {
                SelectionController(selectableId, selectionRegistrar, backgroundSelectionColor)
            }
        } else {
            null
        }
    val hasInlineContent = text.hasInlineContent()
    val hasLinks = text.hasLinks()

    val fontFamilyResolver = LocalFontFamilyResolver.current

    if (!hasInlineContent && !hasLinks) {
        BackgroundTextMeasurement(
            text = text,
            style = style,
            fontFamilyResolver = fontFamilyResolver,
            placeholders = null,
        )

        // this is the same as text: String, use all the early exits
        Layout(
            modifier =
                modifier.textModifier(
                    text = text,
                    style = style,
                    onTextLayout = onTextLayout,
                    overflow = overflow,
                    softWrap = softWrap,
                    maxLines = maxLines,
                    minLines = minLines,
                    fontFamilyResolver = fontFamilyResolver,
                    placeholders = null,
                    onPlaceholderLayout = null,
                    selectionController = selectionController,
                    color = color,
                    onShowTranslation = null,
                    autoSize = autoSize,
                ),
            EmptyMeasurePolicy,
        )
    } else {
        // takes into account text substitution (for translation) that is happening inside the
        // TextAnnotatedStringNode
        var displayedText by remember(text) { mutableStateOf(text) }

        LayoutWithLinksAndInlineContent(
            modifier = modifier,
            text = displayedText,
            onTextLayout = onTextLayout,
            hasInlineContent = hasInlineContent,
            inlineContent = inlineContent,
            style = style,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            fontFamilyResolver = fontFamilyResolver,
            selectionController = selectionController,
            color = color,
            onShowTranslation = { substitutionValue ->
                displayedText =
                    if (substitutionValue.isShowingSubstitution) {
                        substitutionValue.substitution
                    } else {
                        substitutionValue.original
                    }
            },
            autoSize = autoSize,
        )
    }
}

/**
 * Basic element that displays text and provides semantics / accessibility information. Typically
 * you will instead want to use [androidx.compose.material.Text], which is a higher level Text
 * element that contains semantics and consumes style information from a theme.
 *
 * @param text The text to be displayed.
 * @param modifier [Modifier] to apply to this layout node.
 * @param style Style configuration for the text such as color, font, line height etc.
 * @param onTextLayout Callback that is executed when a new text layout is calculated. A
 *   [TextLayoutResult] object that callback provides contains paragraph information, size of the
 *   text, baselines and other details. The callback can be used to add additional decoration or
 *   functionality to the text. For example, to draw selection around the text.
 * @param overflow How visual overflow should be handled.
 * @param softWrap Whether the text should break at soft line breaks. If false, the glyphs in the
 *   text will be positioned as if there was unlimited horizontal space. If [softWrap] is false,
 *   [overflow] and TextAlign may have unexpected effects.
 * @param maxLines An optional maximum number of lines for the text to span, wrapping if necessary.
 *   If the text exceeds the given number of lines, it will be truncated according to [overflow] and
 *   [softWrap]. It is required that 1 <= [minLines] <= [maxLines].
 * @param minLines The minimum height in terms of minimum number of visible lines. It is required
 *   that 1 <= [minLines] <= [maxLines].
 * @param color Overrides the text color provided in [style]
 */
@Deprecated("Maintained for binary compatibility", level = DeprecationLevel.HIDDEN)
@Composable
internal fun BasicText(
    text: String,
    modifier: Modifier = Modifier,
    style: Style = Style.EMPTY,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    color: ColorProducer? = null,
) {
    BasicText(text, modifier, style, onTextLayout, overflow, softWrap, maxLines, minLines, color)
}

/**
 * Basic element that displays text and provides semantics / accessibility information. Typically
 * you will instead want to use [androidx.compose.material.Text], which is a higher level Text
 * element that contains semantics and consumes style information from a theme.
 *
 * @param text The text to be displayed.
 * @param modifier [Modifier] to apply to this layout node.
 * @param style Style configuration for the text such as color, font, line height etc.
 * @param onTextLayout Callback that is executed when a new text layout is calculated. A
 *   [TextLayoutResult] object that callback provides contains paragraph information, size of the
 *   text, baselines and other details. The callback can be used to add additional decoration or
 *   functionality to the text. For example, to draw selection around the text.
 * @param overflow How visual overflow should be handled.
 * @param softWrap Whether the text should break at soft line breaks. If false, the glyphs in the
 *   text will be positioned as if there was unlimited horizontal space. If [softWrap] is false,
 *   [overflow] and TextAlign may have unexpected effects.
 * @param maxLines An optional maximum number of lines for the text to span, wrapping if necessary.
 *   If the text exceeds the given number of lines, it will be truncated according to [overflow] and
 *   [softWrap]. It is required that 1 <= [minLines] <= [maxLines].
 * @param minLines The minimum height in terms of minimum number of visible lines. It is required
 *   that 1 <= [minLines] <= [maxLines].
 * @param inlineContent A map store composables that replaces certain ranges of the text. It's used
 *   to insert composables into text layout. Check [InlineTextContent] for more information.
 * @param color Overrides the text color provided in [style]
 */
@Deprecated("Maintained for binary compatibility", level = DeprecationLevel.HIDDEN)
@Composable
internal fun BasicText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: Style = Style.EMPTY,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    color: ColorProducer? = null,
) {
    BasicText(
        text,
        modifier,
        style,
        onTextLayout,
        overflow,
        softWrap,
        maxLines,
        minLines,
        inlineContent,
        color,
    )
}

@Deprecated("Maintained for binary compatibility", level = DeprecationLevel.HIDDEN)
@Composable
internal fun BasicText(
    text: String,
    modifier: Modifier = Modifier,
    style: Style = Style.EMPTY,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        minLines = 1,
        maxLines = maxLines,
    )
}

@Deprecated("Maintained for binary compatibility", level = DeprecationLevel.HIDDEN)
@Composable
internal fun BasicText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: Style = Style.EMPTY,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = 1,
        inlineContent = inlineContent,
    )
}

@Deprecated("Maintained for binary compat", level = DeprecationLevel.HIDDEN)
@Composable
internal fun BasicText(
    text: String,
    modifier: Modifier = Modifier,
    style: Style = Style.EMPTY,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) = BasicText(text, modifier, style, onTextLayout, overflow, softWrap, maxLines, minLines)

@Deprecated("Maintained for binary compat", level = DeprecationLevel.HIDDEN)
@Composable
internal fun BasicText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: Style = Style.EMPTY,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
) =
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        inlineContent = inlineContent,
    )

/** A custom saver that won't save if no selection is active. */
private fun selectionIdSaver(selectionRegistrar: SelectionRegistrar?) =
    Saver<Long, Long>(
        save = { if (selectionRegistrar.hasSelection(it)) it else null },
        restore = { it },
    )

private object EmptyMeasurePolicy : MeasurePolicy {
    private val placementBlock: Placeable.PlacementScope.() -> Unit = {}

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        return layout(constraints.maxWidth, constraints.maxHeight, placementBlock = placementBlock)
    }
}

/** Measure policy for inline content and links */
private class TextMeasurePolicy(
    private val shouldMeasureLinks: () -> Boolean,
    private val placements: () -> List<Rect?>?,
) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        // inline content
        val inlineContentMeasurables =
            measurables.fastFilter { it.parentData !is TextRangeLayoutModifier }
        val inlineContentToPlace =
            placements()?.fastMapIndexedNotNull { index, rect ->
                // PlaceholderRect will be null if it's ellipsized. In that case, the corresponding
                // inline children won't be measured or placed.
                rect?.let {
                    Pair(
                        inlineContentMeasurables[index].measure(
                            Constraints(
                                maxWidth = floor(it.width).toInt(),
                                maxHeight = floor(it.height).toInt(),
                            )
                        ),
                        IntOffset(it.left.fastRoundToInt(), it.top.fastRoundToInt()),
                    )
                }
            }

        // links
        val linksMeasurables = measurables.fastFilter { it.parentData is TextRangeLayoutModifier }
        val linksToPlace =
            measureWithTextRangeMeasureConstraints(
                measurables = linksMeasurables,
                shouldMeasureLinks = shouldMeasureLinks,
            )

        return layout(constraints.maxWidth, constraints.maxHeight) {
            // inline content
            inlineContentToPlace?.fastForEach { (placeable, position) -> placeable.place(position) }
            // links
            linksToPlace?.fastForEach { (placeable, measureResult) ->
                placeable.place(measureResult?.invoke() ?: IntOffset.Zero)
            }
        }
    }
}

/** Measure policy for links only */
private class LinksTextMeasurePolicy(private val shouldMeasureLinks: () -> Boolean) :
    MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        return layout(constraints.maxWidth, constraints.maxHeight) {
            val linksToPlace =
                measureWithTextRangeMeasureConstraints(
                    measurables = measurables,
                    shouldMeasureLinks = shouldMeasureLinks,
                )
            linksToPlace?.fastForEach { (placeable, measureResult) ->
                placeable.place(measureResult?.invoke() ?: IntOffset.Zero)
            }
        }
    }
}

private fun measureWithTextRangeMeasureConstraints(
    measurables: List<Measurable>,
    shouldMeasureLinks: () -> Boolean,
): List<Pair<Placeable, (() -> IntOffset)?>>? {
    return if (shouldMeasureLinks()) {
        val textRangeLayoutMeasureScope = TextRangeLayoutMeasureScope()
        measurables.fastMapIndexedNotNull { _, measurable ->
            val rangeMeasurePolicy =
                (measurable.parentData as TextRangeLayoutModifier).measurePolicy
            val rangeMeasureResult =
                with(rangeMeasurePolicy) { textRangeLayoutMeasureScope.measure() }
            val placeable =
                measurable.measure(
                    fitPrioritizingWidth(
                        minWidth = rangeMeasureResult.width,
                        maxWidth = rangeMeasureResult.width,
                        minHeight = rangeMeasureResult.height,
                        maxHeight = rangeMeasureResult.height,
                    )
                )
            Pair(placeable, rangeMeasureResult.place)
        }
    } else {
        null
    }
}

private fun Modifier.textModifier(
    text: AnnotatedString,
    style: Style,
    onTextLayout: ((TextLayoutResult) -> Unit)?,
    overflow: TextOverflow,
    softWrap: Boolean,
    maxLines: Int,
    minLines: Int,
    fontFamilyResolver: FontFamily.Resolver,
    placeholders: List<AnnotatedString.Range<Placeholder>>?,
    onPlaceholderLayout: ((List<Rect?>) -> Unit)?,
    selectionController: SelectionController?,
    color: ColorProducer?,
    onShowTranslation: ((TextAnnotatedStringNode.TextSubstitutionValue) -> Unit)?,
    autoSize: TextAutoSize?,
): Modifier {
    if (selectionController == null) {
        val staticTextModifier =
            TextAnnotatedStringElement(
                text,
                style,
                fontFamilyResolver,
                onTextLayout,
                overflow,
                softWrap,
                maxLines,
                minLines,
                placeholders,
                onPlaceholderLayout,
                null,
                color,
                autoSize,
                onShowTranslation,
            )
        return this then Modifier /* selection position */ then staticTextModifier
    } else {
        val selectableTextModifier =
            SelectableTextAnnotatedStringElement(
                text,
                style,
                fontFamilyResolver,
                onTextLayout,
                overflow,
                softWrap,
                maxLines,
                minLines,
                placeholders,
                onPlaceholderLayout,
                selectionController,
                color,
                autoSize,
            )
        return this then selectionController.modifier then selectableTextModifier
    }
}

@Composable
private fun LayoutWithLinksAndInlineContent(
    modifier: Modifier,
    text: AnnotatedString,
    onTextLayout: ((TextLayoutResult) -> Unit)?,
    hasInlineContent: Boolean,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    style: Style,
    overflow: TextOverflow,
    softWrap: Boolean,
    maxLines: Int,
    minLines: Int,
    fontFamilyResolver: FontFamily.Resolver,
    selectionController: SelectionController?,
    color: ColorProducer?,
    onShowTranslation: ((TextAnnotatedStringNode.TextSubstitutionValue) -> Unit)?,
    autoSize: TextAutoSize?,
) {

    val textScope =
        if (text.hasLinks()) {
            remember(text) { TextLinkScope(text) }
        } else null

    // only adds additional span styles to the existing link annotations, doesn't semantically
    // change the text
    val styledText: () -> AnnotatedString =
        if (text.hasLinks()) {
            remember(text, textScope) { { textScope?.applyAnnotators() ?: text } }
        } else {
            { text }
        }

    // do the inline content allocs
    val (placeholders, inlineComposables) =
        if (hasInlineContent) {
            text.resolveInlineContent(inlineContent = inlineContent)
        } else Pair(null, null)

    val measuredPlaceholderPositions =
        if (hasInlineContent) {
            remember<MutableState<List<Rect?>?>> { mutableStateOf(null) }
        } else null

    val onPlaceholderLayout: ((List<Rect?>) -> Unit)? =
        if (hasInlineContent) {
            { measuredPlaceholderPositions?.value = it }
        } else null

    BackgroundTextMeasurement(
        text = text,
        style = style,
        fontFamilyResolver = fontFamilyResolver,
        placeholders = placeholders,
    )

    Layout(
        content = {
            textScope?.LinksComposables()
            inlineComposables?.let { InlineChildren(text = text, inlineContents = it) }
        },
        modifier =
            modifier.textModifier(
                text = styledText(),
                style = style,
                onTextLayout = {
                    textScope?.textLayoutResult = it
                    onTextLayout?.invoke(it)
                },
                overflow = overflow,
                softWrap = softWrap,
                maxLines = maxLines,
                minLines = minLines,
                fontFamilyResolver = fontFamilyResolver,
                placeholders = placeholders,
                onPlaceholderLayout = onPlaceholderLayout,
                selectionController = selectionController,
                color = color,
                onShowTranslation = onShowTranslation,
                autoSize = autoSize,
            ),
        measurePolicy =
            if (!hasInlineContent) {
                LinksTextMeasurePolicy(
                    shouldMeasureLinks = { textScope?.let { it.shouldMeasureLinks() } ?: false }
                )
            } else {
                TextMeasurePolicy(
                    shouldMeasureLinks = { textScope?.let { it.shouldMeasureLinks() } ?: false },
                    placements = { measuredPlaceholderPositions?.value },
                )
            },
    )
}

/**
 * This function pre-measures the text on Android platform to warm the platform text layout cache in
 * a background thread before the actual text layout begins.
 */
@Composable
@NonRestartableComposable
internal fun BackgroundTextMeasurement(
    text: String,
    style: Style,
    fontFamilyResolver: FontFamily.Resolver,
) {
    // Minecraft 平台第一版不预热文字测量
}

/**
 * This function pre-measures the text on Android platform to warm the platform text layout cache in
 * a background thread before the actual text layout begins.
 */
@Composable
@NonRestartableComposable
internal fun BackgroundTextMeasurement(
    text: AnnotatedString,
    style: Style,
    fontFamilyResolver: FontFamily.Resolver,
    placeholders: List<AnnotatedString.Range<Placeholder>>?,
) {
    // Minecraft 平台第一版不预热文字测量
}

/**
 * 平台适配点(T.19/T.25):MC 字形 1x 行高(px)。
 *
 * MC 无原生字号系统,文字大小经渲染矩阵缩放实现(T.10 的 scale 链路)。
 * MC 字形为 8x8 位图,1x 行高固定 9px —— 位图非整数缩放会糊,常用字号应落在
 * 整数缩放上。**MC 平台的基准字号 = 18sp(2x,行高 18px)**:18sp 是 MC 界面
 * 自然的放大观感字号(16sp 是强行对齐 Compose 惯例,非本平台自然字号):
 * 9sp → 1x(行高 9px 原生)、18sp → 2x(行高 18px)、36sp → 4x(行高 36px)。
 */
internal const val MC_TEXT_SCALE_BASE_PX = 9f

/**
 * 平台适配点(T.19):TextUnit(sp) → 文本渲染缩放。
 *
 * MC 无原生字号系统,文字大小经渲染矩阵缩放实现(T.10 的 scale 链路)。
 * 基准行高 [MC_TEXT_SCALE_BASE_PX] = MC 1x 行高 9px:**18sp = 2x = 18px 行高**
 * (MC 平台基准字号;16sp ≈ 1.78x 非整数缩放、非自然字号,尽量避免);
 * 仅支持 sp 单位 —— em 需要基准字号链(TextStyle 已随平台移除),无法解析。
 */
// 平台适配点(T.26):internal —— BasicTextField 同包复用(fontSize → 渲染缩放)
internal fun TextUnit.toTextScale(density: Density): Float {
    require(type == TextUnitType.Sp) {
        "平台适配点(T.19):fontSize 仅支持 sp 单位(MC 无原生字号系统,em 无法解析)"
    }
    // sp → px(密度 1 下 18sp = 18px)→ 缩放 = px / MC 1x 行高(9px),18sp → 2x
    return value * density.density * density.fontScale / MC_TEXT_SCALE_BASE_PX
}
