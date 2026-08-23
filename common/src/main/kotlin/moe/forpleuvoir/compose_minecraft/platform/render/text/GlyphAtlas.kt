package moe.forpleuvoir.compose_minecraft.platform.render.text

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.client.gui.render.TextureSetup
import org.lwjgl.system.MemoryUtil

/**
 * 字形纹理图集(T.TT,设计文档 §3):R8 单通道分页管理。
 *
 * - 页尺寸 1024×1024,`GpuFormat.R8_UNORM`(coverage 单通道,显存 1/4 of RGBA);
 * - 打包:shelf(货架)算法 —— x 向追加、行满换行、页满翻页;P3① 页粒度 LRU:
 *   水位超限时整页「退役」([retirePage],本帧不再分配、帧末 [flushRetiredPages]
 *   重置游标原地复用,不销毁纹理 —— 延迟到帧末保证本帧已提交元素的 UV 引用安全);
 * - 上传:`CommandEncoder.writeToTexture(ByteBuffer, …, destX, destY, w, h)`
 *   局部字节上传,只传字形矩形;
 * - 采样:clamp-to-edge + LINEAR(灰度线性插值 = 放大/缩小的边缘 AA;
 *   相邻字形渗色由调用方 1px padding 隔离,页面创建时整体清零保证 padding 恒 0);
 * - 线程:分配/上传/取 [TextureSetup] 必须在渲染线程(绘制阶段即渲染线程,
 *   与 [com.mojang.blaze3d.platform.NativeImage] 上传惯例一致)。
 */
internal object GlyphAtlas {

    /** 单页边长(px) */
    const val PAGE_SIZE: Int = 1024

    /** 图集槽位:所在页 + 像素矩形(UV 由调用方按需换算,含 padding 内缩) */
    class Slot(
        val page: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private class Page {
        val texture: GpuTexture = RenderSystem.getDevice().createTexture(
            { "compose-minecraft-glyph-atlas" },
            GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.R8_UNORM,
            PAGE_SIZE,
            PAGE_SIZE,
            1,
            1,
        )
        val view = RenderSystem.getDevice().createTextureView(texture)

        /** shelf 打包游标 */
        var cursorX = 0
        var cursorY = 0
        var rowHeight = 0

        /**
         * P3① 页退役标记:true = 已被 LRU 淘汰,等待帧末重置游标复用。
         * 标记期间 [allocate] 跳过本页 —— 淘汰发生在渲染帧的收集阶段时,
         * 本帧早前已提交元素仍引用页内旧槽位 UV,必须保证其内容在本帧
         * draw 结束前不被覆盖(延迟重置语义,见 [flushRetiredPages])。
         */
        var pendingReset = false

        init {
            // 整页清零:padding 区域恒为 0,LINEAR 渗色不会带进相邻字形
            val zeros = MemoryUtil.memCalloc(PAGE_SIZE * PAGE_SIZE)
            try {
                RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(texture, zeros, 0, 0, 0, 0, PAGE_SIZE, PAGE_SIZE)
            } finally {
                MemoryUtil.memFree(zeros)
            }
        }

        fun tryAllocate(w: Int, h: Int): Pair<Int, Int>? {
            if (w > PAGE_SIZE || h > PAGE_SIZE) return null
            if (cursorX + w > PAGE_SIZE) {
                cursorX = 0
                cursorY += rowHeight
                rowHeight = 0
            }
            if (cursorY + h > PAGE_SIZE) return null
            val pos = cursorX to cursorY
            cursorX += w
            if (h > rowHeight) rowHeight = h
            return pos
        }
    }

    private val pages = ArrayList<Page>()

    /**
     * 分配一个 w×h 像素槽位(页满自动翻页;单字形超页尺寸返回 null ——
     * 调用方按缺字处理回退原版)。已退役([isRetired])的页跳过,待帧末
     * 重置游标后重新参与分配。
     */
    fun allocate(w: Int, h: Int): Slot? {
        if (w <= 0 || h <= 0) return null
        for ((index, page) in pages.withIndex()) {
            if (page.pendingReset) continue
            page.tryAllocate(w, h)?.let { (x, y) ->
                return Slot(index, x, y, w, h)
            }
        }
        val page = Page()
        pages.add(page)
        val pos = page.tryAllocate(w, h) ?: return null
        return Slot(pages.size - 1, pos.first, pos.second, w, h)
    }

    /**
     * 把灰度字节(row-major)上传到槽位内部的 [width]×[height] 矩形
     * (渲染线程)。[offsetX]/[offsetY] 为相对槽位左上角的偏移 —— 调用方
     * 分配带 padding 的槽位后,把字形字节写进内部矩形,padding 区保持清零。
     */
    fun upload(
        slot: Slot,
        bytes: ByteArray,
        offsetX: Int = 0,
        offsetY: Int = 0,
        width: Int = slot.width,
        height: Int = slot.height,
    ) {
        require(width > 0 && height > 0) { "glyph upload size must be positive" }
        require(offsetX + width <= slot.width && offsetY + height <= slot.height) {
            "glyph upload rect ($offsetX,$offsetY,$width,$height) exceeds slot ${slot.width}x${slot.height}"
        }
        require(bytes.size >= width * height) { "glyph bytes too small" }
        val len = width * height
        val buffer = MemoryUtil.memAlloc(len)
        try {
            // 逐字节绝对索引写入(不用 bulk put):规避 JVM jbyte_disjoint_arraycopy
            // stub 的原生写越界崩溃(hs_err pid54132)
            for (i in 0 until len) buffer.put(i, bytes[i])
            buffer.position(0).limit(len)
            pages[slot.page].texture.let {
                RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(it, buffer, 0, 0, slot.x + offsetX, slot.y + offsetY, width, height)
            }
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    /** 取某页的 [TextureSetup](clamp-to-edge LINEAR 采样;渲染线程) */
    fun textureSetup(page: Int): TextureSetup {
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        return TextureSetup.singleTexture(pages[page].view, sampler)
    }

    /** 白像素槽位缓存:page → (u, v)(取格心,防 LINEAR 边缘半透) */
    private var whiteTexel: Triple<Int, Float, Float>? = null

    /**
     * 图集内的 1×1 全白像素(coverage=1),装饰线(下划线/删除线)quad 以它为
     * UV 经顶点色 tint 上色 —— 与字形共用 gui_text 管线/排序组,层级正确
     * (对齐原版字体装饰线的纹理 quad 实现)。渲染线程调用。
     */
    fun whiteTexelUV(): Triple<Int, Float, Float> {
        whiteTexel?.let { return it }
        val slot = allocate(1, 1)
            ?: throw IllegalStateException("GlyphAtlas failed to allocate white texel slot")
        upload(slot, byteArrayOf(0xFF.toByte()))
        return Triple(slot.page, (slot.x + 0.5f) / PAGE_SIZE, (slot.y + 0.5f) / PAGE_SIZE)
            .also { whiteTexel = it }
    }

    // ── P3① 页粒度 LRU:退役 / 复用 ────────────────────────────────────────

    /**
     * 整页退役:标记 [Page.pendingReset],本帧剩余时间不再分配;帧末
     * [flushRetiredPages] 重置游标后原地复用(纹理不销毁、不重传 —— 被覆盖的
     * 旧槽位 UV 已随 cache 条目失效,不会再被引用)。若白像素槽位在本页,
     * 同步作废其缓存(下次 [whiteTexelUV] 重新分配)。
     */
    fun retirePage(page: Int) {
        if (page < 0 || page >= pages.size) return
        val p = pages[page]
        if (p.pendingReset) return
        p.pendingReset = true
        if (whiteTexel?.first == page) whiteTexel = null
    }

    /** 全部页退役([GlyphCache.reset] 字体重载时调用,修复旧页显存滞留) */
    fun retireAllPages() {
        for (i in pages.indices) retirePage(i)
    }

    /**
     * 帧末调用:重置全部已退役页的打包游标,使其重新参与分配。
     * 仅元数据操作(无 GPU 写入);必须在整帧 draw 完成之后调用 —— 此前本帧
     * 已提交元素仍持有页内旧槽位 UV,提前重置会导致下一帧收集阶段覆盖这些
     * 槽位内容,造成一帧花屏。渲染线程调用。
     */
    fun flushRetiredPages() {
        for (p in pages) {
            if (!p.pendingReset) continue
            p.pendingReset = false
            p.cursorX = 0
            p.cursorY = 0
            p.rowHeight = 0
        }
    }

    /** 页是否处于待复用的退役状态(诊断/淘汰选页用) */
    fun isRetired(page: Int): Boolean =
        page in pages.indices && pages[page].pendingReset

    /** 活跃(未退役)页数 —— LRU 水位的比较基准 */
    fun activePageCount(): Int {
        var n = 0
        for (p in pages) if (!p.pendingReset) n++
        return n
    }

    /** 已用页数(诊断用) */
    fun pageCount(): Int = pages.size
}
