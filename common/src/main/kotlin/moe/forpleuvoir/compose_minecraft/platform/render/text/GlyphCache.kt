package moe.forpleuvoir.compose_minecraft.platform.render.text

import kotlin.math.roundToInt

/**
 * 字形缓存(设计文档 §3):(码点, 量化字号) → 图集槽位 + 局部几何。
 *
 * - key 的字号按 0.25px 量化:段级字号/姿态缩放产生任意浮点字号,
 *   量化在清晰度无损的前提下显著提高缓存命中;
 * - 几何以 **1x 布局空间** 记录(位图像素 ÷ 光栅化放大系数):绘制端把
 *   quad 放进命令局部坐标系,由姿态矩阵统一变换到屏幕 —— 高分辨率位图
 *   经 UV 映射到同尺寸 quad,纹理像素与物理像素 1:1(矢量光栅化红利);
 * - 负缓存:缺字码点记录进 [misses],避免每帧重复 FindGlyphIndex;
 * -  页粒度 LRU:条目带帧号时间戳([CachedGlyph.lastUseFrame]),活跃页数
 *   超 [TextRenderConfig.atlasMaxPages] 水位时在帧首把「最久未使用」的整页
 *   退役(条目失效、页游标延迟到帧末重置复用)—— §k 混淆等长驻场景显存有界。
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

        /**  LRU 时间戳:最后一次命中/创建的渲染帧号([onFrameStart] 递增) */
        var lastUseFrame: Int = 0
            internal set
    }

    // 键打包进单个 Long(零分配查询):字体槽位 | 量化字号 | 粗体 | 码点
    private val fontSlots = HashMap<Int, Int>()
    private var nextFontSlot = 0

    private fun fontSlot(font: TrueTypeFont): Int =
        fontSlots.getOrPut(System.identityHashCode(font)) {
            (++nextFontSlot).also { require(it < 16) { "too many fonts" } }
        }

    private fun packKey(slot: Int, codepoint: Int, q: Float, bold: Boolean): Long =
        ((slot.toLong() and 0xF) shl 60) or
            (((q * 4).toInt().toLong() and 0xFFFF) shl 44) or
            ((if (bold) 1L else 0L) shl 43) or
            (codepoint.toLong() and 0x1FFFFF)

    private val cache = HashMap<Long, CachedGlyph>()

    /** 缺字负缓存(按字体实例区分,支持常规/粗体双字重) */
    private val misses = HashMap<Int, HashSet<Int>>()

    /**  渲染帧号时钟:每帧渲染入口递增,作为 LRU 时间戳 */
    private var frame = 0

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
        /** 折算除数:位图像素 → 命令布局像素(= 光栅化时附加的矩阵缩放;
         *  绝对像素坐标系下不含任何字号分量 —— 字号已体现在 sizePx 本身) */
        rasterDiv: Float = 1f,
    ): CachedGlyph? {
        val slot = fontSlot(font)
        val missSet = misses.getOrPut(slot) { HashSet() }
        if (codepoint in missSet) return null
        val q = quantize(sizePx)
        val key = packKey(slot, codepoint, q, bold)
        cache[key]?.let {
            it.lastUseFrame = frame //  LRU 命中续期
            return it
        }

        // 膨胀强度:连续值(设备字号 × 可配比例),软边衰减保留灰度过渡
        val embolden = if (bold) {
            (q * TextRenderConfig.boldEmboldenRatio).coerceIn(0.5f, 2f)
        } else 0f
        val glyph = font.rasterize(codepoint, q, embolden)
            ?: run {
                // 无轮廓(空白字符)与真缺字的区分:空白字符可光栅化为空但 hasGlyph 为 true
                if (!font.hasGlyph(codepoint)) {
                    missSet.add(codepoint)
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
        cached.lastUseFrame = frame
        cache[key] = cached
        return cached
    }

    /**
     * 帧首调用(每渲染帧一次,ComposeGuiRenderer.render 入口):
     * 递增 LRU 时钟 + 活跃页水位检查 —— 超出 [TextRenderConfig.atlasMaxPages]
     * 时反复淘汰「最冷」活跃页(页热度 = 页内条目最近使用帧号的最大值,
     * 空页视为最冷)直至回到水位内。淘汰 = 条目失效 + 页退役标记;
     * 页游标由 [GlyphAtlas.flushRetiredPages] 在帧末重置复用。
     * 渲染线程调用。
     */
    fun onFrameStart() {
        frame++
        trimToWatermark()
    }

    private fun trimToWatermark() {
        val maxActivePages = TextRenderConfig.atlasMaxPages.coerceAtLeast(1)
        // 每轮退役一页,循环次数有上界(活跃页数单调递减)
        while (GlyphAtlas.activePageCount() > maxActivePages) {
            val victim = coldestActivePage() ?: break
            removeEntriesOfPage(victim)
            GlyphAtlas.retirePage(victim)
        }
    }

    /** 最冷活跃页:页内条目 lastUseFrame 最大值最小者;无条目页视为最冷 */
    private fun coldestActivePage(): Int? {
        val heat = HashMap<Int, Int>()
        for (g in cache.values) {
            if (g.page < 0) continue // 空白字符占位(不在任何页)
            val cur = heat.getOrDefault(g.page, Int.MIN_VALUE)
            if (g.lastUseFrame > cur) heat[g.page] = g.lastUseFrame
        }
        var best = -1
        var bestHeat = Int.MAX_VALUE
        for (i in 0 until GlyphAtlas.pageCount()) {
            if (GlyphAtlas.isRetired(i)) continue
            val h = heat[i] ?: Int.MIN_VALUE
            if (h < bestHeat) {
                bestHeat = h
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    private fun removeEntriesOfPage(page: Int) {
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.page == page) it.remove()
        }
    }

    /** 清空(字体重载时调用;全部图集页退役,帧末重置游标后原地复用 —— 不再滞留旧显存) */
    fun reset() {
        cache.clear()
        misses.clear()
        GlyphAtlas.retireAllPages()
    }
}
