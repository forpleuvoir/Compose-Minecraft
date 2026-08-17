package moe.forpleuvoir.compose_minecraft.platform.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2fc

/**
 * GPU 距离场软阴影渲染元素(平台扩展,T.14 重构版)。
 *
 * - 顶点:局部坐标交错 [x, y, distNorm] 平铺,每 3 个顶点一个三角形;
 *   distNorm = 到阴影形状真实轮廓的有符号距离 ÷ (σ√2)(片元插值),
 *   gui_shadow 片元着色器用高斯模糊解析解 erfc(distNorm)/2 生成 alpha
 *   (参照 Skia SkShadowUtils:σ = 0.667·e,无离屏、无 CPU 模糊);
 * - 颜色:RGB = 阴影颜色(默认黑),alpha = 颜色 alpha × 阴影强度
 *   (ambient 0.039 / spot 0.19 × (1-e/600)),由 [shadowColorArgb] 统一给定
 *   (每个阴影 = ambient + spot 两个元素,颜色各自携带,T.18);
 * - 网格由 [GeometryTessellator.shadowFill] 生成并 LRU 缓存
 *   ([MinecraftShadowRenderer]),形状不变时每帧零 CPU;
 * - bounds:本体(外扩模糊带)经 pose 变换后与 scissor 求交 —— 供
 *   GuiRenderer 层级归并使用,非 null 是元素被接受的前提;
 * - 渲染顺序:GuiRenderStateMixin 把本类型元素排序到列表最前(最先绘制 =
 *   最底层),内容后画盖住阴影重叠部分 —— 等价于官方"先画阴影、后画内容"。
 */
internal class GuiShadowRenderState(
    val pose: Matrix3x2fc,
    val shadowColorArgb: Int,
    val scissor: ScreenRectangle?,
    /** 交错 [x, y, distNorm] 平铺,每 3 个顶点一个三角形 */
    val vertices: FloatArray,
    elementBounds: ScreenRectangle,
) : GuiElementRenderState {

    private val elementBounds: ScreenRectangle = elementBounds

    override fun pipeline(): RenderPipeline = MinecraftGuiTriangles.shadowPipeline

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle = elementBounds

    override fun buildVertices(vertexConsumer: VertexConsumer) {
        var i = 0
        while (i + 2 < vertices.size) {
            vertexConsumer
                .addVertexWith2DPose(pose, vertices[i], vertices[i + 1])
                .setColor(shadowColorArgb)
                .setLineWidth(vertices[i + 2])
            i += 3
        }
    }
}
