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

import moe.forpleuvoir.compose_minecraft.mc
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import kotlin.math.roundToInt
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style
import net.minecraft.client.gui.Font as MinecraftFont

// ─────────────────────────────────────────────────────────────────────────────
// Minecraft 平台文本后端(文本系统 MC 化,T.2 + T.5 + T.6)
//
// 平台适配点(与官方 Paragraph.skiko.kt 的差异):
// - 样式类型为 MC [Style](能力字段 1:1),TextStyle 已完全移除;
// - 度量统一用 MC [MinecraftFont]:行高 = Font.lineHeight(9),字号忽略;
// - 光标/命中/词界位置用**前缀精确宽度** Font.width(前缀)(T.5,同 EditBox.getScreenX);
// - 命中测试用 Font.plainSubstrByWidth(同 EditBox.findClickedPositionInText);
// - 水平滚动恢复原版 ScrollState 模型(displayPos 截断已移除,T.6 修复);
// - 富文本(AnnotatedString 多 SpanStyle)第一版不做,只支持统一 [Style]。
// ─────────────────────────────────────────────────────────────────────────────

/** 一行文本的布局结果 */
internal class MinecraftTextLine(val start: Int, val end: Int, val width: Float)

/**
 * 平台适配点(T.3):MC [Component] 展平后的样式段 —— [text] 使用 [style] 绘制。
 * 由 `BasicText(component)` 经 `component.flatten()` 展平得到,
 * 每段 style 已是"Component 自身属性优先、缺失用 defaultStyle 补"的合并结果(MC applyTo 语义)。
 */
data class StyleSegment(
    val style: Style,
    val text: String,
)

/**
 * Minecraft Font 度量布局模型。
 *
 * 布局与渲染统一使用 Minecraft 字形度量:
 * - 行宽 = [MinecraftFont.width](该行文本)(GUI 像素,场景密度 1f 下即场景像素);
 * - 行高 = [MinecraftFont.lineHeight](9);
 * - 字号第一版忽略(统一 MC 原生 9px GUI 文本)。
 */
internal class MinecraftTextLayout(
    val text: String,
    val maxWidth: Float,
) {

    private val font: MinecraftFont
        get() = mc.font

    val lineHeight: Float
        get() = font.lineHeight.toFloat()

    /**
     * 累积字符浮点宽度(cumFloatWidths[i] = text[0,i) 的 StringSplitter.stringWidth 浮点值)。
     *
     * MC [Font.width] = `Mth.ceil(splitter.stringWidth(s))`,对每个子串单独 ceil。
     * 由于 stringWidth 是按码点累加的(StringSplitter.stringWidth 遍历每个 codepoint
     * 求和 widthProvider.getWidth,无字距/无上下文依赖),`stringWidth(s[a..b))` 精确等于
     * `cumFloatWidths[b] - cumFloatWidths[a]`。因此 `font.width(s[a..b))` 精确等于
     * `ceil(cumFloatWidths[b] - cumFloatWidths[a])`。
     *
     * 性能(T.34):替代每次 `font.width(text.substring(start, end+1))` 的 O(n) 子串创建
     * + O(n) 宽度计算,降为 O(1) 数组查表。computeLines 从 O(n²) 降为 O(n)。
     */
    private val cumFloatWidths: FloatArray = run {
        val arr = FloatArray(text.length + 1)
        val splitter = font.splitter
        var cum = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val cc = Character.charCount(cp)
            cum += splitter.stringWidth(String(Character.toChars(cp)))
            // 填充本码点覆盖的所有 char 位置(代理对占 2 char,中间位置与终点同值)
            for (j in i + 1..i + cc) arr[j] = cum
            i += cc
        }
        arr
    }

    /**
     * text[from, to) 的 MC 字形宽度(int 语义,与 [MinecraftFont.width] 一致 ——
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
            maxW = maxOf(maxW, substringWidth(start, cur))
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
                    while (end < contentEnd &&
                        substringWidth(start, end + 1) <= maxWidth
                    ) {
                        if (text[end] == ' ') lastSpace = end
                        end++
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
        return result
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
    private val placeholders: List<AnnotatedString.Range<Placeholder>>,
    private val density: Density,
    private val fontFamilyResolver: FontFamily.Resolver,
    /** 平台适配点(T.3):MC Component 展平后的多段样式;空 = 单样式(旧行为)。 */
    internal val segments: List<StyleSegment> = emptyList(),
    /** 平台适配点(T.10):文本缩放(1f = 原样;布局尺寸与字形矩阵同步缩放)。 */
    internal val scale: Float = 1f,
) : ParagraphIntrinsics {
    val textDirection: ResolvedTextDirection = ResolvedTextDirection.Ltr

    private val layout = MinecraftTextLayout(text, Float.POSITIVE_INFINITY)

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
        MinecraftTextLayout(intrinsics.text, maxWidth)
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
        val ellipsisWidth = font.width(ellipsisSuffix).toFloat()
        var end = drawText.length
        while (end > 0 &&
            layout.prefixWidth(line.start, line.start + end) + ellipsisWidth > layout.maxWidth
        ) {
            end--
        }
        drawText.substring(0, end)
    }

    private val font: MinecraftFont
        get() = mc.font

    override val width: Float get() = layout.width * scale

    override val height: Float get() = visibleLineCount * layout.lineHeight * scale

    override val minIntrinsicWidth: Float get() = intrinsics.minIntrinsicWidth

    override val maxIntrinsicWidth: Float get() = intrinsics.maxIntrinsicWidth

    override val firstBaseline: Float get() = layout.lineHeight * 0.8f * scale

    override val lastBaseline: Float
        get() = ((visibleLineCount - 1) * layout.lineHeight + layout.lineHeight * 0.8f) * scale

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
        val top = lineIndex * layout.lineHeight
        // 平台适配点(T.26):坐标 ×scale(布局 1x 空间,视觉放大空间)
        return Rect(x * scale, top * scale, x * scale, (top + layout.lineHeight) * scale)
    }

    override fun getLineLeft(lineIndex: Int): Float = 0f

    override fun getLineRight(lineIndex: Int): Float = lineAt(lineIndex).width * scale

    override fun getLineTop(lineIndex: Int): Float = lineIndex * layout.lineHeight * scale

    override fun getLineBaseline(lineIndex: Int): Float =
        (lineIndex * layout.lineHeight + layout.lineHeight * 0.8f) * scale

    override fun getLineBottom(lineIndex: Int): Float =
        (lineIndex + 1) * layout.lineHeight * scale

    override fun getLineHeight(lineIndex: Int): Float = layout.lineHeight * scale

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

    override fun getLineForVerticalPosition(vertical: Float): Int =
        // 平台适配点(T.26):输入 vertical 为放大空间坐标,除以 scale 后按 1x 布局行高换算
        ((vertical / scale) / layout.lineHeight)
            .toInt()
            .coerceIn(0, maxOf(0, layout.lines.size - 1))

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
        val rel = font
            .plainSubstrByWidth(
                intrinsics.text.substring(start, lineEndExclusive),
                (position.x / scale).roundToInt().coerceAtLeast(0),
            ).length
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
            val size = androidx.compose.ui.geometry.Size(width, height)
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
            try {
                for (i in 0 until lineCount) {
                    val line = layout.lines[i]
                    val start = lineDrawStart(i, line)
                    if (line.end > start) {
                        var drawText = intrinsics.text.substring(start, line.end)
                        if (drawText.endsWith('\n')) drawText = drawText.dropLast(1)
                        // 平台适配点:Ellipsis —— 最后一个可见行替换为省略版本
                        val appendEllipsis = ellipsizedLastLine != null && i == visibleLineCount - 1
                        if (appendEllipsis) drawText = ellipsizedLastLine
                        if (drawText.isNotEmpty()) {
                            mc.recordTextDraw(
                                text = drawText,
                                x = 0f,
                                y = i * layout.lineHeight,
                                style = intrinsics.style,
                                alpha = effectiveAlpha,
                                shader = shader,
                            )
                        }
                        if (appendEllipsis) {
                            mc.recordTextDraw(
                                text = ellipsisSuffix,
                                x = layout.prefixWidth(start, start + drawText.length),
                                y = i * layout.lineHeight,
                                style = intrinsics.style,
                                alpha = effectiveAlpha,
                                shader = shader,
                            )
                        }
                    }
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
        try {
            for (i in 0 until lineCount) {
                val line = layout.lines[i]
                val start = lineDrawStart(i, line)
                if (line.end > start) {
                    // 平台适配点(T.6 修复):整行绘制 —— 原 displayPos/visibleWidth 水平截断已移除,
                    // 多行文本每行完整渲染(水平滚动交还原版 ScrollState 模型)。
                    // 行尾可能含 `\n`(computeLines 的 exclusive end 含换行符),绘制时去掉。
                    var drawText = intrinsics.text.substring(start, line.end)
                    if (drawText.endsWith('\n')) drawText = drawText.dropLast(1)
                    // 平台适配点:Ellipsis —— 最后一个可见行替换为省略版本
                    val appendEllipsis = ellipsizedLastLine != null && i == visibleLineCount - 1
                    if (appendEllipsis) drawText = ellipsizedLastLine
                    if (drawText.isNotEmpty()) {
                        // 平台适配点(T.3):多段样式 —— 行内文本按段边界切分,每段用自己的样式;
                        // 无段(空列表)时退回单样式(旧行为)。
                        val segments = intrinsics.segments
                        if (segments.isEmpty()) {
                            mc.recordTextDraw(
                                text = drawText,
                                x = 0f,
                                y = i * layout.lineHeight,
                                style = style,
                                alpha = effectiveAlpha,
                            )
                        } else {
                            recordSegmentedTextDraw(mc, drawText, start, i, segments, style, effectiveAlpha)
                        }
                    }
                    if (appendEllipsis) {
                        mc.recordTextDraw(
                            text = ellipsisSuffix,
                            x = layout.prefixWidth(start, start + drawText.length),
                            y = i * layout.lineHeight,
                            style = style,
                            alpha = effectiveAlpha,
                        )
                    }
                }
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
        row: Int,
        segments: List<StyleSegment>,
        fallbackStyle: Style,
        alpha: Float,
    ) {
        val rowEnd = rowStart + drawText.length
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
                val xPx = layout.prefixWidth(rowStart, clipStart)
                mc.recordTextDraw(
                    text = intrinsics.text.substring(clipStart, clipEnd),
                    x = xPx,
                    y = row * layout.lineHeight,
                    style = seg.style,
                    alpha = alpha,
                )
                cursor = clipEnd
            }
        }
        // 段未覆盖到的尾部(理论上不应发生,防御性兜底)
        if (cursor < rowEnd) {
            val xPx = layout.prefixWidth(rowStart, cursor)
            mc.recordTextDraw(
                text = intrinsics.text.substring(cursor, rowEnd),
                x = xPx,
                y = row * layout.lineHeight,
                style = fallbackStyle,
                alpha = alpha,
            )
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
