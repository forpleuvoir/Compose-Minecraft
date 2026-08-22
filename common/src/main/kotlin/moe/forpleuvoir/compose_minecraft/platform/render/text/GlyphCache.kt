package moe.forpleuvoir.compose_minecraft.platform.render.text

import kotlin.math.roundToInt

/**
 * 字形缓存(T.TT,设计文档 §3):(码点, 量化字号) → 图集槽位 + 局部几何。
 *
 * - key 的字号按 0.25px 量化:段级字号/姿态缩放产生任意浮点字号,
 *   量化在清晰度无损的前提下显著提高缓存命中;
 * - 几何以 **1x 布局空间** 记录(位图像素 ÷ 光栅化放大系数):绘制端把
 *   quad 放进命令局部坐标系,由姿态矩阵统一变换到屏幕 —— 高分辨率位图
 *   经 UV 映射到同尺寸 quad,纹理像素与物理像素 1:1(矢量光栅化红利);
 * - 负缓存:缺字码点记录进 [misses],避免每帧重复 FindGlyphIndex;
 * - LRU 淘汰属 P3(P1 写一次不删,页满翻页)。
 */
internal object GlyphCache {

    /** 字号量化步长(px) */
    private const val SIZE_QUANTUM = 0.25f

    /** 粗体膨胀强度比例(相对量化后设备字号,连续值支持亚像素),默认 1/32
     *  —— 18sp(32px 位图)时强度 1.0 = 每侧 +1px,笔画总宽 +2px,清晰加粗 */

    class CachedGlyph internal constructor(
        val page: Int,
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
        /** 位图宽(1x 布局空间,已按超采样折算) */
        val widthLocal: Float,
        /** 位图高(1x 布局空间,已按超采样折算) */
        val heightLocal: Float,
        /** 笔位到位图左缘(1x 布局空间,右为正) */
        val bearingXLocal: Float,
        /** 基线到位图顶(1x 布局空间;屏幕 y-down 约定,负值 = 在基线上方) */
        val bearingTopLocal: Float,
    ) {
        /** 空白字符(有 advance 无轮廓,如空格):只推进笔位不生成 quad */
        val hasBitmap: Boolean = widthLocal > 0f
    }

    private data class Key(val fontId: Int, val codepoint: Int, val quantizedSizePx: Float, val bold: Boolean)

    private val cache = HashMap<Key, CachedGlyph>()

    /** 缺字负缓存(按字体实例区分,支持常规/粗体双字重) */
    private val misses = HashMap<Int, HashSet<Int>>()

    fun quantize(sizePx: Float): Float = (sizePx / SIZE_QUANTUM).roundToInt() * SIZE_QUANTUM

    /**
     * 取字形(未命中则光栅化 + 入图集 + 上传)。渲染线程调用。
     * [bold] = true 时按可配强度(TextRenderConfig.boldEmboldenRatio)做膨胀合成粗体。
     * 返回 null = 缺字(调用方整 run 回退原版)。
     */
    fun getOrCreate(
        font: TrueTypeFont,
        codepoint: Int,
        sizePx: Float,
        bold: Boolean = false,
        /** 折算除数:位图像素 → 1x 布局像素(mscale × 超采样) */
        rasterDiv: Float = 1f,
    ): CachedGlyph? {
        val fontId = System.identityHashCode(font)
        misses.getOrPut(fontId) { HashSet() }
        if (misses[fontId]!!.contains(codepoint)) return null
        val q = quantize(sizePx)
        val key = Key(fontId, codepoint, q, bold)
        cache[key]?.let { return it }

        // 膨胀强度:连续值(设备字号 × 可配比例),软边衰减保留灰度过渡
        val embolden = if (bold) {
            (q * TextRenderConfig.boldEmboldenRatio).coerceIn(0.5f, 2f)
        } else 0f
        val glyph = font.rasterize(codepoint, q, embolden)
            ?: run {
                // 无轮廓(空白字符)与真缺字的区分:空白字符可光栅化为空但 hasGlyph 为 true
                if (!font.hasGlyph(codepoint)) {
                    misses.getOrPut(fontId) { HashSet() }.add(codepoint)
                    return null
                }
                null
            }

        val cached = if (glyph == null) {
            // 空白字符:有字形索引、无位图 → 只记 advance 语义(hasBitmap=false)
            CachedGlyph(-1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        } else {
            val pad = 1 // 双侧 1px padding,隔离 LINEAR 相邻渗色
            val slot = GlyphAtlas.allocate(glyph.width + pad * 2, glyph.height + pad * 2)
                ?: return null // 单字形超页尺寸(极端字号),回退原版
            // 字节写入 padding 包围的内部矩形,padding 区保持页面清零
            GlyphAtlas.upload(slot, glyph.bytes, pad, pad, glyph.width, glyph.height)
            CachedGlyph(
                page = slot.page,
                u0 = (slot.x + pad).toFloat() / GlyphAtlas.PAGE_SIZE,
                v0 = (slot.y + pad).toFloat() / GlyphAtlas.PAGE_SIZE,
                u1 = (slot.x + pad + glyph.width).toFloat() / GlyphAtlas.PAGE_SIZE,
                v1 = (slot.y + pad + glyph.height).toFloat() / GlyphAtlas.PAGE_SIZE,
                widthLocal = glyph.width / rasterDiv,
                heightLocal = glyph.height / rasterDiv,
                bearingXLocal = glyph.bearingX / rasterDiv,
                bearingTopLocal = glyph.bearingTop / rasterDiv,
            )
        }
        cache[key] = cached
        return cached
    }

    /** 清空(字体重载时调用;图集页不复用,靠翻页兜底) */
    fun reset() {
        cache.clear()
        misses.clear()
    }
}
