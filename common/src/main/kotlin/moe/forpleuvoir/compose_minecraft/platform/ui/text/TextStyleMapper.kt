/*
 * Copyright 2026 The Compose-Minecraft Project
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

package moe.forpleuvoir.compose_minecraft.platform.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.PlatformSpanStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.StyleSegment
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isUnspecified
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

/**
 * Compose [TextStyle] → 平台渲染参数映射(T.28 文本 TextStyle 化)。
 *
 * 平台渲染能力 = MC `Style`(颜色/加粗/斜体/下划线/删除线)+ 渲染矩阵缩放
 * (字号,18sp = 2x 平台基准)+ 渲染 alpha(透明度)。[TextStyle] 里平台
 * 无法表达的字段(letterSpacing/background/shadow/fontFamily/lineHeight/
 * textAlign/... )收集进 [PlatformTextData.ignored] 文档化忽略。
 *
 * 映射规则:
 * - color → MC `TextColor`(只取 RGB,alpha 分量走渲染 alpha);
 * - alpha → 独立渲染 alpha(与图层 alpha 相乘);
 * - fontSize(sp)→ 缩放:value × density × fontScale / 9f(公式同
 *   `TextUnit.toTextScale`,18sp = 2x);Unspecified → 平台默认 18f;
 * - fontWeight ≥ 600 → bold(MC 只有布尔);
 * - fontStyle == Italic → italic;
 * - textDecoration 含 Underline/LineThrough → underlined/strikethrough;
 * - 其余字段忽略(见 [PlatformTextData.ignored])。
 */
data class PlatformTextData(
    /** 映射到 MC `Style` 的属性(color/bold/italic/underline/strikethrough)。 */
    val mcStyle: Style,
    /** 字号渲染缩放(18sp = 2x 平台基准字号);与 BasicText 的 scale 链路一致。 */
    val scale: Float,
    /** 文本透明度(spanStyle.alpha,默认 1f),与图层 alpha 相乘。 */
    val alpha: Float,
    /** 平台无法表达、被文档化忽略的字段名。 */
    val ignored: List<String>,
)

/** 平台默认字号(sp):与 [androidx.compose.foundation.text.BasicText] 默认一致(18sp = 2x)。 */
const val MC_DEFAULT_FONT_SIZE_SP: Float = 18f

/**
 * [TextStyle] → [PlatformTextData]。仅在组合期调用(density 来自 [androidx.compose.ui.platform.LocalDensity])。
 */
fun TextStyle.toPlatformData(density: Density): PlatformTextData {
    val span = spanStyle
    val ignored = buildList {
        // 段落级:布局端不支持(MC 行高固定 9px×scale、单行方向、无首行缩进)
        if (paragraphStyle.textAlign != null) add("textAlign")
        if (paragraphStyle.textDirection != TextDirection.Unspecified) add("textDirection")
        if (!paragraphStyle.lineHeight.isUnspecified) add("lineHeight")
        if (paragraphStyle.textIndent != null) add("textIndent")
        if (paragraphStyle.lineBreak != null) add("lineBreak")
        if (paragraphStyle.hyphens != null) add("hyphens")
        if (paragraphStyle.platformStyle != null) add("platformParagraphStyle")
        // 字符级:MC 无法表达
        if (span.fontFamily != null) add("fontFamily")
        if (!span.fontSize.isUnspecified && span.fontSize.type == TextUnitType.Em) add("fontSizeEm")
        if (span.fontSynthesis != null) add("fontSynthesis")
        if (span.fontFeatureSettings != null) add("fontFeatureSettings")
        if (!span.letterSpacing.isUnspecified) add("letterSpacing")
        if (span.baselineShift != null) add("baselineShift")
        if (span.textGeometricTransform != null) add("textGeometricTransform")
        if (span.localeList != null) add("localeList")
        if (span.background != Color.Unspecified) add("background")
        if (span.shadow != null) add("shadow")
        if (span.drawStyle != null) add("drawStyle")
        // T.28:PlatformSpanStyle 承载 MC 原版 Style 渲染特性(obfuscated/shadowColor/
        // clickEvent/hoverEvent/insertion/font),已映射进 mcStyle;仅 blendRadius 忽略
        if (span.platformStyle?.blendRadius != null) add("platformSpanStyle.blendRadius")
        if (platformStyle != null) add("platformStyle")
        if (span.brush != null) add("brush")
    }

    var mcStyle: Style = Style.EMPTY
    // 颜色:只取 RGB(MC Style 颜色不携带 alpha,透明度走渲染 alpha)
    if (span.color != Color.Unspecified) {
        mcStyle = mcStyle.withColor(TextColor.fromRgb(span.color.toRgb()))
    }
    // 加粗:MC 只有布尔,阈值对齐 Compose/Skia(SemiBold = 600)
    val fontWeight = span.fontWeight
    if (fontWeight != null && fontWeight.weight >= FontWeight.SemiBold.weight) {
        mcStyle = mcStyle.withBold(true)
    }
    if (span.fontStyle == FontStyle.Italic) {
        mcStyle = mcStyle.withItalic(true)
    }
    val decoration = span.textDecoration
    if (decoration != null) {
        if (decoration.contains(TextDecoration.Underline)) mcStyle = mcStyle.withUnderlined(true)
        if (decoration.contains(TextDecoration.LineThrough)) mcStyle = mcStyle.withStrikethrough(true)
    }
    // T.28:MC 原版 Style 渲染/交互特性(经 PlatformSpanStyle 承载,双向不丢失)
    span.platformStyle?.let { ps ->
        if (ps.obfuscated != null) mcStyle = mcStyle.withObfuscated(ps.obfuscated)
        if (ps.shadowColor != null) mcStyle = mcStyle.withShadowColor(ps.shadowColor.toArgb())
        if (ps.clickEvent != null) mcStyle = mcStyle.withClickEvent(ps.clickEvent)
        if (ps.hoverEvent != null) mcStyle = mcStyle.withHoverEvent(ps.hoverEvent)
        if (ps.insertion != null) mcStyle = mcStyle.withInsertion(ps.insertion)
        if (ps.font != null) mcStyle = mcStyle.withFont(ps.font)
    }

    // 字号:sp → 渲染缩放(公式同 TextUnit.toTextScale,见 T.19/T.26);Unspecified/em → 平台默认
    val fontSizeSp =
        if (span.fontSize.isUnspecified || span.fontSize.type != TextUnitType.Sp) {
            MC_DEFAULT_FONT_SIZE_SP
        } else {
            span.fontSize.value
        }
    val scale = fontSizeSp * density.density * density.fontScale / 9f

    return PlatformTextData(
        mcStyle = mcStyle,
        scale = scale,
        // 官方语义:未指定 alpha(无颜色/无 brush)时 span.alpha = Float.NaN
        // (TextForegroundStyle.Unspecified),须按 1f 处理,否则 NaN 直传渲染端
        // 致 alphaByte=0 全透明空白(T.28)
        alpha = if (span.alpha.isNaN()) 1f else span.alpha,
        ignored = ignored,
    )
}

/**
 * MC `Style` → Compose [TextStyle] 尽力映射(T.28,反向工具)。
 * 颜色补全 alpha(TextColor 无 alpha,默认不透明);bold → FontWeight.Bold;
 * italic → FontStyle.Italic;underlined/strikethrough → TextDecoration;
 * MC 独有渲染特性(obfuscated/shadowColor/clickEvent/hoverEvent/insertion/font)
 * 经 [PlatformSpanStyle] 承载进 spanStyle.platformStyle(双向不丢失)。
 */
fun Style.toTextStyle(): TextStyle {
    var decoration: TextDecoration? = null
    if (underlinedRaw == true) {
        decoration = (decoration ?: TextDecoration.None) + TextDecoration.Underline
    }
    if (strikethroughRaw == true) {
        decoration = (decoration ?: TextDecoration.None) + TextDecoration.LineThrough
    }
    val mcPlatform =
        PlatformSpanStyle(
            obfuscated = obfuscatedRaw,
            shadowColor = shadowColor?.let { Color(it) },
            clickEvent = clickEvent,
            hoverEvent = hoverEvent,
            insertion = insertion,
            font = fontOriginal,
        )
    return TextStyle(
        spanStyle =
            SpanStyle(
                color = color?.toColor() ?: Color.Unspecified,
                fontWeight = if (boldRaw == true) FontWeight.Bold else null,
                fontStyle = if (italicRaw == true) FontStyle.Italic else null,
                textDecoration = decoration,
                platformStyle = mcPlatform,
            ),
        paragraphStyle = ParagraphStyle(),
    )
}

// 复用 StyleExtensions 的 toRgb/toColor(同包 internal)

/**
 * 段级映射(T.29 富文本):把 [SpanStyle] 增量应用到基础 MC [Style]。
 * 规则与 [TextStyle.toPlatformData] 的 mcStyle 构建一致(color/bold/italic/
 * decoration/platformStyle),**不含字号** —— 段级字号暂不参与布局
 * (布局统一 base scale,文档标注)。
 */
fun SpanStyle.toMcStyle(base: Style): Style {
    var s = base
    if (color != Color.Unspecified) {
        s = s.withColor(TextColor.fromRgb(color.toRgb()))
    }
    val fontWeight = fontWeight
    if (fontWeight != null && fontWeight.weight >= FontWeight.SemiBold.weight) {
        s = s.withBold(true)
    }
    if (fontStyle == FontStyle.Italic) {
        s = s.withItalic(true)
    }
    val decoration = textDecoration
    if (decoration != null) {
        if (decoration.contains(TextDecoration.Underline)) s = s.withUnderlined(true)
        if (decoration.contains(TextDecoration.LineThrough)) s = s.withStrikethrough(true)
    }
    platformStyle?.let { ps ->
        if (ps.obfuscated != null) s = s.withObfuscated(ps.obfuscated)
        if (ps.shadowColor != null) s = s.withShadowColor(ps.shadowColor.toArgb())
        if (ps.clickEvent != null) s = s.withClickEvent(ps.clickEvent)
        if (ps.hoverEvent != null) s = s.withHoverEvent(ps.hoverEvent)
        if (ps.insertion != null) s = s.withInsertion(ps.insertion)
        if (ps.font != null) s = s.withFont(ps.font)
    }
    return s
}

/**
 * 富文本(T.29):把 [AnnotatedString] 的 spanStyles(Compose 段样式)切分为
 * **全覆盖**的 [StyleSegment] 列表 —— 段间无样式覆盖的文本用 [baseStyle],
 * 满足渲染端 recordSegmentedTextDraw 的段覆盖要求(它只兜底行尾)。
 * 优先级:同一区间被多个 span 覆盖时,后声明者优先(与官方注解合并语义一致)。
 */
fun AnnotatedString.toStyleSegments(baseStyle: Style): List<StyleSegment> {
    if (spanStyles.isEmpty()) return listOf(StyleSegment(baseStyle, text))
    // 边界点:0 + 全部 span 的 start/end + length(sortedSet 去重)
    val bounds = sortedSetOf<Int>()
    bounds.add(0)
    bounds.add(text.length)
    for (range in spanStyles) {
        bounds.add(range.start.coerceIn(0, text.length))
        bounds.add(range.end.coerceIn(0, text.length))
    }
    val points = bounds.toIntArray()
    val result = ArrayList<StyleSegment>(points.size - 1)
    for (i in 0 until points.size - 1) {
        val start = points[i]
        val end = points[i + 1]
        if (end <= start) continue
        var segStyle = baseStyle
        for (range in spanStyles) {
            if (range.start <= start && end <= range.end) {
                segStyle = range.item.toMcStyle(baseStyle)
            }
        }
        result.add(StyleSegment(segStyle, text.substring(start, end)))
    }
    return result
}

/**
 * 默认字体填充(T.30):[TextStyle] 未显式指定字体(`spanStyle.platformStyle.font`
 * 为 null)时补 [font];已指定则原样返回。保留其余字段与 platformStyle 的
 * 其它 MC 特性(obfuscated/shadowColor 等)。供 BasicText 组合端读取
 * [LocalDefaultFont] 后调用。
 */
fun TextStyle.withDefaultFont(font: FontDescription): TextStyle {
    val pf = spanStyle.platformStyle
    val merged =
        if (pf?.font == null) {
            PlatformSpanStyle(
                obfuscated = pf?.obfuscated,
                shadowColor = pf?.shadowColor,
                clickEvent = pf?.clickEvent,
                hoverEvent = pf?.hoverEvent,
                insertion = pf?.insertion,
                font = font,
            )
        } else {
            pf
        }
    return if (merged === pf) {
        this
    } else {
        // TextStyle.copy 展平了 spanStyle 字段(无 spanStyle 参数),直接构造保留其余字段
        TextStyle(spanStyle = spanStyle.copy(platformStyle = merged), paragraphStyle = paragraphStyle)
    }
}
