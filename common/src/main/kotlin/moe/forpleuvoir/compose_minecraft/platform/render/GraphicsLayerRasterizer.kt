package moe.forpleuvoir.compose_minecraft.platform.render

import androidx.compose.ui.graphics.MinecraftCanvas
import moe.forpleuvoir.compose_minecraft.platform.render.backend.CommandDispatcher
import moe.forpleuvoir.compose_minecraft.platform.render.backend.RasterBackend

/**
 * CPU 光栅化入口(P2 起为 facade):把 [MinecraftCanvas] 录制的绘制命令直接光栅化
 * 到 CPU 像素缓冲(0xAARRGGBB),支撑 [androidx.compose.ui.graphics.layer.GraphicsLayer.toImageBitmap]
 * —— 图层内容快照,无渲染上下文依赖,任意线程可调用。
 *
 * 具体光栅化实现(三角化重心填充 / 图片逆矩阵采样 / 渐变顶点色)已随 P2 搬移至
 * [RasterBackend],与 GPU 回放路径共用 [CommandDispatcher] 同一命令分派。
 *
 * 不支持(静默跳过,快照中缺失,调用方自行注意):
 * - 文本命令:MC 字体渲染在 GPU 贴图侧,CPU 端无字体现成方案;
 * - 阴影命令:阴影是 GPU 距离场渲染,快照不含阴影。
 */
internal object GraphicsLayerRasterizer {

    /** 光栅化整帧命令到 [width]x[height] 缓冲(0xAARRGGBB,初始全透明) */
    fun rasterize(canvas: MinecraftCanvas, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        val backend = RasterBackend(out, width, height)
        for (cmd in canvas.commands()) {
            CommandDispatcher.dispatch(cmd, backend)
        }
        return out
    }
}