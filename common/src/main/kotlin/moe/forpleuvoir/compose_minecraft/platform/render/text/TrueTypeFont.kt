package moe.forpleuvoir.compose_minecraft.platform.render.text

import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTruetype.stbtt_FindGlyphIndex
import org.lwjgl.stb.STBTruetype.stbtt_GetCodepointKernAdvance
import org.lwjgl.stb.STBTruetype.stbtt_GetCodepointHMetrics
import org.lwjgl.stb.STBTruetype.stbtt_GetFontVMetrics
import org.lwjgl.stb.STBTruetype.stbtt_GetFontOffsetForIndex
import org.lwjgl.stb.STBTruetype.stbtt_ScaleForPixelHeight
import kotlin.math.ceil
import org.lwjgl.stb.STBTruetype.stbtt_FreeBitmap
import org.lwjgl.stb.STBTruetype.stbtt_GetCodepointBitmap
import org.lwjgl.stb.STBTruetype.stbtt_InitFont
import org.lwjgl.system.MemoryStack
import java.nio.ByteBuffer

/**
 * stb_truetype 光栅化结果:8bit 灰度位图(row-major,每像素一字节)。
 *
 * - [bearingTop]:基线到位图顶(px,**屏幕 y-down 约定:负值 = 位图顶在基线上方**,
 *   stb yoff 原始符号)。绘制:quadTop = baselineY + bearingTop。
 */
class RasterizedGlyph internal constructor(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    /** 笔位到位图左缘(px,右为正,stb xoff,已含 lsb) */
    val bearingX: Int,
    /** 基线到位图顶(px,屏幕 y-down 约定:负值 = 在基线上方,stb yoff) */
    val bearingTop: Int,
)

/**
 * stb_truetype 字体封装(T.TT,D2:零新依赖,MC 运行时自带 lwjgl-stb)。
 *
 * - 解析 TTF/TTC([load] 自动遍历 TTC 字体索引);
 * - 光栅化:[rasterize] 任意 px 字号的灰度位图(**矢量红利:非整数
 *   字号直接支持**,段级字号/姿态缩放可按实际屏幕像素光栅化);
 *
 * 仅参考 Modern UI 的公开架构思路(按需光栅化 + 图集),实现全部基于
 * LWJGL STBTruetype 绑定与自研代码(授权边界见设计文档)。
 *
 * [data] 持强引用防止直接缓冲被 GC 后 stb 悬垂指针。
 */
class TrueTypeFont private constructor(
    private val info: STBTTFontinfo,
    /** 强引用锚点:防止直接缓冲被 GC 后 stb 内部持有的数据指针悬垂(代码不直接读它) */
    @Suppress("unused") private val data: ByteBuffer,
    /** 基准字号(px):1x 布局语义的 advance/行高都按此字号计算 */
    val baseSizePx: Float,
    /** 本 face 在字体数据中的 sfnt 偏移(TTC 多 face;OS/2 表解析定位用) */
    private val faceOffset: Int,
) : AutoCloseable {

    /** 缩放系数(font units → px),按所选垂直度量行盒归一:baseSizePx == 1x 行高 */
    private val baseScale: Float

    /** 基线到行顶距离(px,正值) */
    val ascentPx: Float

    /** 基线到行底距离(px,正值;stb descent 为负,此处取反) */
    val descentPx: Float

    /** 行盒高(px,= ascent + descent 向上取整,不含 lineGap) */
    val lineHeightPx: Float

    /** unitsPerEm(head 表):em 归一缩放与光栅化共用 */
    private val unitsPerEm: Int

    init {
        require(baseSizePx > 0f) { "baseSizePx must be positive, was $baseSizePx" }
        MemoryStack.stackPush().use { stack ->
            val ascent = stack.mallocInt(1)
            val descent = stack.mallocInt(1)
            val lineGap = stack.mallocInt(1)
            stbtt_GetFontVMetrics(info, ascent, descent, lineGap)
            // 垂直度量:OS/2 typo + CJK 基线下限(紧凑且容得下汉字);缺失回退 hhea
            val metrics = parseVerticalMetrics(data, faceOffset)
            val ascentUnits = metrics?.ascentUnits ?: ascent.get(0)
            val descentUnits = metrics?.descentUnits ?: descent.get(0)
            // upm(head 表)先行解析
            val upm = metrics?.unitsPerEm ?: run {
                MemoryStack.stackPush().use { st2 ->
                    // 兜底:直接从 head 表读 upm
                    val b = data.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN)
                    var v = -1
                    try {
                        b.position(faceOffset + 4)
                        val nT = b.short.toInt() and 0xFFFF
                        for (i in 0 until nT) {
                            val e = faceOffset + 12 + i * 16
                            b.position(e)
                            val tag = ByteArray(4); b.get(tag)
                            if (String(tag, Charsets.US_ASCII) == "head") { b.int; v = b.int; break }
                        }
                        if (v >= 0) { b.position(v + 18); v = b.short.toInt() and 0xFFFF }
                    } catch (_: Exception) {}
                    v
                }.let { if (it > 0) it else 1000 }
            }
            baseScale = baseSizePx / upm
            require(baseScale > 0f) { "font scale must be positive" }
            unitsPerEm = upm
            ascentPx = ascentUnits * baseScale
            descentPx = -descentUnits * baseScale
            lineHeightPx = ceil(ascentPx + descentPx)
            println(
                "[TT-FONT] vertical metrics source=${metrics?.source ?: "hhea"} " +
                    "ascent=${ascentUnits} descent=${descentUnits} upm=$upm -> " +
                    "lineHeightPx=$lineHeightPx (em=$baseSizePx)"
            )
        }
    }

    /**
     * 单码点 advance(1x 基准,px)。换行/回车记 0;制表符按 4 空格宽。
     */
    fun codepointAdvance(codepoint: Int): Float = when (codepoint) {
        '\n'.code, '\r'.code -> 0f
        // \t 控制字符:无字形,由控制字符规则走原版回退(不在字体层特判)
        else -> MemoryStack.stackPush().use { stack ->
            val advance = stack.mallocInt(1)
            val lsb = stack.mallocInt(1)
            stbtt_GetCodepointHMetrics(info, codepoint, advance, lsb)
            advance.get(0) * baseScale
        }
    }

    /** 字偶距(font units → px,stb kern 表;无表字体恒 0) */
    fun codepointKernAdvance(left: Int, right: Int): Float =
        MemoryStack.stackPush().use { stack ->
            val kern = stack.mallocInt(1)
            val lsb = stack.mallocInt(1)
            stbtt_GetCodepointHMetrics(info, left, kern, lsb) // 占位读,防未初始化语义混淆
            kern.clear()
            val kv = stbtt_GetCodepointKernAdvance(info, left, right)
            kv * baseScale
        }

    private fun spaceAdvance(): Float = MemoryStack.stackPush().use { stack ->
        val advance = stack.mallocInt(1)
        val lsb = stack.mallocInt(1)
        stbtt_GetCodepointHMetrics(info, ' '.code, advance, lsb)
        advance.get(0) * baseScale
    }

    /** 解析出的垂直度量(font units)、upm 与来源标注 */
    private class VerticalMetrics(
        val ascentUnits: Int,
        val descentUnits: Int,
        val unitsPerEm: Int,
        val source: String,
    )

    /**
     * 解析 sfnt 表目录并读取垂直度量:优先 OS/2 typo(v2+,排版紧凑),并施加
     * **CJK 基线下限**(ascent >= 0.88em)。OS/2 缺失/非法回退 hhea。
     */
    private fun parseVerticalMetrics(data: ByteBuffer, faceOffset: Int): VerticalMetrics? {
        return try {
            val buf = data.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN)
            buf.position(faceOffset + 4)
            val numTables = buf.short.toInt() and 0xFFFF
            var os2Offset = -1
            var headOffset = -1
            for (i in 0 until numTables) {
                val entry = faceOffset + 12 + i * 16
                buf.position(entry)
                val tag = ByteArray(4)
                buf.get(tag)
                // 表项布局:[tag][checksum][offset][length] —— 先跳过 checksum
                when (String(tag, Charsets.US_ASCII)) {
                    "OS/2" -> { buf.int; os2Offset = buf.int }
                    "head" -> { buf.int; headOffset = buf.int }
                }
            }
            if (headOffset < 0) return null
            buf.position(headOffset + 18)
            val unitsPerEm = buf.short.toInt() and 0xFFFF
            if (unitsPerEm <= 0) return null
            if (os2Offset >= 0) {
                buf.position(os2Offset)
                val version = buf.short.toInt() and 0xFFFF
                if (version >= 2) {
                    buf.position(os2Offset + 68)
                    val typoAscender = buf.short.toInt()
                    val typoDescender = buf.short.toInt()
                    if (typoAscender > 0 && typoDescender < 0 && typoAscender - typoDescender <= unitsPerEm * 2) {
                        val cjkFloor = (unitsPerEm * 88 + 99) / 100
                        return VerticalMetrics(
                            maxOf(typoAscender, cjkFloor),
                            typoDescender,
                            unitsPerEm,
                            "OS/2 typo+CJK floor",
                        )
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 是否有该码点的字形轮廓(缺字判定,emoji 等 → 调用方回退原版) */
    fun hasGlyph(codepoint: Int): Boolean = stbtt_FindGlyphIndex(info, codepoint) != 0

    /**
     * 混淆字形池(T.TT):拉丁可打印区 + CJK 统一表意文字采样(步长 7)。
     * 懒构建一次;只查字形存在性,无光栅化开销。
     */
    private val asciiObfuscationPool: List<Int> by lazy {
        ('!'..'~').filter { hasGlyph(it.code) }.map { it.code }
    }

    private val cjkObfuscationPool: List<Int> by lazy {
        val list = ArrayList<Int>(300)
        var cjk = 0x4E00
        while (cjk <= 0x9FA5) {
            if (hasGlyph(cjk)) list.add(cjk)
            cjk += 7
        }
        list
    }

    /**
     * 取混淆替换字形:**同文字系统内随机**(拉丁原文 → ASCII 池,非拉丁 → CJK 池),
     * 确定性种子 —— 同一槽位内同一位置稳定,跨槽位重掷。
     *
     * 不做同宽约束:比例字体下各字符宽度离散,同宽桶大多只含元素自身,
     * 观感为「绝大多数字母永不变、少数偶尔跳一下还长期保持」(实测反馈);
     * 布局稳定性与替换宽度无关 —— advance 始终取原字符(TrueTypeTextWriter),
     * 替换字形只影响视觉。空格不进入混淆(调用方过滤,原版语义)。
     */
    fun randomObfuscationCandidate(originalCodepoint: Int, seed: Long): Int? {
        val pool = if (originalCodepoint < 0x2E80) asciiObfuscationPool else cjkObfuscationPool
        if (pool.isEmpty()) return null
        return pool[java.util.Random(seed).nextInt(pool.size)]
    }

    /**
     * 光栅化指定码点为指定字号的灰度位图。
     * 缺字([hasGlyph] == false)或无轮廓(空白字符)返回 null —— 调用方
     * 分别按「整 run 回退原版」与「仅 advance 无位图」处理。
     */
    /**
     * 光栅化指定码点为指定字号的灰度位图。
     *
     * 缺字([hasGlyph] == false)或无轮廓(空白字符)返回 null —— 调用方
     * 分别按「整 run 回退原版」与「仅 advance 无位图」处理。
     *
     * [emboldenPx] > 0 时做**偏移并集合成粗体**:把字形按若干整数偏移的光栅
     * 结果逐像素取最大值合并进同一位图 —— 笔画真实加宽、边缘保留各自抗锯齿、
     * 单张纹理单次绘制(无原版双绘制的重影,也无亚像素灰度膨胀的发糊)。
     * 偏移档位随强度增长:≥0.5 右移 1px;≥1 加下移;≥1.5 再加左移补厚。
     * 输出位图向右/向下扩,bearing 同步修正。
     */
    fun rasterize(codepoint: Int, sizePx: Float, emboldenPx: Float = 0f): RasterizedGlyph? {
        if (!hasGlyph(codepoint)) return null
        // em 归一:scale = em_px / unitsPerEm(与布局 baseScale 同语义)
        val scale = sizePx / unitsPerEm.toFloat()
        MemoryStack.stackPush().use { stack ->
            val width = stack.mallocInt(1)
            val height = stack.mallocInt(1)
            val xoff = stack.mallocInt(1)
            val yoff = stack.mallocInt(1)
            val bitmap = stbtt_GetCodepointBitmap(info, scale, scale, codepoint, width, height, xoff, yoff)
                ?: return null
            try {
                val w = width.get(0)
                val h = height.get(0)
                if (w <= 0 || h <= 0) return null
                // 逐字节绝对索引拷贝(不用 bulk get):规避 JVM jbyte_disjoint_arraycopy
                // stub 的原生写越界崩溃(hs_err pid54132)
                if (emboldenPx < 0.5f) {
                    val bytes = ByteArray(w * h)
                    for (i in bytes.indices) bytes[i] = bitmap.get(i)
                    return RasterizedGlyph(bytes, w, h, xoff.get(0), yoff.get(0))
                }
                // ── 偏移并集合成粗体:整数偏移的多份光栅取逐像素最大值 ──
                val strength = emboldenPx.coerceIn(0.25f, 2f)
                data class Off(val dx: Int, val dy: Int)
                val offsets = ArrayList<Off>(8)
                offsets.add(Off(0, 0))
                if (strength >= 0.5f) offsets.add(Off(1, 0))
                if (strength >= 1.0f) { offsets.add(Off(0, 1)); offsets.add(Off(1, 1)) }
                if (strength >= 1.5f) { offsets.add(Off(-1, 0)); offsets.add(Off(-1, 1)) }
                val minDx = offsets.minOf { it.dx }
                val maxDx = offsets.maxOf { it.dx }
                val maxDy = offsets.maxOf { it.dy }
                val ow = w + (maxDx - minDx)
                val oh = h + maxDy
                val out = ByteArray(ow * oh)
                for (sy in 0 until h) {
                    for (sx in 0 until w) {
                        val v = bitmap.get(sy * w + sx).toInt() and 0xFF
                        if (v == 0) continue
                        for (off in offsets) {
                            val idx = (sy + off.dy) * ow + (sx + off.dx - minDx)
                            if (v > (out[idx].toInt() and 0xFF)) out[idx] = v.toByte()
                        }
                    }
                }
                return RasterizedGlyph(
                    bytes = out,
                    width = ow,
                    height = oh,
                    bearingX = xoff.get(0) - minDx,
                    bearingTop = yoff.get(0),
                )
            } finally {
                stbtt_FreeBitmap(bitmap)
            }
        }
    }

    /** 无需显式释放(直接缓冲由 GC 管理);保留 AutoCloseable 与字体生命周期语义对齐 */
    override fun close() = Unit

    companion object {

        /**
         * 从字体数据解析字体(必须 direct ByteBuffer)。
         * TTC(TrueType Collection):从 index 0 起逐个尝试,直到解析成功或
         * 索引耗尽(msyh.ttc 等集合字体走此路径)。
         */
        fun load(data: ByteBuffer, baseSizePx: Float): TrueTypeFont {
            require(data.isDirect) { "TrueTypeFont.load requires a direct ByteBuffer" }
            var index = 0
            while (true) {
                val offset = stbtt_GetFontOffsetForIndex(data, index)
                if (index > 0 && offset < 0) break
                val info = STBTTFontinfo.create()
                if (stbtt_InitFont(info, data, if (offset < 0) 0 else offset)) {
                    return TrueTypeFont(info, data, baseSizePx, if (offset < 0) 0 else offset)
                }
                index++
            }
            throw IllegalArgumentException("Unable to parse font data (TTF/TTC), no parsable face found")
        }

        /** 读文件为 direct ByteBuffer(stb 要求可寻址原生内存) */
        fun readFile(path: java.nio.file.Path): ByteBuffer {
            val bytes = java.nio.file.Files.readAllBytes(path)
            return ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() as ByteBuffer
        }
    }
}
