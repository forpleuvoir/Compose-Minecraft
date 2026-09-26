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

import moe.forpleuvoir.compose_minecraft.platform.render.text.FontResolver
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.PlatformSpanStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.StyleSegment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isUnspecified
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import com.mojang.logging.LogUtils

/**
 * Compose [TextStyle] → 平台渲染参数映射(文本 TextStyle 化)。
 *
 * 平台渲染能力 = MC `Style`(颜色/加粗/斜体/下划线/删除线)+ 渲染矩阵缩放
 * (字号,18sp = 2x 平台基准)+ 渲染 alpha(透明度)+ 段落水平对齐。
 * [TextStyle] 里平台无法表达的字段(letterSpacing/background/shadow/fontFamily/
 * lineHeight/... )收集进 [PlatformTextData.ignored] 文档化忽略。
 *
 * 映射规则:
 * - color → MC `TextColor`(只取 RGB,alpha 分量走渲染 alpha);
 * - alpha → 独立渲染 alpha(与图层 alpha 相乘);
 * - fontSize(sp)→ emPx:value × density × fontScale(fontSizeToEmPx 唯一入口);
 *   Unspecified → 当前生效字体的 defaultSizeSp(A6);
 * - fontWeight ≥ 600 → bold(MC 只有布尔);
 * - fontStyle == Italic → italic;
 * - textDecoration 含 Underline/LineThrough → underlined/strikethrough;
 * - paragraphStyle.textAlign → [PlatformTextData.textAlign](段落级,经载荷下沉到
 *   段落布局;`Justify` 平台不支持,布局端按 Start 降级);
 * - 其余字段忽略(见 [PlatformTextData.ignored])。
 */
/** 映射层共享日志(忽略字段告警等) */
private val FONT_LOGGER = LogUtils.getLogger()

/** 已告警过的忽略字段(进程内每字段一次,防组合期刷屏) */
private val IGNORED_FIELD_WARNED =
    java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

data class PlatformTextData(
    /** 映射到 MC `Style` 的属性(color/bold/italic/underline/strikethrough)。 */
    val mcStyle: Style,
    /** 字号渲染缩放(18sp = 2x 平台基准字号);与 BasicText 的 scale 链路一致。 */
    val scale: Float,
    /**
     * 段落水平对齐(`ParagraphStyle.textAlign`,`Unspecified` = 未设置)。
     * 平台适配点:MC `Style` 是字符级样式,无段落属性 —— 对齐与 [scale] 同级,
     * 作为独立字段沿布局链下沉(见 [PlatformTextPayload])。
     */
    val textAlign: TextAlign,
    /** 文本透明度(spanStyle.alpha,默认 1f),与图层 alpha 相乘。 */
    val alpha: Float,
    /** 平台适配点:渐变画刷(spanStyle.brush,非 SolidColor),绘制端逐字形取色;null = 无。 */
    val brush: Brush? = null,
    /** 平台无法表达、被文档化忽略的字段名。 */
    val ignored: List<String>,
)

/** 平台默认字号(sp):随当前默认字体的 defaultSizeSp(A6/I5) */
fun platformDefaultFontSizeSp(): Float =
    FontResolver.defaultFont().defaultSizeSp

/**
 * 字号 → **最终像素 em(emPx)** 唯一换算入口(A4 尺寸空间唯一):
 * `emPx = sp × density × fontScale`(1sp == 1px @density1,**无基准除法**)。
 *
 * 全部调用点(TextStyleMapper/toTextScale/autoSize)已收敛至此;
 * 禁止再出现第二套字号公式(census-A 的教训)。
 */
internal fun Density.fontSizeToEmPx(sp: Float): Float =
    sp * density * fontScale

/**
 * [TextStyle] → [PlatformTextData]。仅在组合期调用(density 来自 [androidx.compose.ui.platform.LocalDensity])。
 */
fun TextStyle.toPlatformData(density: Density): PlatformTextData {
    val span = spanStyle
    val ignored = buildList {
        // 段落级:布局端不支持(MC 行高固定 9px×scale、单行方向、无首行缩进)
        // 存疑(未修复):textIndent / lineBreak / hyphens 为不可空类型,
        // 其判空恒真 —— 此处实际语义是"设置过就记录",与其余项的判空语义不一致。
        // 当前行为正常,复现"被丢弃样式统计不准"时优先看此处。
        // 注:textAlign 已不再忽略(见 PlatformTextData.textAlign,经载荷下沉到段落布局)
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
        // PlatformSpanStyle 承载 MC 原版 Style 渲染特性(obfuscated/shadowColor/
        // clickEvent/hoverEvent/insertion/font),已映射进 mcStyle;仅 blendRadius 忽略
        if (span.platformStyle?.blendRadius != null) add("platformSpanStyle.blendRadius")
        if (platformStyle != null) add("platformStyle")
    }
    // fail-loud(font-system 重构):被忽略字段逐字段去重告警一次,
    // 消除「静默失效」—— 业务样式写了却不生效时日志可见,而非无声吞掉
    ignored.forEach { name ->
        if (IGNORED_FIELD_WARNED.add(name)) {
            FONT_LOGGER.warn("[ComposeMinecraft] Text style field '{}' platform not supported, ignored.", name)
        }
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
    // MC 原版 Style 渲染/交互特性(经 PlatformSpanStyle 承载,双向不丢失)
    span.platformStyle?.let { ps ->
        if (ps.obfuscated != null) mcStyle = mcStyle.withObfuscated(ps.obfuscated)
        if (ps.shadowColor != null) mcStyle = mcStyle.withShadowColor(ps.shadowColor.toArgb())
        if (ps.clickEvent != null) mcStyle = mcStyle.withClickEvent(ps.clickEvent)
        if (ps.hoverEvent != null) mcStyle = mcStyle.withHoverEvent(ps.hoverEvent)
        if (ps.insertion != null) mcStyle = mcStyle.withInsertion(ps.insertion)
        if (ps.font != null) mcStyle = mcStyle.withFont(ps.font)
    }

    // 字号:sp → 渲染缩放(公式同 TextUnit.toTextScale,见);Unspecified/em → 平台默认
    val fontSizeSp =
        if (span.fontSize.isUnspecified || span.fontSize.type != TextUnitType.Sp) {
            platformDefaultFontSizeSp()
        } else {
            span.fontSize.value
        }
    // 字号缩放 = 唯一换算入口(公式合一;像素 em 12 / stb 模式原版行高 9)
    val scale = density.fontSizeToEmPx(fontSizeSp)

    return PlatformTextData(
        mcStyle = mcStyle,
        scale = scale,
        // 段落对齐:TextAlign.Unspecified = 未设置(布局端按 Start 处理,与官方一致)
        textAlign = paragraphStyle.textAlign,
        // 官方语义:未指定 alpha(无颜色/无 brush)时 span.alpha = Float.NaN
        // (TextForegroundStyle.Unspecified),须按 1f 处理,否则 NaN 直传渲染端
        // 致 alphaByte=0 全透明空白
        alpha = if (span.alpha.isNaN()) 1f else span.alpha,
        // 平台适配点:渐变画刷透传(SolidColor 已并入 color,其余 Brush
        // 由绘制端逐字形采样取色);null = 无
        brush = span.brush?.takeIf { it !is SolidColor },
        ignored = ignored,
    )
}

/**
 * MC `Style` → Compose [TextStyle] 尽力映射(反向工具)。
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
 * 段级映射(富文本):把 [SpanStyle] 增量应用到基础 MC [Style]。
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
 * 富文本:把 [AnnotatedString] 的 spanStyles(Compose 段样式)切分为
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
        var fontSizeSp: Float? = null
        for (range in spanStyles) {
            if (range.start <= start && end <= range.end) {
                segStyle = range.item.toMcStyle(baseStyle)
                // 平台适配点(段级字号):携带 span 的 fontSize(仅 sp;em 平台不支持,忽略)
                val fs = range.item.fontSize
                if (fs != null && fs.isSp) fontSizeSp = fs.value
            }
        }
        result.add(StyleSegment(segStyle, text.substring(start, end), fontSizeSp))
    }
    return result
}

/**
 * 默认字体填充:[TextStyle] 未显式指定字体(`spanStyle.platformStyle.font`
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
