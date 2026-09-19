package moe.forpleuvoir.compose_minecraft.platform.render.pipeline

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer

/**
 * Minecraft 平台 [GraphicsContext]。
 *
 * 与 Skia 平台的 SkiaGraphicsContext 不同,本平台的 [GraphicsLayer] 是纯命令录制型:
 * 不分配任何离屏 VkImage/纹理,record 阶段只把绘制命令记录进内存,
 * 因此这里只负责创建/释放 [GraphicsLayer] 实例,不管理任何 GPU 资源。
 *
 * 第一版不做实例池(命令列表成本很低),后续如需优化再引入。
 */
class MinecraftGraphicsContext : GraphicsContext {

    override fun createGraphicsLayer(): GraphicsLayer = GraphicsLayer()

    override fun releaseGraphicsLayer(layer: GraphicsLayer) {
        layer.release()
    }

    /** 与 SkiaGraphicsContext.dispose() 对齐的调用点;本平台无 GPU 资源可释放 */
    fun dispose() = Unit
}
