package moe.forpleuvoir.compose_minecraft.platform.render.text

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2fc

/**
 * TrueType 文本 quad 批次渲染元素(T.TT,设计文档 §3「GuiGlyphRenderState」)。
 *
 * - 顶点:命令局部坐标 [x, y, u, v] 平铺,每顶点 4 float、每 quad 6 顶点
 *   (两三角形);几何变换由 [pose](命令矩阵的 2D 部分)在 GPU 端完成,
 *   与 BlitRenderState/GuiTriangleRenderState 一致;
 * - 颜色:每顶点 0xAARRGGBB(P1 单 run 统一色;渐变逐字形取色属第二步);
 * - UV:R8 图集页内的归一化坐标([GlyphAtlas]);
 * - bounds:局部包围盒经 pose 变换后与 scissor 求交(元素被
 *   GuiRenderer 接受的前提)。
 */
internal class GuiGlyphRenderState(
    private val pose: Matrix3x2fc,
    private val scissor: ScreenRectangle?,
    /** 交错 [x, y, u, v] 平铺,每 quad 6 顶点 */
    private val vertices: FloatArray,
    /** 每顶点 0xAARRGGBB(与顶点数等长) */
    private val colors: IntArray,
    /** 图集页索引([MinecraftGuiText.pipeline] 的 Sampler0 绑定来源) */
    private val pageIndex: Int,
) : GuiElementRenderState {

    private val elementBounds: ScreenRectangle = computeBounds(pose, scissor, vertices)

    override fun pipeline(): RenderPipeline = MinecraftGuiText.pipeline

    override fun textureSetup(): TextureSetup = GlyphAtlas.textureSetup(pageIndex)

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle = elementBounds

    override fun buildVertices(vertexConsumer: VertexConsumer) {
        var vi = 0
        var i = 0
        while (i + 4 <= vertices.size) {
            vertexConsumer
                .addVertexWith2DPose(pose, vertices[i], vertices[i + 1])
                .setUv(vertices[i + 2], vertices[i + 3])
                .setColor(colors[vi])
            vi++
            i += 4
        }
    }

    private companion object {

        /** 局部包围盒(floor/ceil 保守取整)→ pose 变换 → 与 scissor 求交 */
        fun computeBounds(pose: Matrix3x2fc, scissor: ScreenRectangle?, vertices: FloatArray): ScreenRectangle {
            if (vertices.isEmpty()) return ScreenRectangle(0, 0, 0, 0)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var i = 0
            while (i + 4 <= vertices.size) {
                val x = vertices[i]
                val y = vertices[i + 1]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                i += 4
            }
            val local = ScreenRectangle(
                kotlin.math.floor(minX).toInt(),
                kotlin.math.floor(minY).toInt(),
                kotlin.math.ceil(maxX - minX).toInt(),
                kotlin.math.ceil(maxY - minY).toInt(),
            )
            val transformed = local.transformMaxBounds(pose)
            return scissor?.intersection(transformed) ?: transformed
        }
    }
}
