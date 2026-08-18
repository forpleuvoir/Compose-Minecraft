package moe.forpleuvoir.compose_minecraft.platform.render

import androidx.compose.ui.graphics.MinecraftCanvas
import moe.forpleuvoir.compose_minecraft.platform.render.backend.CommandDispatcher
import moe.forpleuvoir.compose_minecraft.platform.render.backend.GuiStateBackend
import moe.forpleuvoir.compose_minecraft.platform.render.backend.PerspectiveBackend

/**
 * 渲染入口(P2/P3 起为纯编排层):把 [MinecraftCanvas] 录制的绘制命令分派给各渲染后端。
 *
 * - 2D 命令 → [CommandDispatcher] → [GuiStateBackend](GUI 状态,原 render() 2D 分支,P2);
 * - 3D 命令(携带 layer3D)→ [PerspectiveBackend](CPU 顶点透视变换,T.15,原 render3D 搬移,P3),
 *   自成一式,维持 D4 决定:不共享 2D triangleCache;
 * - 快照路径(toImageBitmap)由 [GraphicsLayerRasterizer] 经同一 [CommandDispatcher]
 *   分派给 RasterBackend。
 *
 * 内部类:MinecraftCanvas 及其命令模型为 internal,业务代码经 MinecraftComposeScene 使用。
 */
internal class MinecraftRenderContext {

    /** 2D 后端实例(跨帧持有:内部 triangleCache 缓存必须保留;sink 每帧设置) */
    private val guiBackend = GuiStateBackend()

    /** 3D 后端实例(sink 每帧更新,内部 triangleSink 复用) */
    private var perspectiveBackend: PerspectiveBackend? = null

    /** 把 [canvas] 中的命令逐条提交到 [sink](T.24:像素坐标,场景 1:1 窗口像素) */
    fun render(canvas: MinecraftCanvas, sink: GuiCommandSink) {
        guiBackend.sink = sink
        for (command in canvas.commands()) {
            // T.15:3D 命令(图层 rotationX/rotationY,携带行主序含透视的 layer3D)
            // 走 CPU 顶点透视变换路径(纯色几何 → 屏幕三角形,实心无 AA)。
            if (command.layer3D != null) {
                perspectiveBackend(sink).render(command)
                continue
            }
            CommandDispatcher.dispatch(command, guiBackend)
        }
    }

    private fun perspectiveBackend(sink: GuiCommandSink): PerspectiveBackend {
        val existing = perspectiveBackend
        if (existing != null) {
            existing.sink = sink
            return existing
        }
        return PerspectiveBackend(sink).also { perspectiveBackend = it }
    }
}