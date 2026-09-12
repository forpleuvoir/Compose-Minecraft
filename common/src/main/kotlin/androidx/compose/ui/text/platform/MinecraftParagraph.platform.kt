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
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import moe.forpleuvoir.compose_minecraft.platform.render.text.FontResolver
import moe.forpleuvoir.compose_minecraft.platform.render.text.ResolvedFont
import moe.forpleuvoir.compose_minecraft.platform.ui.text.fontOriginal
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

// ─────────────────────────────────────────────────────────────────────────────
// Minecraft 平台文本后端(文本系统 MC 化,T.2 + T.5 + T.6)
//
// 平台适配点(与官方 Paragraph.skiko.kt 的差异):
// - 样式类型为 MC [Style](能力字段 1:1),TextStyle 已完全移除;
// - 度量统一用 MC Font(T.TT 起:度量来源可切换,原版 splitter / TrueType HMetrics);
// - 光标/命中/词界位置用**前缀精确宽度** Font.width(前缀)(T.5,同 EditBox.getScreenX);
// - 命中测试用 Font.plainSubstrByWidth(同 EditBox.findClickedPositionInText);
// - 水平滚动恢复原版 ScrollState 模型(displayPos 截断已移除,T.6 修复);
// - 富文本(AnnotatedString 多 SpanStyle)第一版不做,只支持统一 [Style]。
// ─────────────────────────────────────────────────────────────────────────────

/** 一行文本的布局结果(P2-B3:行盒/基线 = 行内字符绑定字体的最大自然度量) */
internal data class MinecraftTextLine(
    val start: Int,
    val end: Int,
    val width: Float,
    /** 行盒高(px,最终像素空间;换行符不计入) */
    val lineBoxPx: Float = 0f,
    /** 行顶到基线(px,最终像素空间) */
    val baselinePx: Float = 0f,
)

/**
 * 平台适配点(InlineContent):占位符排版原子 —— [AnnotatedString.Range]<[Placeholder]>
 * 的字符区间 + 布局空间(1x 预缩放)尺寸。
 * 官方语义:占位符是**原子不可断**元素,替代文本不显示、不可放光标;
 * Placeholder(sp) 经 density 转 px 后再除以 scale 进入布局空间(与 maxWidth 同规则)。
 */
internal data class PlaceholderSpan(
    val start: Int,
    val end: Int,
    val width: Float,
    val height: Float,
    val verticalAlign: PlaceholderVerticalAlign,
)

/**
 * 平台适配点(T.3):MC [Component] 展平后的样式段 —— [text] 使用 [style] 绘制。
 * 由 `BasicText(component)` 经 `component.flatten()` 展平得到,
 * 每段 style 已是"Component 自身属性优先、缺失用 defaultStyle 补"的合并结果(MC applyTo 语义)。
 */
data class StyleSegment(
    val style: Style,
    val text: String,
    /**
     * 平台适配点(段级字号):该段 SpanStyle.fontSize 的 sp 数值(null = 未指定,
     * 继承基础字号);em 单位平台不支持,忽略。由 TextStyleMapper.toStyleSegments 填充。
     */
    val fontSizeSp: Float? = null,
)

/**
 * Minecraft 字形度量布局模型(P1 重构:度量按 run 的字体解析,总设计 I1/I2)。
 *
 * 布局与渲染统一使用同源字形度量:
 * - 行宽 = 每字符 advance 累积(`RunMetrics.advance`,逐码点精确);
 * - 行高 = `metrics.lineHeight`;基线 = `metrics.baselineFromTop`;
 * - 度量来源由构造方按样式经 [FontResolver.resolveNative] 解析后传入 ——
 *   同一布局的宽度/行高/基线恒定,且与该 run 最终渲染所用字体同源
 *   (不再有全局快照;配置切换后新建布局使用新解析)。
 */
internal class MinecraftTextLayout(
    val text: String,
    val maxWidth: Float,
    /** 平台适配点(InlineContent):占位符排版原子(按声明序;布局时按 start 排序)。 */
    val placeholders: List<PlaceholderSpan> = emptyList(),
    /**
     * 基础字体绑定(P2-B3,总设计 A3/I2):段级字号缺省、省略号等基础 run,以及
     * **空文本占位行**的度量来源 —— 空文本无字符索引可取,故由调用方显式传入。
     */
    internal val baseFont: ResolvedFont,
    /**
     * 每字符字体绑定:段级字号 = 该段独立 emPx 的
     * ResolvedFont;度量恒等于该字符最终渲染所用字体。
     */
    internal val fonts: (Int) -> ResolvedFont,
) {

    /** char index → 字体(越界钳制;空文本回退 [baseFont]) */
    internal fun fontAt(i: Int): ResolvedFont =
        if (text.isEmpty()) baseFont else fonts(i.coerceIn(0, text.length - 1))

    /**
     * 累积字符浮点宽度(cumFloatWidths 数组第 i 项 = text 前 i 个字符的 advance 浮点和)。
     *
     * MC [Font.width] = `Mth.ceil(splitter.stringWidth(s))`,对每个子串单独 ceil。
     * 由于 stringWidth 是按码点累加的(StringSplitter.stringWidth 遍历每个 codepoint
     * 求和 widthProvider.getWidth,无字距/无上下文依赖),`stringWidth(s[a..b))` 精确等于
     * `cumFloatWidths[b] - cumFloatWidths[a]`。TrueType 度量(stbtt HMetrics)同为
     * 逐码点累加、无字距,前缀和语义不变。
     *
     * 性能(T.34):替代每次 `font.width(text.substring(start, end+1))` 的 O(n) 子串创建
     * + O(n) 宽度计算,降为 O(1) 数组查表。computeLines 从 O(n²) 降为 O(n)。
     */
    private val cumFloatWidths: FloatArray = run {
        val n = text.length
        // 第一遍:每 char 的增量宽度(代理对占 2 char —— 宽度记首个 char、第二个为 0,
        // 前缀查询语义与旧实现「中间位置与终点同值」等价)
        val inc = FloatArray(n)
        var i = 0
        var prevCp = -1
        while (i < n) {
            val cp = text.codePointAt(i)
            val cc = Character.charCount(cp)
            // P2-B3:字符增量 = 该字符绑定字体的 advance + kern(绝对 emPx,
            // 与绘制端 pen 推进严格同源);字偶距由提供字形的字体计算
            fonts(i).metrics.let { m -> inc[i] = m.advance(cp) + m.kern(prevCp, cp) }
            if (cc == 2) inc[i + 1] = 0f
            prevCp = cp
            i += cc
        }
        // 平台适配点(InlineContent):占位符原子 —— 内部增量清零(子区间宽 0,光标不落入),
        // 整段宽度记在段尾 char 的增量上。必须在「增量」阶段调整后再统一累积 ——
        // 若只在累积结果上局部覆写各 span 区间,span 之后的全部位置仍携带替代文本的
        // 旧宽度(实测 ⑬:tag rect.x 被 "[红块]" 字面宽度污染 +34px、断行整体错位)。
        for (span in placeholders.sortedBy { it.start }) {
            val s = span.start.coerceIn(0, n)
            val e = span.end.coerceIn(s, n)
            if (e <= s) continue
            for (j in s until e) inc[j] = 0f
            inc[e - 1] += span.width
        }
        // 第二遍:重建累积
        val arr = FloatArray(n + 1)
        var cum = 0f
        for (j in 0 until n) {
            cum += inc[j]
            arr[j + 1] = cum
        }
        arr
    }

    /**
     * text[from, to) 的 MC 字形宽度(int 语义,与原版 `Font.width` 一致 ——
     * `ceil(stringWidth)`,返回 Float 等价于 `font.width(substring).toFloat()`)。
     */
    private fun substringWidth(from: Int, to: Int): Float {
        if (to <= from) return 0f
        val end = minOf(to, text.length)
        if (end <= from) return 0f
        return ceil(cumFloatWidths[end] - cumFloatWidths[from])
    }

    val lines: List<MinecraftTextLine> by lazy { computeLines() }

    /** 段落宽度(最大行宽) */
    val width: Float
        get() = lines.maxOfOrNull { it.width } ?: 0f

    /** 段落高度(各行盒高之和) */
    val height: Float
        get() = lines.sumOf { it.lineBoxPx.toDouble() }.toFloat()

    /** 最宽单词宽度(MC 字形度量;占位符按原子计入) */
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
            maxW = maxOf(maxW, substringWidth(start, cur))
        }
        // 平台适配点(InlineContent):占位符是原子"单词",宽度直接参与最小固有宽
        for (span in placeholders) {
            maxW = maxOf(maxW, span.width)
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
            maxW = maxOf(maxW, substringWidth(cur, end))
            if (nl == -1) break
            cur = nl + 1
        }
        maxW
    }

    /**
     * 前缀精确宽度(T.5):文本 [from, to) 的 MC 字形宽度。
     * 替换第一版的 avgCharWidth 近似,与 MC EditBox.getScreenX 同源。
     * 平台适配点(T.11 修复):行尾可能含 `\n`(exclusive end),宽度按不含换行符的文本计算。
     */
    fun prefixWidth(from: Int, to: Int): Float {
        if (to <= from) return 0f
        val end = minOf(to, text.length)
        // 排除行尾换行符(若有)
        val trimmedEnd = if (end > from && text[end - 1] == '\n') end - 1 else end
        if (trimmedEnd <= from) return 0f
        return ceil(cumFloatWidths[trimmedEnd] - cumFloatWidths[from])
    }

    /** 占位符原子按 start 排序(computeLines 扫描用;数量小,线性查找足够)。 */
    private val sortedSpans: List<PlaceholderSpan> = placeholders.sortedBy { it.start }

    /** 起点恰为 [pos] 的占位符(无则 null)。 */
    private fun spanStartingAt(pos: Int): PlaceholderSpan? =
        sortedSpans.firstOrNull { it.start == pos }

    /** 严格覆盖 [pos](start < pos < end,即内部位置)的占位符(无则 null)。 */
    private fun spanCoveringInterior(pos: Int): PlaceholderSpan? =
        sortedSpans.firstOrNull { it.start < pos && pos < it.end }

    private fun computeLines(): List<MinecraftTextLine> {
        val result = ArrayList<MinecraftTextLine>()
        if (text.isEmpty()) {
            // 空文本也要有行盒:光标高度(getCursorRect)/行高 API 取自此行,缺则光标塌成 0 高
            result.add(
                MinecraftTextLine(
                    start = 0,
                    end = 0,
                    width = 0f,
                    lineBoxPx = baseFont.lineHeightPx,
                    baselinePx = baseFont.baselineFromTopPx,
                )
            )
            return result
        }
        val wrap = maxWidth.isFinite() && maxWidth > 0
        // UAX #14 断点表(font-system 重构 T.RF-B):整串一次构建,懒加载 ——
        // 仅存在受限宽度分段时才付 BreakIterator 的 O(n) 成本
        var breakOffsets: IntArray? = null
        var segStart = 0
        while (segStart < text.length) {
            val nl = text.indexOf('\n', segStart)
            // 行尾含换行符(exclusive end,官方 getLineEnd 语义,T.11)
            val segEnd = if (nl == -1) text.length else nl + 1
            if (segStart >= segEnd) {
                result.add(MinecraftTextLine(segStart, segEnd, 0f))
            } else if (!wrap) {
                result.add(
                    MinecraftTextLine(
                        segStart, segEnd,
                        substringWidth(segStart, if (nl == -1) segEnd else nl),
                    )
                )
            } else {
                if (breakOffsets == null) breakOffsets = collectLineBreakOffsets()
                appendWrappedLines(result, segStart, segEnd, nl, breakOffsets)
            }
            if (nl == -1) break
            segStart = nl + 1
            if (segStart == text.length) {
                result.add(MinecraftTextLine(segStart, segStart, 0f))
                break
            }
        }
        // P2-B3:行盒/基线 = 行内字符绑定字体的最大自然度量(换行符不计)
        return result.map { line ->
            val contentEnd = if (line.end > line.start && text[line.end - 1] == '\n') line.end - 1 else line.end
            var box = 0f
            var baseline = 0f
            if (contentEnd > line.start) {
                for (j in line.start until contentEnd) {
                    val f = fontAt(j)
                    box = maxOf(box, f.lineHeightPx)
                    baseline = maxOf(baseline, f.baselineFromTopPx)
                }
            } else {
                val f = fontAt(line.start)
                box = f.lineHeightPx
                baseline = f.baselineFromTopPx
            }
            line.copy(lineBoxPx = box, baselinePx = baseline)
        }
    }

    /** UAX #14 行断点(getLineInstance):CJK 可逐字断行、闭括号/句读不悬行首。 */
    private fun collectLineBreakOffsets(): IntArray {
        val iterator = java.text.BreakIterator.getLineInstance()
        iterator.setText(text)
        val offsets = ArrayList<Int>(text.length / 4 + 1)
        var p = iterator.first()
        while (p != java.text.BreakIterator.DONE) {
            offsets.add(p)
            p = iterator.next()
        }
        return offsets.toIntArray()
    }

    /**
     * 单个 `\n` 分段内的贪心换行填充(font-system 重构,T.RF-B):
     * - 断点候选 = UAX #14 边界 ∪ 占位符原子端点(原子整体判定,不可截半);
     * - 在不超过 maxWidth 的最后一个候选处切行;软切行的行尾空白随断点吞并
     *   (迭代器边界在空白段之后 —— 与官方 Skia 视觉一致,且不产生行首空白);
     * - 无任何候选可入本行时按字符硬切、至少推进 1(T.12 防推进死循环);
     * - 宽度查询全部走 [substringWidth] 前缀和 O(1)(T.34 语义保留)。
     */
    private fun appendWrappedLines(
        result: MutableList<MinecraftTextLine>,
        segStart: Int,
        segEnd: Int,
        nl: Int,
        breaks: IntArray,
    ) {
        val contentEnd = if (nl == -1) segEnd else nl
        if (contentEnd <= segStart) {
            // 空白分段(如孤立 `\n`):记零宽空行(与原实现一致)
            result.add(MinecraftTextLine(segStart, segEnd, 0f))
            return
        }
        // 本分段内的候选切点(breaks 升序;取 (segStart, contentEnd] 区间,
        // 落在占位符内部的迭代器边界随原子消费一并丢弃)
        val candidates = ArrayList<Int>()
        var bi = breaks.binarySearch(segStart + 1).let { if (it >= 0) it else -it - 1 }
        var cur = segStart
        while (cur < contentEnd) {
            val span = spanStartingAt(cur)
            if (span != null) {
                val atomEnd = minOf(span.end, contentEnd)
                candidates.add(atomEnd)
                cur = atomEnd
                while (bi < breaks.size && breaks[bi] <= cur) bi++ // 原子内部边界无效
                continue
            }
            // 普通文本单元:吃到下一占位符起点或分段尾
            var unitEnd = contentEnd
            for (s in sortedSpans) {
                if (s.start > cur) {
                    unitEnd = minOf(s.start, contentEnd)
                    break
                }
            }
            while (bi < breaks.size && breaks[bi] <= unitEnd) {
                if (breaks[bi] > cur) candidates.add(breaks[bi])
                bi++
            }
            if (candidates.lastOrNull() != unitEnd) candidates.add(unitEnd)
            cur = unitEnd
        }
        // 贪心装填:每行吃下「不超过 maxWidth 的最后一个候选切点」
        var start = segStart
        var ci = 0
        while (start < contentEnd) {
            // 占位符宽于行宽 → 独占一行(强制推进防御,同 T.12)
            val startSpan = spanStartingAt(start)
            if (startSpan != null && startSpan.width > maxWidth) {
                result.add(MinecraftTextLine(start, startSpan.end, startSpan.width))
                start = startSpan.end
                while (ci < candidates.size && candidates[ci] <= start) ci++
                continue
            }
            var lastFit = start
            while (ci < candidates.size && candidates[ci] <= contentEnd) {
                if (substringWidth(start, candidates[ci]) <= maxWidth) {
                    lastFit = candidates[ci]
                    ci++
                } else break
            }
            when {
                lastFit == contentEnd -> {
                    // 余下内容整段放得下:本分段收尾(行区间含行尾 `\n`,宽度不计)
                    result.add(MinecraftTextLine(start, segEnd, substringWidth(start, contentEnd)))
                    start = segEnd
                }
                lastFit > start -> {
                    // 软切:吞并行尾空白(不越入占位符内部);空白行直接跳过不占盒
                    var lineEnd = lastFit
                    while (lineEnd > start &&
                        text[lineEnd - 1] == ' ' &&
                        spanCoveringInterior(lineEnd - 1) == null
                    ) lineEnd--
                    if (lineEnd > start) {
                        result.add(MinecraftTextLine(start, lineEnd, substringWidth(start, lineEnd)))
                    }
                    start = lastFit
                }
                else -> {
                    // 首个候选即超宽:按字符硬切(至少推进 1 字符,T.12)
                    var k = 1
                    while (start + k < contentEnd &&
                        substringWidth(start, start + k + 1) <= maxWidth
                    ) k++
                    result.add(MinecraftTextLine(start, start + k, substringWidth(start, start + k)))
                    start += k
                    while (ci < candidates.size && candidates[ci] <= start) ci++
                }
            }
        }
    }
}

/**
 * Minecraft 平台 ParagraphIntrinsics 实现。
 * 平台适配点(T.2):样式参数 TextStyle → MC [Style];
 * 排版方向固定 LTR(MC 文本第一版不支持 BiDi),fontSize/字号被忽略。
 */
internal class MinecraftParagraphIntrinsics(
    val text: String,
    internal val style: Style,
    private val annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    private val density: Density,
    private val fontFamilyResolver: FontFamily.Resolver,
    /** 平台适配点(T.3):MC Component 展平后的多段样式;空 = 单样式(旧行为)。 */
    internal val segments: List<StyleSegment> = emptyList(),
    /** 平台适配点(T.10):文本缩放(1f = 原样;布局尺寸与字形矩阵同步缩放)。 */
    internal val scale: Float = 1f,
) : ParagraphIntrinsics {
    val textDirection: ResolvedTextDirection = ResolvedTextDirection.Ltr

    /**
     * 平台适配点(InlineContent):占位符排版原子。
     * Placeholder(sp) 经 [density] 转 px,再除以 [scale] 进入布局空间(与 maxWidth 同规则)。
     */
    internal val placeholderSpans: List<PlaceholderSpan> = placeholders.map { range ->
        PlaceholderSpan(
            start = range.start,
            end = range.end,
            width = with(density) { range.item.width.toPx() },
            height = with(density) { range.item.height.toPx() },
            verticalAlign = range.item.placeholderVerticalAlign,
        )
    }

    /**
     * 基础字体绑定(P2-B3):按基础字号 emPx([scale])解析 —— 未显式指定
     * 字体/字号的字符使用;段级字号字符使用各自的 [ResolvedFont](I1/I2)。
     */
    internal val resolvedFont: ResolvedFont =
        FontResolver.resolveForRun(
            style.fontOriginal,
            moe.forpleuvoir.compose_minecraft.platform.render.text.MeasureSpec(scale, style = style),
        )

    /**
     * 每字符字体绑定(P2-B3,取代 charScales 相对系数体系):
     * 段级字号 = 该段独立 emPx 的字体解析;未指定段字号的字符继承基础绑定。
     */
    internal val charFonts: List<ResolvedFont> = run {
        if (segments.isEmpty()) return@run List(text.length) { resolvedFont }
        val arr = arrayOfNulls<ResolvedFont>(text.length)
        var offset = 0
        for (seg in segments) {
            val end = minOf(offset + seg.text.length, arr.size)
            val f = seg.fontSizeSp?.let { sp ->
                FontResolver.resolveForRun(
                    seg.style.fontOriginal ?: style.fontOriginal,
                    moe.forpleuvoir.compose_minecraft.platform.render.text.MeasureSpec(
                        sp * density.density * density.fontScale,
                        style = seg.style,
                    ),
                )
            } ?: resolvedFont
            for (j in offset until end) arr[j] = f
            offset += seg.text.length
        }
        List(arr.size) { arr[it] ?: resolvedFont }
    }

    private val layout = MinecraftTextLayout(
        text, Float.POSITIVE_INFINITY,
        placeholderSpans,
        baseFont = resolvedFont,
    ) { i -> charFonts[i] }

    override val minIntrinsicWidth: Float = layout.minIntrinsicWidth * scale

    override val maxIntrinsicWidth: Float = layout.maxIntrinsicWidth * scale
}

/**
 * Minecraft 平台 Paragraph 实现。
 * 平台适配点(T.5):精确前缀度量;水平滚动交还原版 ScrollState 模型(T.6 修复)。
 */
internal class MinecraftParagraph(
    private val intrinsics: MinecraftParagraphIntrinsics,
    private val maxLines: Int,
    private val overflow: TextOverflow,
    private val constraints: Constraints,
) : Paragraph {

    private val layout: MinecraftTextLayout = run {
        // 平台适配点(T.10):缩放后可用宽度按 1/scale 换算(布局在缩放前空间度量,绘制时矩阵放大)
        val maxWidth =
            if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat()
            else Float.POSITIVE_INFINITY
        MinecraftTextLayout(
            intrinsics.text, maxWidth,
            intrinsics.placeholderSpans,
            baseFont = intrinsics.resolvedFont,
        ) { i -> intrinsics.charFonts[i] }
    }

    private val visibleLineCount: Int =
        if (maxLines != DefaultMaxLines) minOf(layout.lines.size, maxLines) else layout.lines.size

    /** 平台适配点:Ellipsis 后缀用 MC 视觉惯例的三点(ASCII 字形集保证存在),不用 Unicode `…`。 */
    private val ellipsisSuffix = "..."

    /**
     * Ellipsis 生效时最后一个可见行的**裁剪后**绘制文本(null = 无省略,按原行绘制):
     * 仅当 [didExceedMaxLines] 且 [overflow] == [TextOverflow.Ellipsis] 时非空。
     * 裁剪规则:从行尾收缩至 `前缀宽 + 省略号宽 <= 布局 maxWidth`(行本身已放得下则不收缩);
     * 整行连省略号都放不下时裁为空串(paint 层只画省略号)。命中测试不感知省略号
     *(点击省略号区域定位到原文本行尾),与官方 StaticLayout 近似。
     */
    private val ellipsizedLastLine: String? = run {
        if (overflow != TextOverflow.Ellipsis || !didExceedMaxLines) return@run null
        val lineIndex = visibleLineCount - 1
        if (lineIndex < 0) return@run null
        val line = layout.lines[lineIndex]
        var drawText = intrinsics.text.substring(line.start, line.end)
        if (drawText.endsWith('\n')) drawText = drawText.dropLast(1)
        if (drawText.isEmpty()) return@run null
        // 省略号宽度与布局度量同源(基础绑定逐码点 advance)
        val ellipsisWidth = ceil(layout.baseFont.metrics.let { m ->
            var w = 0f
            var i = 0
            while (i < ellipsisSuffix.length) {
                val cp = ellipsisSuffix.codePointAt(i)
                i += Character.charCount(cp)
                w += m.advance(cp)
            }
            w
        })
        var end = drawText.length
        while (end > 0 &&
            layout.prefixWidth(line.start, line.start + end) + ellipsisWidth > layout.maxWidth
        ) {
            end--
        }
        drawText.substring(0, end)
    }

    override val width: Float get() = layout.width

    /** 行盒高(最终像素空间,P2-B3)= 该行绑定字体的最大自然行盒 */
    private fun lineBoxHeight(lineIndex: Int): Float =
        lineAt(lineIndex).lineBoxPx

    /** 行顶 y(布局空间)= 前序可见行盒高之和 */
    private fun lineTop(lineIndex: Int): Float {
        var acc = 0f
        for (j in 0 until lineIndex.coerceIn(0, visibleLineCount)) acc += lineBoxHeight(j)
        return acc
    }

    override val height: Float get() = lineTop(visibleLineCount)

    override val minIntrinsicWidth: Float get() = intrinsics.minIntrinsicWidth

    override val maxIntrinsicWidth: Float get() = intrinsics.maxIntrinsicWidth

    override val firstBaseline: Float
        get() = lineAt(0).baselinePx

    override val lastBaseline: Float
        get() = lineTop(visibleLineCount - 1) + lineAt(visibleLineCount - 1).baselinePx

    override val didExceedMaxLines: Boolean
        get() = maxLines != DefaultMaxLines && layout.lines.size > maxLines

    override val lineCount: Int get() = visibleLineCount

    override val placeholderRects: List<Rect?> get() = buildPlaceholderRects()

    /**
     * 平台适配点(InlineContent):占位符矩形(段落本地坐标,已乘 [scale])。
     * 顺序与占位符声明序一致;占位符是原子 → 恰好完整落在一行,被 maxLines 截掉的
     * 占位符为 null(foundation 侧 null 跳过绘制)。垂直对齐按 [PlaceholderVerticalAlign]
     * 映射到 MC 度量(行高 9、基线 0.8×行高;TextTop/TextCenter/TextBottom 与
     * Top/Center/Bottom 等价 —— MC 字体无独立 ascent/descent 模型,文档化简化)。
     */
    private fun buildPlaceholderRects(): List<Rect?> {
        val spans = intrinsics.placeholderSpans
        if (spans.isEmpty()) return emptyList()
        val rects = spans.map { span ->
            val lineIndex = layout.lines.indexOfFirst { it.start <= span.start && span.end <= it.end }
            if (lineIndex < 0 || lineIndex >= visibleLineCount) {
                null
            } else {
                val lineStart = layout.lines[lineIndex].start
                val x = layout.prefixWidth(lineStart, span.start)
                // 平台适配点(段级字号):行顶按前序行盒高累计,对齐基准用该行真实基线
                val box = lineBoxHeight(lineIndex)
                val baseline = lineAt(lineIndex).baselinePx
                val y = lineTop(lineIndex) + placeholderAlignY(span.height, span.verticalAlign, box, baseline)
                Rect(
                    left = x,
                    top = y,
                    right = x + span.width,
                    bottom = y + span.height,
                )
            }
        }
        return rects
    }

    private fun placeholderAlignY(
        height: Float,
        align: PlaceholderVerticalAlign,
        boxHeight: Float,
        baseline: Float,
    ): Float {
        return when {
            align == PlaceholderVerticalAlign.AboveBaseline -> baseline - height
            align == PlaceholderVerticalAlign.Top || align == PlaceholderVerticalAlign.TextTop -> 0f
            align == PlaceholderVerticalAlign.Bottom || align == PlaceholderVerticalAlign.TextBottom ->
                boxHeight - height
            align == PlaceholderVerticalAlign.Center || align == PlaceholderVerticalAlign.TextCenter ->
                (boxHeight - height) / 2f
            else -> baseline - height // 兜底:同官方默认 AboveBaseline 语义
        }
    }

    /**
     * 行的可见绘制子区间(平台适配点 InlineContent):排除占位符内部 —— 替代文本不绘制、
     * 空间已保留。返回绝对 char 区间列表([IntRange] 闭区间)。
     * [contentEnd] 为不含行尾 `\n` 的内容终点;[drawLimit] 为 Ellipsis 收缩后的绘制终点,
     * 若收缩点落在占位符内部则回退到占位符起点(占位符原子不截半)。
     */
    private fun visiblePieces(start: Int, contentEnd: Int, drawLimit: Int): List<IntRange> {
        var limit = drawLimit.coerceAtMost(contentEnd)
        // 收缩点落在占位符内部 → 回退到占位符起点(原子不截半)
        intrinsics.placeholderSpans
            .firstOrNull { it.start < limit && limit < it.end }
            ?.let { limit = it.start }
        if (limit <= start) return emptyList()
        val ordered = intrinsics.placeholderSpans.sortedBy { it.start }
        val pieces = ArrayList<IntRange>()
        var cur = start
        while (cur < limit) {
            val nextSpan = ordered.firstOrNull { it.start >= cur && it.start < limit }
            if (nextSpan == null) {
                pieces.add(cur until limit)
                break
            }
            if (nextSpan.start > cur) pieces.add(cur until nextSpan.start)
            cur = nextSpan.end
        }
        return pieces
    }

    private fun lineAt(index: Int): MinecraftTextLine {
        val i = index.coerceIn(0, layout.lines.size - 1)
        return layout.lines[i]
    }

    private fun lineForOffset(offset: Int): Int {
        val clamped = offset.coerceIn(0, intrinsics.text.length)
        // 平台适配点(T.11 修复):半开区间 [start, end) —— computeLines 的行尾含 `\n`(end = nl+1),
        // 行边界连续且不重叠;闭区间 `<=` 会让行尾/行首(如 `\n` 后的首字符)归属错行。
        // offset == text.length(末尾)时归最后一行。
        for (i in layout.lines.indices) {
            val line = layout.lines[i]
            if (clamped >= line.start && clamped < line.end) {
                return i
            }
            if (clamped == intrinsics.text.length && i == layout.lines.lastIndex) {
                return i
            }
        }
        return layout.lines.size - 1
    }

    /** 行的绘制起点(无截断 —— 原 displayPos 水平截断已移除,T.6 修复)。 */
    private fun lineDrawStart(lineIndex: Int, line: MinecraftTextLine): Int = line.start

    override fun getPathForRange(start: Int, end: Int): Path {
        // 平台适配点(T.11 修复):实现选区 Path —— 官方 SkiaParagraph 语义:
        // 取选区覆盖的每行矩形(RectWidthMode.TIGHT:从选中起始 x 到选中结束 x),
        // 全部 addRect 进同一个 Path,一次绘制(替代原空 Path() 占位)。
        require(start in 0..end && end <= intrinsics.text.length) {
            "start($start) or end($end) is out of range [0..${intrinsics.text.length}]," +
                " or start > end!"
        }
        if (start == end) return Path()

        val path = Path()
        val firstLine = lineForOffset(start)
        val lastLine = lineForOffset((end - 1).coerceAtLeast(0))
        for (lineIndex in firstLine..lastLine) {
            val line = lineAt(lineIndex)
            val lineStart = maxOf(start, line.start)
            // 行尾排除换行符(选区矩形不覆盖 `\n` 本身)
            val lineEndRaw = minOf(end, line.end)
            val lineEnd =
                if (lineEndRaw > lineStart && intrinsics.text[lineEndRaw - 1] == '\n') {
                    lineEndRaw - 1
                } else {
                    lineEndRaw
                }
            if (lineEnd <= lineStart) continue
            val left = layout.prefixWidth(line.start, lineStart)
            val right = layout.prefixWidth(line.start, lineEnd)
            val top = lineTop(lineIndex)
            val bottom = lineTop(lineIndex) + lineBoxHeight(lineIndex)
            // 平台适配点(T.26):坐标 API 统一 ×scale —— 布局在 1x 空间度量,绘制经矩阵
            // 放大;选区/光标/命中测试在放大空间工作,坐标必须与视觉一致
            path.addRect(Rect(left, top, right, bottom))
        }
        return path
    }

    override fun getCursorRect(offset: Int): Rect {
        val lineIndex = lineForOffset(offset)
        val line = lineAt(lineIndex)
        val start = lineDrawStart(lineIndex, line)
        // T.5:前缀精确宽度(EditBox.getScreenX 同源)
        val x = layout.prefixWidth(start, offset.coerceIn(start, line.end))
        val top = lineTop(lineIndex)
        return Rect(x, top, x, top + lineBoxHeight(lineIndex))
    }

    override fun getLineLeft(lineIndex: Int): Float = 0f

    override fun getLineRight(lineIndex: Int): Float = lineAt(lineIndex).width

    override fun getLineTop(lineIndex: Int): Float = lineTop(lineIndex)

    override fun getLineBaseline(lineIndex: Int): Float =
        lineTop(lineIndex) + lineAt(lineIndex).baselinePx

    override fun getLineBottom(lineIndex: Int): Float =
        lineTop(lineIndex) + lineBoxHeight(lineIndex)

    override fun getLineHeight(lineIndex: Int): Float = lineBoxHeight(lineIndex)

    override fun getLineWidth(lineIndex: Int): Float = lineAt(lineIndex).width

    override fun getLineStart(lineIndex: Int): Int = lineAt(lineIndex).start

    override fun getLineEnd(lineIndex: Int, visibleEnd: Boolean): Int {
        val end = lineAt(lineIndex).end
        // 平台适配点(T.11 修复):visibleEnd=true 时排除行尾换行符
        // (官方语义:不计算行尾换行/空白;jumpByLinesOffset 等用它定位行尾)。
        if (!visibleEnd) return end
        val line = lineAt(lineIndex)
        return if (end > line.start && intrinsics.text[end - 1] == '\n') end - 1 else end
    }

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

    override fun getLineForVerticalPosition(vertical: Float): Int {
        // 平台适配点(段级字号):行盒高可变,按前序行盒高累计定位
        val yLayout = vertical
        var acc = 0f
        for (i in layout.lines.indices) {
            val box = lineBoxHeight(i)
            if (yLayout < acc + box || i == layout.lines.lastIndex) {
                return i.coerceIn(0, maxOf(0, layout.lines.size - 1))
            }
            acc += box
        }
        return 0
    }

    override fun getOffsetForPosition(position: Offset): Int {
        // 平台适配点(T.26):position 为放大空间坐标(点击/光标),换算回 1x 布局空间定位
        val lineIndex = getLineForVerticalPosition(position.y)
        val line = lineAt(lineIndex)
        val start = lineDrawStart(lineIndex, line)
        // T.5/T.6:前缀宽度定位(EditBox.findClickedPositionInText 同源)
        // 平台适配点(T.11 修复):行尾排除 `\n`(exclusive end 含换行符),定位不落入换行符。
        val lineEndExclusive = if (line.end > start && intrinsics.text[line.end - 1] == '\n') {
            line.end - 1
        } else {
            line.end
        }
        //("累计宽度 ≤ x"语义与旧 plainSubstrByWidth 一致)
        val xLayout = position.x
        var rel = 0
        for (o in start until lineEndExclusive) {
            if (layout.prefixWidth(start, o + 1) <= xLayout) {
                rel = o + 1 - start
            } else {
                break
            }
        }
        return (start + rel).coerceIn(line.start, lineEndExclusive)
    }

    override fun getRangeForRect(
        rect: Rect,
        granularity: androidx.compose.ui.text.TextGranularity,
        inclusionStrategy: androidx.compose.ui.text.TextInclusionStrategy,
    ): TextRange {
        // 对照官方桌面语义(font-system 重构 T.RF-D):CMP 1.11 的
        // SkiaParagraph.getRangeForRect 同样未实现(TODO CMP-1255,返回
        // TextRange.Zero)。此前自研占位返回全串范围会让框选退化为「全选」,
        // 与官方契约(KDoc:「无命中文本时返回 TextRange.Zero」)不符 ——
        // 对齐官方零结果;MultiParagraph 层会跳过零结果段落,SelectionContainer
        // 行为与官方桌面一致。待上游实现后跟随移植。
        return TextRange.Zero
    }

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
        // 词界语义对照官方桌面(SkiaParagraph.skiko.kt getWordBoundary,font-system
        // 重构 T.RF-C):空白偏移 → 空域或前字符的词;其余交给 ICU BreakIterator
        //(getWordInstance,UAX #29)—— 替代旧 isLetterOrDigit 手写规则,
        // 双击选词在中日韩/复合词场景与官方一致。
        val iterator = java.text.BreakIterator.getWordInstance()
        iterator.setText(text)
        if (text[offset].isWhitespace()) {
            if (offset > 0 && !text[offset - 1].isWhitespace()) {
                return wordRangeAround(iterator, offset - 1)
            }
            return TextRange(offset, offset)
        }
        return wordRangeAround(iterator, offset)
    }

    /** 包含 [pos] 的词区间([pos-? , ?+)):`preceding`/`following` 括界,DONE 兜底。 */
    private fun wordRangeAround(iterator: java.text.BreakIterator, pos: Int): TextRange {
        val endB = iterator.following(pos)
        val startB = iterator.preceding(pos)
        val end = (if (endB == java.text.BreakIterator.DONE) iterator.last() else endB)
            .coerceIn(0, intrinsics.text.length)
        val start = (if (startB == java.text.BreakIterator.DONE) 0 else startB)
            .coerceIn(0, intrinsics.text.length)
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
        if (brush is ShaderBrush) {
            // T.TT:必须用「未缩放」段落盒创建 —— 渲染端逐字形采样用的是命令局部坐标
            // (未乘 scale);若用缩放后尺寸,t 会被压缩到 0..1/scale(实测:渐变恒为首色)
            val size = androidx.compose.ui.geometry.Size(
                layout.width,
                lineTop(visibleLineCount),
            )
            val shader = brush.createShader(size)
            val mc = canvas as? MinecraftCanvas
                ?: throw UnsupportedOperationException(
                    "MinecraftParagraph.paint only supports MinecraftCanvas, actual: ${canvas::class.simpleName}"
                )
            val effectiveAlpha = alpha
            val lineCount = visibleLineCount
            var rowTop = 0f
            for (i in 0 until lineCount) {
                    val line = layout.lines[i]
                    val start = lineDrawStart(i, line)
                    if (line.end > start) {
                        val contentEnd =
                            if (intrinsics.text[line.end - 1] == '\n') line.end - 1 else line.end
                        // 平台适配点:Ellipsis —— 最后一个可见行替换为省略版本
                        val appendEllipsis = ellipsizedLastLine != null && i == visibleLineCount - 1
                        val drawLimit =
                            if (appendEllipsis) start + ellipsizedLastLine.length else contentEnd
                        // 平台适配点(InlineContent):占位符内部不绘制(空间已保留)
                        for (piece in visiblePieces(start, contentEnd, drawLimit)) {
                            val pieceText = intrinsics.text.substring(piece.first, piece.last + 1)
                            val segs = intrinsics.segments
                            if (segs.isEmpty()) {
                                mc.recordTextDraw(
                                    text = pieceText,
                                    x = layout.prefixWidth(start, piece.first),
                                    y = rowTop,
                                    style = intrinsics.style,
                                    alpha = effectiveAlpha,
                                    shader = shader,
                                    font = layout.fontAt(piece.first),
                                )
                            } else {
                                // 平台适配点(段级字号):Brush 路径同样走分段(此前丢段样式/缩放)
                                recordSegmentedTextDraw(
                                    mc, pieceText, piece.first, rowTop, segs,
                                    intrinsics.style, effectiveAlpha,
                                    baseX = layout.prefixWidth(start, piece.first),
                                    line = line,
                                    shader = shader,
                                )
                            }
                        }
                        if (appendEllipsis) {
                            drawStyledRun(
                                mc, ellipsisSuffix,
                                layout.prefixWidth(start, start + ellipsizedLastLine.length),
                                rowTop + line.baselinePx - layout.baseFont.baselineFromTopPx,
                                layout.baseFont, intrinsics.style, effectiveAlpha, shader,
                            )
                        }
                    }
                    // 行顶按该行盒高累计(P2-B3)
                    rowTop += line.lineBoxPx
                }
        } else {
            val color = (brush as? SolidColor)?.value ?: Color.White
            paint(canvas, color, alpha)
        }
    }

    private fun paint(canvas: Canvas, color: Color, alpha: Float) {
        val mc = canvas as? MinecraftCanvas
            ?: throw UnsupportedOperationException(
                "MinecraftParagraph.paint only supports MinecraftCanvas, actual: ${canvas::class.simpleName}"
            )
        // T.1/T.2:记录完整 MC 样式快照;paint 传入的 color 覆盖样式色
        val style = intrinsics.style.withColor(color.copy(alpha = color.alpha * alpha))
        val effectiveAlpha = color.alpha * alpha
        // P2-B3(A4 尺寸空间唯一):布局坐标即最终像素,不再施加画布缩放;
        // 字号语义由每字符字体绑定(ResolvedFont.emPx)承载。
        val lineCount = visibleLineCount
        var rowTop = 0f
        for (i in 0 until lineCount) {
            val line = layout.lines[i]
            val start = lineDrawStart(i, line)
            if (line.end > start) {
                // T.6:整行绘制;行尾可能含 \n(exclusive end),绘制时去掉
                val contentEnd = if (intrinsics.text[line.end - 1] == '\n') line.end - 1 else line.end
                val appendEllipsis = ellipsizedLastLine != null && i == visibleLineCount - 1
                val drawLimit =
                    if (appendEllipsis) start + ellipsizedLastLine.length else contentEnd
                // InlineContent:占位符内部不绘制,按可见子区间分段绘制
                for (piece in visiblePieces(start, contentEnd, drawLimit)) {
                    val pieceText = intrinsics.text.substring(piece.first, piece.last + 1)
                    val pieceX = layout.prefixWidth(start, piece.first)
                    val segments = intrinsics.segments
                    if (segments.isEmpty()) {
                        mc.recordTextDraw(
                            text = pieceText,
                            x = pieceX,
                            y = rowTop,
                            style = style,
                            alpha = effectiveAlpha,
                            font = layout.fontAt(piece.first),
                        )
                    } else {
                        recordSegmentedTextDraw(
                            mc, pieceText, piece.first, rowTop, segments, style, effectiveAlpha,
                            baseX = pieceX,
                            line = line,
                        )
                    }
                }
                if (appendEllipsis) {
                    drawStyledRun(
                        mc, ellipsisSuffix,
                        layout.prefixWidth(start, start + ellipsizedLastLine.length),
                        rowTop + line.baselinePx - layout.baseFont.baselineFromTopPx,
                        layout.baseFont, style, effectiveAlpha, shader = null,
                    )
                }
            }
            rowTop += line.lineBoxPx
        }
    }

    /**
     * 按 [StyleSegment] 边界把行内文本切分绘制(P2-B3 绑定式):
     * 所有段共享行基线;段原点 = 行基线 − 段字体基线(行顶语义,渲染端内部加锚点)。
     */
    private fun recordSegmentedTextDraw(
        mc: MinecraftCanvas,
        drawText: String,
        rowStart: Int,
        rowTop: Float,
        segments: List<StyleSegment>,
        fallbackStyle: Style,
        alpha: Float,
        /** 行内绘制起点 x(最终像素);InlineContent piece 时为该 piece 的行内偏移 */
        baseX: Float = 0f,
        /** 所属行:共享基线对齐的行盒/基线来自该行绑定字体 */
        line: MinecraftTextLine,
        /** Brush 路径透传 */
        shader: Shader? = null,
    ) {
        val rowEnd = rowStart + drawText.length
        val lineBaselineAbs = rowTop + line.baselinePx
        var cursor = rowStart
        var segOffset = 0
        for (seg in segments) {
            val segStart = segOffset
            val segEnd = segOffset + seg.text.length
            segOffset = segEnd
            if (segEnd <= rowStart || segStart >= rowEnd) continue
            val clipStart = maxOf(segStart, rowStart)
            val clipEnd = minOf(segEnd, rowEnd)
            if (clipEnd > clipStart) {
                val xPx = baseX + layout.prefixWidth(rowStart, clipStart)
                val f = layout.fontAt(clipStart)
                drawStyledRun(
                    mc,
                    intrinsics.text.substring(clipStart, clipEnd),
                    xPx, lineBaselineAbs - f.baselineFromTopPx,
                    f, seg.style, alpha, shader,
                )
                cursor = clipEnd
            }
        }
        if (cursor < rowEnd) {
            val xPx = baseX + layout.prefixWidth(rowStart, cursor)
            val f = layout.fontAt(cursor)
            drawStyledRun(
                mc,
                intrinsics.text.substring(cursor, rowEnd),
                xPx, lineBaselineAbs - f.baselineFromTopPx,
                f, fallbackStyle, alpha, shader,
            )
        }
    }

    /** 记录一段指定字体绑定的文本(最终像素坐标;P2-B3 无矩阵缩放包裹) */
    private fun drawStyledRun(
        mc: MinecraftCanvas,
        text: String,
        x: Float,
        y: Float,
        font: ResolvedFont,
        style: Style,
        alpha: Float,
        shader: Shader?,
    ) {
        mc.recordTextDraw(text = text, x = x, y = y, style = style, alpha = alpha, shader = shader, font = font)
    }
}

internal fun ActualParagraph(
    text: String,
    style: Style,
    annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    maxLines: Int,
    ellipsis: Boolean,
    width: Float,
    density: Density,
    @Suppress("DEPRECATION") resourceLoader: Font.ResourceLoader,
    segments: List<StyleSegment> = emptyList(),
    scale: Float = 1f,
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
    segments = segments,
    scale = scale,
)

internal fun ActualParagraph(
    text: String,
    style: Style,
    annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    maxLines: Int,
    overflow: TextOverflow,
    constraints: Constraints,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    segments: List<StyleSegment> = emptyList(),
    scale: Float = 1f,
): Paragraph = MinecraftParagraph(
    MinecraftParagraphIntrinsics(
        text = text,
        style = style,
        annotations = annotations,
        placeholders = placeholders,
        density = density,
        fontFamilyResolver = fontFamilyResolver,
        segments = segments,
        scale = scale,
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
    style: Style,
    annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    segments: List<StyleSegment> = emptyList(),
    scale: Float = 1f,
): ParagraphIntrinsics = MinecraftParagraphIntrinsics(
    text = text,
    style = style,
    annotations = annotations,
    placeholders = placeholders,
    density = density,
    fontFamilyResolver = fontFamilyResolver,
    segments = segments,
    scale = scale,
)

/** 与官方 Paragraph.skiko.kt 一致的 DefaultMaxLines */
private val DefaultMaxLines = Int.MAX_VALUE
