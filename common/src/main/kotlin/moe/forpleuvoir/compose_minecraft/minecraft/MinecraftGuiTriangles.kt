package moe.forpleuvoir.compose_minecraft.minecraft

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2fc
import org.slf4j.Logger

// ─────────────────────────────────────────────────────────────────────────────
// 平台自定义 GUI 三角形渲染管线与渲染元素
//
// 背景:MC 26.2 的 GuiRenderState.addGuiElement 接受任意 GuiElementRenderState,
// GuiRenderer 按 pipeline() 分组、自动生成索引并调用 buildVertices() 上传。
// 但 MC 自带的 GUI pipeline(RenderPipelines.GUI)是 QUADS 拓扑,无法表达任意
// 三角形网格 —— 因此这里自建一个 TRIANGLES 拓扑的 pipeline,shader 为
// 仓库内自写的 core/gui_triangles(与 core/gui 同构:POSITION_COLOR_LINE_WIDTH
// + 纯色,保守 GLSL 330,OpenGL / Vulkan 两个渲染后端均可编译)。
//
// 平台适配点:
// - shader 的 uniform 块声明必须与 pipeline 的 BindGroupLayout(DynamicTransforms
//   / Projection,即 MATRICES_PROJECTION)完全一致,否则链接失败;
// - 不依赖 RenderPipelines 内部的 snippet/register(neoForm 视图下为 private),
//   管线参数在此显式声明:POSITION_COLOR_LINE_WIDTH、TRANSLUCENT 混合、
//   TRIANGLES 拓扑,cull 关闭(三角形绕序不做约束,与 GUI 四边形观感一致);
// - bounds() 不能为 null:GuiRenderState.findAppropriateNode 对 null bounds
//   直接丢弃元素;
// - 编译时机:shader 由 GpuDevice.precompilePipeline 编译(GameRenderer
//   .preloadUiShader 同款机制),需要 ResourceProvider 读取 shader 源码;
//   本平台在首次绘制几何时(主线程 extractRenderState 阶段)懒编译,幂等。
// ─────────────────────────────────────────────────────────────────────────────

/** 平台 GUI 三角形渲染管线与编译管理 */
internal object MinecraftGuiTriangles {

    private val LOGGER: Logger = LogUtils.getLogger()

    /**
     * 自定义三角形 pipeline:与 GUI 相同的渲染语义(纯色、TRANSLUCENT alpha
     * 混合、DynamicTransforms/Projection 绑定),拓扑为 [PrimitiveTopology.TRIANGLES],
     * shader 为自写的 core/gui_triangles。
     *
     * 顶点格式 POSITION_COLOR_LINE_WIDTH:LineWidth 属性承载「到最近真实轮廓的
     * 有符号屏幕像素距离」(coverage:外侧负、轮廓 0、内侧正),片元着色器
     * 用 smoothstep(-0.5·fwidth(d), 0.5·fwidth(d), d) 做边缘抗锯齿,过渡带
     * 恒约为 1 物理像素。
     * 不注册进 RenderPipelines(neoForm 下 register 不可访问,渲染时
     * GuiRenderer 直接使用对象本身,无需注册表)。
     */
    val pipeline: RenderPipeline by lazy {
        RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("compose_minecraft", "pipeline/gui_triangles"))
            .withVertexShader(Identifier.fromNamespaceAndPath("compose_minecraft", "core/gui_triangles"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("compose_minecraft", "core/gui_triangles"))
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build()
    }

    @Volatile
    private var compiled = false

    /**
     * 确保 pipeline 的 shader 已编译(幂等,只编译一次)。
     * 必须在主线程、渲染上下文活跃时调用(与 [GameRenderer.preloadUiShader] 相同约束),
     * 本平台由 [MinecraftRenderContext] 首次遇到几何命令时触发。
     */
    fun ensureCompiled() {
        if (compiled) return
        val device = RenderSystem.getDevice()
        val resourceProvider = Minecraft.getInstance().resourceManager
        val shaderSource = ShaderSource { id, type ->
            val location = type.idConverter().idToFile(id)
            try {
                resourceProvider.getResourceOrThrow(location).openAsReader().use { it.readText() }
            } catch (e: Exception) {
                LOGGER.error("Compose-Minecraft: 无法读取 shader {}", location, e)
                null
            }
        }
        device.precompilePipeline(pipeline, shaderSource)
        compiled = true
    }
}

/**
 * 三角形网格 GUI 渲染元素。
 *
 * - 顶点:局部坐标(场景 px,密度 1)平铺的 [x, y] 三元组序列,每 3 个顶点一个
 *   三角形;几何变换由 [pose](命令矩阵的 2D 部分)在 GPU 端完成,与 [BlitRenderState] 一致;
 * - 颜色:统一 0xAARRGGBB(三角化器已把 Compose Color 与 Paint.alpha 折算好);
 * - coverage:与顶点一一对应的「到最近真实轮廓的有符号屏幕像素距离」
 *   (LineWidth 属性槽):外侧为负、轮廓上为 0、内侧为正;内部实心三角形为大数;
 *   片元着色器 smoothstep(-0.5·fwidth(d), 0.5·fwidth(d), d) 做约 1 物理像素的
 *   边缘抗锯齿过渡;
 * - bounds:局部包围盒经 pose 变换后与 scissor 求交 —— 供 GuiRenderer 的
 *   层级归并(findAppropriateNode)使用,非 null 是元素被接受的前提。
 */
internal class GuiTriangleRenderState(
    val pose: Matrix3x2fc,
    val colorArgb: Int,
    val scissor: ScreenRectangle?,
    val vertices: FloatArray,
    val coverage: FloatArray,
) : GuiElementRenderState {

    private val elementBounds: ScreenRectangle = computeBounds(pose, scissor, vertices)

    override fun pipeline(): RenderPipeline = MinecraftGuiTriangles.pipeline

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle = elementBounds

    override fun buildVertices(vertexConsumer: VertexConsumer) {
        var i = 0
        var c = 0
        while (i + 1 < vertices.size) {
            vertexConsumer
                .addVertexWith2DPose(pose, vertices[i], vertices[i + 1])
                .setColor(colorArgb)
                .setLineWidth(coverage[c])
            i += 2
            c++
        }
    }

    private companion object {

        /** 局部包围盒(floor/ceil 保守取整)→ pose 变换 → 与 scissor 求交 */
        fun computeBounds(pose: Matrix3x2fc, scissor: ScreenRectangle?, vertices: FloatArray): ScreenRectangle {
            if (vertices.size < 6) return ScreenRectangle(0, 0, 0, 0)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var i = 0
            while (i + 1 < vertices.size) {
                val x = vertices[i]
                val y = vertices[i + 1]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                i += 2
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
