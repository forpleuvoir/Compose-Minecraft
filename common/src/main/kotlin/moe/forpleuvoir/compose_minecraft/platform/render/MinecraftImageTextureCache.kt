package moe.forpleuvoir.compose_minecraft.platform.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.MinecraftImageBitmap
import net.minecraft.client.gui.render.TextureSetup
import org.lwjgl.system.MemoryUtil
import java.nio.ByteOrder

/**
 * [MinecraftImageBitmap] → GPU 纹理上传与缓存(T.16 图片管线)。
 *
 * 平台适配点:
 * - [MinecraftImageBitmap] 是 CPU 像素缓冲(IntArray,0xAARRGGBB),绘制端
 *   [MinecraftRenderContext] 回放 `DrawImageRectCommand` 时按需上传为
 *   [GpuTexture] 并经 [TextureSetup.singleTexture] 提交 [net.minecraft.client.renderer.state.gui.BlitRenderState];
 * - 上传只在渲染线程执行([MinecraftRenderContext.render] 由 extractRenderState 每帧驱动),
 *   `ImageBitmap.prepareToDraw()` 的调用线程不可靠,不承担上传职责;
 * - 位图内容创建后不可变(buffer 只读),按对象身份缓存纹理,内容不变则零重传;
 * - LRU 上限 64,淘汰时 close 纹理(GpuTexture/AutoCloseable,防 GPU 内存泄漏);
 * - 采样器不缓存进 Entry:[FilterQuality.None] → 最近邻(NEAREST),
 *   其余 → 双线性(LINEAR),每次按绘制参数从 SamplerCache 取(对象复用,开销可忽略)。
 *
 * 像素格式:Compose Argb8888 Int = 0xAARRGGBB;NativeImage 内存为 ABGR 字节序
 * (0xAABBGGRR Int 视图),写入前做 R/B 交换。
 */
internal object MinecraftImageTextureCache {

    private class Entry(
        val texture: GpuTexture,
        val view: GpuTextureView,
    ) {
        fun close() {
            view.close()
            texture.close()
        }
    }

    /** 位图身份 → 纹理(LRU,accessOrder;淘汰时 close 纹理) */
    private val cache = object : LinkedHashMap<MinecraftImageBitmap, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<MinecraftImageBitmap, Entry>?,
        ): Boolean {
            if (size <= CACHE_LIMIT) return false
            eldest?.value?.close()
            return true
        }
    }

    /** 取位图纹理的 [TextureSetup](首次调用触发上传);必须在渲染线程调用。
     *  默认 [FilterQuality.None](平台适配点 T.16:最近邻,MC 像素风;显式传 Low 得双线性) */
    fun textureSetup(image: MinecraftImageBitmap, filterQuality: FilterQuality = FilterQuality.None): TextureSetup {
        val entry = cache.getOrPut(image) { upload(image) }
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(
            if (filterQuality == FilterQuality.None) FilterMode.NEAREST else FilterMode.LINEAR,
        )
        return TextureSetup.singleTexture(entry.view, sampler)
    }

    /**
     * CPU 像素上传为 RGBA8 纹理(渲染线程,首次绘制时执行一次)。
     *
     * 注意:NativeImage.getPixelsABGR()/getPixels() 返回**拷贝**(只读用途),
     * 写入必须走像素内存。这里用 nmemAlloc 分配对齐内存并包装成 NativeImage
     * (useStbFree=false),以小端 Int 视图写入 0xAABBGGRR → 内存字节
     * [R,G,B,A] = RGBA8_UNORM 期望的字节序;close() 时 nmemFree 配对释放。
     */
    private fun upload(image: MinecraftImageBitmap): Entry {
        val width = image.width
        val height = image.height
        val size = width.toLong() * height * 4L
        val ptr = MemoryUtil.nmemAlloc(size)
        val native = NativeImage(NativeImage.Format.RGBA, width, height, false, ptr)
        try {
            val src = image.buffer
            val dst = MemoryUtil.memByteBuffer(ptr, size.toInt())
                .order(ByteOrder.nativeOrder())
                .asIntBuffer()
            for (i in src.indices) {
                dst.put(i, argbToAbgr(src[i]))
            }
            val device = RenderSystem.getDevice()
            val texture = device.createTexture(
                { "compose-minecraft-image" },
                GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1,
            )
            device.createCommandEncoder().writeToTexture(texture, native)
            val view = device.createTextureView(texture)
            return Entry(texture, view)
        } finally {
            native.close()
        }
    }

    /** 0xAARRGGBB → 0xAABBGGRR(Compose Argb8888 → NativeImage ABGR 字节序,R/B 交换) */
    private fun argbToAbgr(argb: Int): Int =
        (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16)

    private const val CACHE_LIMIT = 64
}
