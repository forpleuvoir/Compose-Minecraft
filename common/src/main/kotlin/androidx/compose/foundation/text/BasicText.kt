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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.StyleSegment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Constraints.Companion.fitPrioritizingWidth
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalTextRenderBackend
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalDefaultFont
import moe.forpleuvoir.compose_minecraft.platform.ui.text.resolveDefaultFont
import moe.forpleuvoir.compose_minecraft.platform.ui.text.resolveDefaultFontSize
import moe.forpleuvoir.compose_minecraft.platform.ui.text.resolveDefaultFontSize
import moe.forpleuvoir.compose_minecraft.platform.render.text.TextRenderConfig
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalDefaultTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.fontSizeToEmPx
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withDefaultFont
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMapIndexedNotNull
import androidx.compose.ui.util.fastRoundToInt
import kotlin.math.floor
import moe.forpleuvoir.compose_minecraft.platform.ui.text.flatten
import moe.forpleuvoir.compose_minecraft.platform.ui.text.fontOriginal
import moe.forpleuvoir.compose_minecraft.platform.ui.text.obfuscatedRaw
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toPlatformData
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toStyleSegments
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
 *   平台适配点(T.28):接受 Compose [TextStyle] —— 语义经
 *   [moe.forpleuvoir.compose_minecraft.platform.ui.text.toPlatformData]
 *   映射到平台:color/alpha/fontSize(sp,18sp = 2x 平台基准字号,9sp = 1x 原生像素)/
 *   fontWeight(≥600 加粗)/fontStyle(斜体)/textDecoration(下划线/删除线)生效;
 *   [androidx.compose.ui.text.PlatformSpanStyle] 承载 MC 原版渲染特性
 *   (obfuscated/shadowColor/clickEvent/hoverEvent/insertion/font);
 *   letterSpacing/background/lineHeight/textAlign 等平台无法表达的字段
 *   文档化忽略(见 [moe.forpleuvoir.compose_minecraft.platform.ui.text.PlatformTextData.ignored])。
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
 * @sample androidx.compose.foundation.samples.TextAutoSizeBasicTextSample
 */
@Composable
fun BasicText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
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

    val fontFamilyResolver = LocalFontFamilyResolver.current

    // 平台适配点(T.28):TextStyle → {MC Style, scale, alpha}。Compose 语义经
    // toPlatformData 映射:color/fontWeight/fontStyle/textDecoration/alpha/fontSize
    // (sp → 渲染缩放)+ PlatformSpanStyle 承载的 MC 渲染特性;平台无法表达的字段
    // (letterSpacing/background/lineHeight/...) 收集进 PlatformTextData.ignored(文档化忽略)。
    // 已知边界(T.19):textModifier 分支(selection/onTextLayout/autoSize)暂不消费 scale/alpha。
    val density = LocalDensity.current
    // 平台适配点(T.30):style 可空 —— 未显式传 style 用 LocalDefaultTextStyle 兜底;
    // 未显式指定字体(platformStyle.font)补 LocalDefaultFont。
    val defaultTextStyle = LocalDefaultTextStyle.current
    val defaultFont = resolveDefaultFont()
    // 默认字号 = 双模式统一解析(P3 像素化:经 resolveDefaultFontSize,
    // 原「TTF 16sp / 原版 18sp」后端分支随全局默认字体切换为 Fusion Pixel 而移除)
    val defaultFontSize = resolveDefaultFontSize()
    val effectiveStyle =
        remember(style, defaultTextStyle, defaultFont, defaultFontSize) {
            val withFont = (style ?: defaultTextStyle).withDefaultFont(defaultFont)
            // 平台适配点(T.32):fontSize 未显式指定时补解析后的默认字号(显式 provide 优先)
            if (withFont.fontSize.isUnspecified) withFont.merge(TextStyle(fontSize = defaultFontSize)) else withFont
        }
    val platformData = remember(effectiveStyle, density) { effectiveStyle.toPlatformData(density) }
    val mcStyle = platformData.mcStyle
    val scale = platformData.scale
    val textAlpha = platformData.alpha
    // 平台适配点(T.TT):渐变画刷(TextStyle.brush 非 SolidColor),绘制走 brush 重载
    val textBrush = platformData.brush
    // 平台适配点(T.TT P2):子树级渲染后端定向(LocalTextRenderBackend)
    val textBackend = LocalTextRenderBackend.current

    BackgroundTextMeasurement(text = text, style = mcStyle, fontFamilyResolver = fontFamilyResolver)

    val finalModifier =
        if (selectionController != null || onTextLayout != null || autoSize != null) {
            modifier.textModifier(
                AnnotatedString(text = text),
                style = mcStyle,
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
                scale = scale,
            )
        } else {
            modifier then
                TextStringSimpleElement(
                    text = text,
                    style = mcStyle,
                    fontFamilyResolver = fontFamilyResolver,
                    overflow = overflow,
                    softWrap = softWrap,
                    maxLines = maxLines,
                    minLines = minLines,
                    color = color,
                    scale = scale,
                    alpha = textAlpha,
                    brush = textBrush,
                    backend = textBackend,
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
 *   矩阵同步缩放);仅支持 sp 单位。emPx = sp × density × fontScale(P2-B3 绝对像素空间),
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
    fontSize: TextUnit = resolveDefaultFontSize(),
) {
    validateMinMaxLines(minLines = minLines, maxLines = maxLines)

    val fontFamilyResolver = LocalFontFamilyResolver.current

    // 平台适配点(T.19/T.26):fontSize(sp) → 渲染缩放(18sp = 2x 基准);布局/绘制端消费 scale
    val scale = fontSize.toTextScale(LocalDensity.current)

    // 平台适配点(T.30):默认字体 —— defaultStyle 未指定 font 时补 LocalDefaultFont
    val defaultFont = resolveDefaultFont()
    val effectiveDefaultStyle =
        remember(defaultStyle, defaultFont) {
            // ⚠️ 必须用 fontOriginal(可空原始值):MC Style.getFont() 未设置时
            // 返回 DEFAULT 非 null —— 用 .font 判空会导致默认字体盖章永不生效
            if (defaultStyle.fontOriginal == null) defaultStyle.withFont(defaultFont) else defaultStyle
        }

    // 平台适配点(T.3):展平为带自身样式的段;每段缺失属性用 defaultStyle 补缺(applyTo 语义)
    val segments =
        remember(component, effectiveDefaultStyle) {
            component.flatten().map { seg ->
                StyleSegment(style = seg.style.applyTo(effectiveDefaultStyle), text = seg.getString())
            }
        }
    val text = remember(segments) { segments.joinToString("") { it.text } }

    BackgroundTextMeasurement(text = text, style = effectiveDefaultStyle, fontFamilyResolver = fontFamilyResolver)

    val finalModifier =
        modifier then
            TextStringSimpleElement(
                text = text,
                style = effectiveDefaultStyle,
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
 * 平台适配点(T.29 富文本):官方 CMP 此重载为 internal(AnnotatedString 是内部实现通道),
 * 本平台提升为 public —— 富文本(spanStyles 逐段混排)的公开入口。段级支持
 * color/bold/italic/decoration/PlatformSpanStyle(MC 特性),段间无样式覆盖文本走
 * [style] 默认样式;字号(scale)逐段不同暂不支持(布局统一 base scale)。
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
fun BasicText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
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

    // 平台适配点(T.28):TextStyle → MC Style(见公开 String 版注释)
    val density = LocalDensity.current
    // 平台适配点(T.30):style 可空 —— 未显式传 style 用 LocalDefaultTextStyle 兜底;
    // 未显式指定字体(platformStyle.font)补 LocalDefaultFont。
    val defaultTextStyle = LocalDefaultTextStyle.current
    val defaultFont = resolveDefaultFont()
    // 默认字号 = 双模式统一解析(P3 像素化:经 resolveDefaultFontSize,
    // 原「TTF 16sp / 原版 18sp」后端分支随全局默认字体切换为 Fusion Pixel 而移除)
    val defaultFontSize = resolveDefaultFontSize()
    val effectiveStyle =
        remember(style, defaultTextStyle, defaultFont, defaultFontSize) {
            val withFont = (style ?: defaultTextStyle).withDefaultFont(defaultFont)
            // 平台适配点(T.32):fontSize 未显式指定时补解析后的默认字号(显式 provide 优先)
            if (withFont.fontSize.isUnspecified) withFont.merge(TextStyle(fontSize = defaultFontSize)) else withFont
        }
    val platformData = remember(effectiveStyle, density) { effectiveStyle.toPlatformData(density) }
    val mcStyle = platformData.mcStyle

    // 平台适配点(T.29 富文本):spanStyles 段 → 全覆盖 StyleSegment(段样式叠加 mcStyle,
    // 段间无样式覆盖的文本用 mcStyle;渲染端 recordSegmentedTextDraw 要求段全覆盖)
    val segments = remember(text, style, density) { text.toStyleSegments(mcStyle) }

    // 平台适配点(T.29):字号渲染缩放(18sp → 2x)。AnnotatedString 版走
    // MultiParagraphLayoutCache,scale 必须透传到布局/绘制(否则字号恒 1x)
    val textScale = platformData.scale

    if (!hasInlineContent && !hasLinks) {
        BackgroundTextMeasurement(
            text = text,
            style = mcStyle,
            fontFamilyResolver = fontFamilyResolver,
            placeholders = null,
        )

        // this is the same as text: String, use all the early exits
        Layout(
            modifier =
                modifier.textModifier(
                    text = text,
                    style = mcStyle,
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
                    segments = segments,
                    scale = textScale,
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
            style = mcStyle,
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
            segments = segments,
            scale = textScale,
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
    style: TextStyle? = null,
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
    style: TextStyle? = null,
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
    style: TextStyle? = null,
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
    style: TextStyle? = null,
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
    style: TextStyle? = null,
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
    style: TextStyle? = null,
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
    // 平台适配点(T.29 富文本):spanStyles 切分后的段列表(全覆盖)
    segments: List<StyleSegment> = emptyList(),
    // 平台适配点(T.29):字号渲染缩放(18sp → 2x),透传给布局/绘制
    scale: Float = 1f,
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
                segments,
                scale,
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
                segments,
                scale,
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
    // 平台适配点(T.29 富文本):spanStyles 切分后的段列表(全覆盖)
    segments: List<StyleSegment> = emptyList(),
    // 平台适配点(T.29):字号渲染缩放(18sp → 2x)
    scale: Float = 1f,
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
                segments = segments,
                scale = scale,
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
// 基准行高动态跟随原版 Font.lineHeight(兼容修改行高的模组)

/**
 * 平台适配点(T.19):TextUnit(sp) → 文本渲染缩放。
 *
 * MC 无原生字号系统,文字大小经渲染矩阵缩放实现(T.10 的 scale 链路)。
 * 换算 = fontSizeToEmPx 唯一入口(1sp == 1px @density1)
 * (MC 平台基准字号;16sp ≈ 1.78x 非整数缩放、非自然字号,尽量避免);
 * 仅支持 sp 单位 —— em 需要基准字号链(TextStyle 已随平台移除),无法解析。
 */
// 平台适配点(T.26):internal —— BasicTextField 同包复用(fontSize → 渲染缩放)
internal fun TextUnit.toTextScale(density: Density): Float {
    require(type == TextUnitType.Sp) {
        "Platform (T.19): fontSize only supports sp units (MC has no native font size system, em cannot be resolved)"
    }
    // sp → px → 缩放:唯一换算入口(P2-B1 公式合一)
    return density.fontSizeToEmPx(value)
}
