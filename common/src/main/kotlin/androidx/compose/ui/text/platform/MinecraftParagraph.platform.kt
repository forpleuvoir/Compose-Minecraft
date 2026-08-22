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
import moe.forpleuvoir.compose_minecraft.platform.render.text.MetricsSource
import moe.forpleuvoir.compose_minecraft.platform.render.text.activeMetricsSource
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

/** 一行文本的布局结果 */
internal data class MinecraftTextLine(
    val start: Int,
    val end: Int,
    val width: Float,
    /**
     * 平台适配点(段级字号):行内最大字符渲染系数(max r),行盒高 = 9 × scaleFactor。
     * 全 1 时与旧行为一致。换行符不计入。
     */
    val scaleFactor: Float = 1f,
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
 * Minecraft 字形度量布局模型(T.TT 起度量来源可切换)。
 *
 * 布局与渲染统一使用同源字形度量:
 * - 行宽 = 每字符 advance 累积(原版 `font.splitter` / TrueType stb HMetrics);
 * - 行高 = [MetricsSource.lineHeight](原版 9 固定 / TrueType 字体真实行高);
 * - 度量来源在构造时快照([activeMetricsSource]):开关开启且字体就绪 → TTF
 *   度量,否则原版 —— flag 关闭时行为与历史版本逐字节一致。
 */
internal class MinecraftTextLayout(
    val text: String,
    val maxWidth: Float,
    /** 平台适配点(InlineContent):占位符排版原子(按声明序;布局时按 start 排序)。 */
    val placeholders: List<PlaceholderSpan> = emptyList(),
    /** 平台适配点(段级字号):每 char 相对渲染系数(长度 = text.length;空 = 全 1)。 */
    val charScales: FloatArray = FloatArray(0),
) {

    /** 字符渲染相对系数(段级字号;越界 = 1) */
    private fun ratioAt(i: Int): Float = charScales.getOrElse(i) { 1f }

    /**
     * 度量来源快照(T.TT 原生排版):构造时查询一次 —— 同一布局的宽度/行高/基线
     * 恒定;开关切换后新建的布局使用新度量(测试场景整树重组)。
     */
    internal val metrics: MetricsSource = activeMetricsSource()

    val lineHeight: Float
        get() = metrics.lineHeight

    /** 行顶到基线距离(T.TT):布局侧基线公式统一从这里取,替代 0.8×行盒启发式 */
    internal val baselineFromTop: Float
        get() = metrics.baselineFromTop

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
            // 段级字号:字符增量 × 该段相对系数(r = 段字号缩放 / 基础缩放);
            // T.TT:字偶距并入增量(与绘制端 pen 推进同源,间距与系统渲染一致)
            inc[i] = (metrics.charAdvance(cp) + metrics.codepointKern(prevCp, cp)) * ratioAt(i)
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

    /** 段落高度 */
    val height: Float
        get() = lines.size * lineHeight

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
            result.add(MinecraftTextLine(0, 0, 0f))
            return result
        }
        val wrap = maxWidth.isFinite() && maxWidth > 0
        var segStart = 0
        while (segStart < text.length) {
            val nl = text.indexOf('\n', segStart)
            // 平台适配点(T.11 修复):行尾含换行符 —— 官方 getLineEnd 语义(exclusive end,
            // 对含 `\n` 的行返回换行符之后的位置)。原实现 end = nl(不含 `\n`),
            // 导致行边界不连续、选区跨行时漏行/坐标错。
            val segEnd = if (nl == -1) text.length else nl + 1
            if (segStart >= segEnd) {
                result.add(MinecraftTextLine(segStart, segEnd, 0f))
            } else if (wrap) {
                var start = segStart
                while (start < segEnd) {
                    var end = start
                    var lastSpace = -1
                    // 前缀宽度用 MC 字形度量判定换行(行尾 `\n` 不计入换行判定与行宽)
                    val contentEnd = if (nl == -1) segEnd else nl
                    // 平台适配点(T.12 修复):单字符宽度超过 maxWidth 时,原内层 while
                    // 不推进(end == start),外层 start 也原地踏步 -> 无限添加空行 -> OOM。
                    // 防御:单字符强制占一行并推进。
                    if (start < contentEnd) {
                        // 平台适配点(InlineContent):占位符宽度超过行宽 → 独占一行(同款防御)
                        val startSpan = spanStartingAt(start)
                        if (startSpan != null && startSpan.width > maxWidth) {
                            result.add(MinecraftTextLine(start, startSpan.end, startSpan.width))
                            start = startSpan.end
                            continue
                        }
                        val chWidth = substringWidth(start, start + 1)
                        if (chWidth > maxWidth) {
                            result.add(
                                MinecraftTextLine(
                                    start, start + 1,
                                    chWidth,
                                )
                            )
                            start++
                            continue
                        }
                    }
                    // 性能(T.34):substringWidth 为 O(1) 数组查表,替代原 O(n) font.width(substring)
                    while (end < contentEnd) {
                        // 平台适配点(InlineContent):占位符是原子不可断单元 —— 整段一起判定换行
                        val span = spanStartingAt(end)
                        if (span != null) {
                            val atomEnd = minOf(span.end, contentEnd)
                            if (atomEnd != span.end) {
                                // 防御:占位符区间跨行段(官方不会出现)—— 消费到行段尾,避免死循环
                                end = atomEnd
                                continue
                            }
                            if (substringWidth(start, atomEnd) <= maxWidth) {
                                end = atomEnd
                                continue
                            }
                            break // 放不下:本行在此结束,占位符整体去下一行
                        }
                        if (substringWidth(start, end + 1) > maxWidth) break
                        if (text[end] == ' ') lastSpace = end
                        end++
                        // 防御:推进后落入占位符内部(异常截断等罕见情形),跳到段尾
                        spanCoveringInterior(end)?.let { end = minOf(it.end, contentEnd) }
                    }
                    if (end == contentEnd) {
                        result.add(
                            MinecraftTextLine(start, segEnd, substringWidth(start, contentEnd))
                        )
                        start = segEnd
                    } else {
                        val lineEnd = if (lastSpace > start) lastSpace else end
                        result.add(
                            MinecraftTextLine(start, lineEnd, substringWidth(start, lineEnd))
                        )
                        start = if (lastSpace > start) lastSpace + 1 else end
                    }
                }
            } else {
                result.add(
                    MinecraftTextLine(segStart, segEnd, substringWidth(segStart, if (nl == -1) segEnd else nl))
                )
            }
            if (nl == -1) break
            segStart = nl + 1
            if (segStart == text.length) {
                result.add(MinecraftTextLine(segStart, segStart, 0f))
                break
            }
        }
        // 平台适配点(段级字号):行系数 = 行内字符最大 r(换行符不计),决定该行盒高
        return result.map { line ->
            val contentEnd = if (line.end > line.start && text[line.end - 1] == '\n') line.end - 1 else line.end
            var factor = 1f
            for (j in line.start until contentEnd) factor = maxOf(factor, ratioAt(j))
            line.copy(scaleFactor = factor)
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
            width = with(density) { range.item.width.toPx() } / scale,
            height = with(density) { range.item.height.toPx() } / scale,
            verticalAlign = range.item.placeholderVerticalAlign,
        )
    }

    internal val charScales: FloatArray = run {
        // 无分段(String 路径等)= 空数组,ratioAt 兜底为 1;
        // 有分段时默认 1f(未覆盖字符继承基础字号)—— 千万不能是 0 默认,
        // 否则全部字符增量被乘 0、文本宽度归零(实测 TAG 子段落 w=0)
        if (segments.isEmpty()) return@run FloatArray(0)
        val arr = FloatArray(text.length) { 1f }
        var offset = 0
        for (seg in segments) {
            // 段字号缩放公式与 foundation toTextScale 一致(sp × density × fontScale / MC 基准行高 9px),
            // 再除以基础缩放得相对系数
            val r = seg.fontSizeSp
                ?.let { sp -> (sp * density.density * density.fontScale / 9f) / scale }
                ?: 1f
            val end = minOf(offset + seg.text.length, arr.size)
            for (j in offset until end) arr[j] = r
            offset += seg.text.length
        }
        arr
    }

    private val layout = MinecraftTextLayout(text, Float.POSITIVE_INFINITY, placeholderSpans, charScales)

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

    /** 平台适配点(T.10):文本缩放;1f = 原样。 */
    private val scale: Float get() = intrinsics.scale

    private val layout: MinecraftTextLayout = run {
        // 平台适配点(T.10):缩放后可用宽度按 1/scale 换算(布局在缩放前空间度量,绘制时矩阵放大)
        val maxWidth =
            if (constraints.hasBoundedWidth) {
                if (scale > 0f) constraints.maxWidth.toFloat() / scale
                else Float.POSITIVE_INFINITY
            } else Float.POSITIVE_INFINITY
        MinecraftTextLayout(intrinsics.text, maxWidth, intrinsics.placeholderSpans, intrinsics.charScales)
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
        // 平台适配点(T.TT):省略号宽度与布局度量同源(原版 splitter / TrueType HMetrics)
        val ellipsisWidth = ceil(layout.metrics.let { m ->
            var w = 0f
            var i = 0
            while (i < ellipsisSuffix.length) {
                val cp = ellipsisSuffix.codePointAt(i)
                i += Character.charCount(cp)
                w += m.charAdvance(cp)
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

    override val width: Float get() = layout.width * scale

    /** 平台适配点(段级字号):行盒高(布局空间)= 行高 × 该行最大段系数 */
    private fun lineBoxHeight(lineIndex: Int): Float =
        layout.lineHeight * lineAt(lineIndex).scaleFactor

    /** 行顶 y(布局空间)= 前序可见行盒高之和 */
    private fun lineTop(lineIndex: Int): Float {
        var acc = 0f
        for (j in 0 until lineIndex.coerceIn(0, visibleLineCount)) acc += lineBoxHeight(j)
        return acc
    }

    override val height: Float get() = lineTop(visibleLineCount) * scale

    override val minIntrinsicWidth: Float get() = intrinsics.minIntrinsicWidth

    override val maxIntrinsicWidth: Float get() = intrinsics.maxIntrinsicWidth

    override val firstBaseline: Float
        get() = layout.baselineFromTop * lineAt(0).scaleFactor * scale

    override val lastBaseline: Float
        get() = (
            lineTop(visibleLineCount - 1) +
                layout.baselineFromTop * lineAt(visibleLineCount - 1).scaleFactor
            ) * scale

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
                val baseline = layout.baselineFromTop * lineAt(lineIndex).scaleFactor
                val y = lineTop(lineIndex) + placeholderAlignY(span.height, span.verticalAlign, box, baseline)
                Rect(
                    left = x * scale,
                    top = y * scale,
                    right = (x + span.width) * scale,
                    bottom = (y + span.height) * scale,
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
            val top = lineIndex * layout.lineHeight
            val bottom = (lineIndex + 1) * layout.lineHeight
            // 平台适配点(T.26):坐标 API 统一 ×scale —— 布局在 1x 空间度量,绘制经矩阵
            // 放大;选区/光标/命中测试在放大空间工作,坐标必须与视觉一致
            path.addRect(Rect(left * scale, top * scale, right * scale, bottom * scale))
        }
        return path
    }

    override fun getCursorRect(offset: Int): Rect {
        val lineIndex = lineForOffset(offset)
        val line = lineAt(lineIndex)
        val start = lineDrawStart(lineIndex, line)
        // T.5:前缀精确宽度(EditBox.getScreenX 同源)
        val x = layout.prefixWidth(start, offset.coerceIn(start, line.end))
        // 平台适配点(段级字号):行顶按前序行盒高累计
        val top = lineTop(lineIndex)
        // 平台适配点(T.26):坐标 ×scale(布局 1x 空间,视觉放大空间)
        return Rect(x * scale, top * scale, x * scale, (top + lineBoxHeight(lineIndex)) * scale)
    }

    override fun getLineLeft(lineIndex: Int): Float = 0f

    override fun getLineRight(lineIndex: Int): Float = lineAt(lineIndex).width * scale

    override fun getLineTop(lineIndex: Int): Float = lineTop(lineIndex) * scale

    override fun getLineBaseline(lineIndex: Int): Float =
        (lineTop(lineIndex) + lineBoxHeight(lineIndex) * 0.8f) * scale

    override fun getLineBottom(lineIndex: Int): Float =
        (lineTop(lineIndex) + lineBoxHeight(lineIndex)) * scale

    override fun getLineHeight(lineIndex: Int): Float = lineBoxHeight(lineIndex) * scale

    override fun getLineWidth(lineIndex: Int): Float = lineAt(lineIndex).width * scale

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
        return layout.prefixWidth(start, offset.coerceIn(start, line.end)) * scale
    }

    override fun getParagraphDirection(offset: Int): ResolvedTextDirection = intrinsics.textDirection

    override fun getBidiRunDirection(offset: Int): ResolvedTextDirection = intrinsics.textDirection

    override fun getLineForVerticalPosition(vertical: Float): Int {
        // 平台适配点(T.26):y 为放大空间坐标,除以 scale;
        // 平台适配点(段级字号):行盒高可变,按前序行盒高累计定位
        val yLayout = vertical / scale
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
        // 平台适配点(段级字号):字符增量含相对系数,改用缩放后前缀宽度行走
        //("累计宽度 ≤ x"语义与旧 plainSubstrByWidth 一致;系数全 1 时行为不变)
        val xLayout = position.x / scale
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
            val scaled = scale != 1f
            if (scaled) {
                mc.save()
                mc.scale(scale, scale)
            }
            val lineCount = visibleLineCount
            var rowTop = 0f
            try {
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
                                )
                            } else {
                                // 平台适配点(段级字号):Brush 路径同样走分段(此前丢段样式/缩放)
                                recordSegmentedTextDraw(
                                    mc, pieceText, piece.first, rowTop, segs,
                                    intrinsics.style, effectiveAlpha,
                                    baseX = layout.prefixWidth(start, piece.first),
                                    charScales = intrinsics.charScales,
                                    fallbackRatio = line.scaleFactor,
                                    shader = shader,
                                )
                            }
                        }
                        if (appendEllipsis) {
                            drawStyledRun(
                                mc, ellipsisSuffix,
                                layout.prefixWidth(start, start + ellipsizedLastLine.length),
                                rowTop,
                                line.scaleFactor, intrinsics.style, effectiveAlpha, shader,
                            )
                        }
                    }
                    // 平台适配点(段级字号):行顶按该行盒高累计
                    rowTop += line.scaleFactor * layout.lineHeight
                }
            } finally {
                if (scaled) mc.restore()
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
        // (shadow/textDecoration 等 Compose 绘制参数已被 MC Style 能力取代,第一版忽略)
        val style = intrinsics.style.withColor(color.copy(alpha = color.alpha * alpha))
        // 平台适配点(T.28):文本透明度 —— color.alpha(含 TextStyle.alpha 合成)与
        // 绘制 alpha 相乘,经 recordTextDraw 的 alpha 参数传递(渲染端 text() 消费
        // command.alpha;MC TextColor 无 alpha 通道,不走样式色)
        val effectiveAlpha = color.alpha * alpha
        // 平台适配点(T.10):文本缩放 —— 对画布施加缩放,recordTextDraw 记录缩放矩阵,
        // 渲染端 pose 变换字形顶点(MC GUI 渲染为 GPU 矩阵变换)。
        // 平台适配点(T.33 修复):缩放必须用 save/restore 包裹 —— MinecraftCanvas.scale
        // 是原地乘当前矩阵,若不恢复会污染画布矩阵,导致本文本之后的所有兄弟节点
        // (背景/边框/后续文本)在放大后的错位矩阵上绘制(实测 autoSize 大 scale 文本
        // 之后的内容全部错位/画到屏幕外)。节点级 willClip 的 save/restore 只恢复
        // 节点进入时的状态,无法兜底 scale 污染(willClip=false 时节点不 save)。
        val scaled = scale != 1f
        if (scaled) {
            mc.save()
            mc.scale(scale, scale)
        }
        val lineCount = visibleLineCount
        var rowTop = 0f
        try {
            for (i in 0 until lineCount) {
                val line = layout.lines[i]
                val start = lineDrawStart(i, line)
                if (line.end > start) {
                    // 平台适配点(T.6 修复):整行绘制 —— 原 displayPos/visibleWidth 水平截断已移除,
                    // 多行文本每行完整渲染(水平滚动交还原版 ScrollState 模型)。
                    // 行尾可能含 `\n`(computeLines 的 exclusive end 含换行符),绘制时去掉。
                    val contentEnd = if (intrinsics.text[line.end - 1] == '\n') line.end - 1 else line.end
                    // 平台适配点:Ellipsis —— 最后一个可见行替换为省略版本
                    val appendEllipsis = ellipsizedLastLine != null && i == visibleLineCount - 1
                    val drawLimit =
                        if (appendEllipsis) start + ellipsizedLastLine.length else contentEnd
                    // 平台适配点(InlineContent):占位符内部不绘制(空间已保留),按可见子区间分段绘制
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
                            )
                        } else {
                            recordSegmentedTextDraw(
                                mc, pieceText, piece.first, rowTop, segments, style, effectiveAlpha,
                                baseX = pieceX,
                                charScales = intrinsics.charScales,
                                fallbackRatio = line.scaleFactor,
                            )
                        }
                    }
                    if (appendEllipsis) {
                        drawStyledRun(
                            mc, ellipsisSuffix,
                            layout.prefixWidth(start, start + ellipsizedLastLine.length),
                            rowTop,
                            line.scaleFactor, style, effectiveAlpha, shader = null,
                        )
                    }
                }
                rowTop += line.scaleFactor * layout.lineHeight
            }
        } finally {
            if (scaled) {
                mc.restore()
            }
        }
    }

    /**
     * 平台适配点(T.3):按 [StyleSegment] 边界把行内文本 [drawText](绝对起点 [rowStart])切分,
     * 每段用段样式(缺失属性已由 defaultStyle 合并)绘制;段外文本用 [fallbackStyle]。
     * 段样式含合并后的颜色,不再被调用方 color 覆盖。
     */
    private fun recordSegmentedTextDraw(
        mc: MinecraftCanvas,
        drawText: String,
        rowStart: Int,
        rowTop: Float,
        segments: List<StyleSegment>,
        fallbackStyle: Style,
        alpha: Float,
        /**
         * 行内绘制起点 x(1x 布局单位)。整行绘制时为 0;InlineContent 分段(piece)绘制时
         * = 该 piece 的行内偏移 —— 段内各子段的 x 在 [baseX] 上累积,否则所有 piece
         * 都会叠在行首(实测 ⑬:piece 文本互相重叠)。
         */
        baseX: Float = 0f,
        /** 平台适配点(段级字号):每 char 相对渲染系数(与 [segments] 覆盖同一文本)。 */
        charScales: FloatArray = FloatArray(0),
        /** 兜底段(无 span 覆盖的尾部)的渲染系数 */
        fallbackRatio: Float = 1f,
        /** Brush 绘制路径透传(分段路径此前不支持 shader,Brush+富文本会丢渐变语义) */
        shader: Shader? = null,
    ) {
        val rowEnd = rowStart + drawText.length
        // 平台适配点(段级字号):基线对齐 —— 行基线(相对行顶)= 真实基线 × 行最大系数;
        // 段绘制原点 y = 行基线 − 真实基线 × 段系数,使所有段共享同一基线。
        // (T.TT:基线取自度量来源 [MetricsSource.baselineFromTop],替代 0.8×行盒启发式;
        // 此前直接从行顶起画是"顶部对齐",实测 ⑭:小字吊在行顶、大字向下延伸。)
        val baseline = layout.baselineFromTop
        val lineBaselineDelta = baseline * fallbackRatio
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
                // 平台适配点(T.29 修复):行内段 x 偏移 = 该行 [rowStart, clipStart) 的
                // MC 字形宽度(**1x 单位**,layout.prefixWidth 不乘 scale)。渲染端
                // GuiTextRenderState 的 x/y 是 prepareText 文本空间坐标,字形顶点
                // 再乘 pose(含字号 scale)缩放 —— 传 ×scale 值会被二次缩放,
                // 同行多段间隔翻倍、尾段被推出屏幕。
                val xPx = baseX + layout.prefixWidth(rowStart, clipStart)
                val ratio = charScales.getOrElse(clipStart) { 1f }
                drawStyledRun(
                    mc,
                    intrinsics.text.substring(clipStart, clipEnd),
                    xPx, rowTop + lineBaselineDelta - baseline * ratio,
                    ratio, seg.style, alpha, shader,
                )
                cursor = clipEnd
            }
        }
        // 段未覆盖到的尾部(理论上不应发生,防御性兜底)
        if (cursor < rowEnd) {
            val xPx = baseX + layout.prefixWidth(rowStart, cursor)
            drawStyledRun(
                mc,
                intrinsics.text.substring(cursor, rowEnd),
                xPx, rowTop + lineBaselineDelta - baseline * fallbackRatio,
                fallbackRatio, fallbackStyle, alpha, shader,
            )
        }
    }

    /**
     * 记录一段指定渲染系数的文本:ratio ≈ 1 直接记录;否则 translate 到运行原点后
     * 局部 scale —— 字形顶点随矩阵放大。传入的 [y] 应为"该段基线对齐后的绘制原点"
     * (见 [recordSegmentedTextDraw] 的基线对齐注释)。
     */
    private fun drawStyledRun(
        mc: MinecraftCanvas,
        text: String,
        x: Float,
        y: Float,
        ratio: Float,
        style: Style,
        alpha: Float,
        shader: Shader?,
    ) {
        if (ratio == 1f) {
            mc.recordTextDraw(text = text, x = x, y = y, style = style, alpha = alpha, shader = shader)
        } else {
            mc.save()
            mc.translate(x, y)
            mc.scale(ratio, ratio)
            mc.recordTextDraw(text = text, x = 0f, y = 0f, style = style, alpha = alpha, shader = shader)
            mc.restore()
        }
    }
}

private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

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
