package moe.forpleuvoir.compose_minecraft.platform.render.pipeline

import com.mojang.blaze3d.pipeline.PipelineCache
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import moe.forpleuvoir.compose_minecraft.mc
import net.minecraft.client.renderer.ShaderManager
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

/**
 * 自绘管线提交用的编译缓存(既含模组自建 pipeline,也含 Compose 绘制复用的原版 pipeline)。
 *
 * 26.3 的全局 fallback 管线缓存由 `GameRenderer.preloadUiShader(vanillaPackResources.asResourceManager())`
 * 建立,其 [ShaderSource] 只覆盖 vanilla 资源包;模组命名空间的 shader 与模组的
 * `shaders/include` 都读不到。这里以 `mc.resourceManager`(含模组资源包)构建
 * [ShaderSource]、自持 [PipelineCache] 产出 [CompiledRenderPipeline]。
 *
 * 编译在 [PipelineCache.get] 内同步完成(后台执行器 + join),须在主线程、渲染上下文活跃时调用。
 */
object ComposePipelineCache {

    private var cache: PipelineCache? = null

    /** 取 [pipeline] 的已编译实例:命中直接返回,未命中即时编译;失败时丢弃缓存重建一次。 */
    fun compiled(pipeline: RenderPipeline): CompiledRenderPipeline {
        cache?.get(pipeline)?.let { return it }
        reset()
        return cache!!.get(pipeline) ?: error("Failed to compile pipeline ${pipeline.getLocation()}")
    }

    /** 丢弃已编译管线并重建缓存(资源重载后 shader / include 内容变化时调用)。 */
    fun reset() {
        cache?.close()
        cache = PipelineCache(RenderSystem.getDevice(), ResourceManagerShaderSource())
    }
}

/**
 * 从客户端资源包(含模组资源包)读取 GLSL 源码与 `#include` 头。
 *
 * include 表由 [ShaderManager.listAllIncludes] 一次性读入并随 [close] 释放,由
 * [PipelineCache.close] 在缓存重建/关闭时调用;每个缓存实例持有独立副本。
 */
private class ResourceManagerShaderSource : ShaderSource {

    private val logger = LoggerFactory.getLogger(ResourceManagerShaderSource::class.java)
    private val includes: Map<Identifier, ShaderSource.CachedIncludeSource> =
        ShaderManager.listAllIncludes(mc.resourceManager)

    override fun getShader(id: Identifier, type: ShaderType): String? {
        val location = type.idConverter().idToFile(id)
        return try {
            mc.resourceManager.getResourceOrThrow(location).openAsReader().use { it.readText() }
        } catch (e: Exception) {
            logger.error("Compose-Minecraft: failed to read shader {}", location, e)
            null
        }
    }

    override fun getInclude(id: Identifier): ShaderSource.CachedIncludeSource? = includes[id]

    override fun close() {
        includes.values.forEach { it.close() }
    }
}
