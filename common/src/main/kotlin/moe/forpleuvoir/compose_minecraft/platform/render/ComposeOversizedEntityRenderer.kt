package moe.forpleuvoir.compose_minecraft.platform.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import org.joml.Matrix3x2f
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3fc

/**
 * 实体画中画渲染器(T.37,参照原版 [net.minecraft.client.gui.render.pip.GuiEntityRenderer] +
 * [net.minecraft.client.gui.render.pip.PictureInPictureRenderer] 的实现):
 * 把实体渲染进独立 PIP 纹理,再 blit 到 Compose 渲染器(1:1 像素管道)。
 *
 * 与 [ComposeOversizedItemRenderer] 互补:物品走 atlas/离屏 PIP,实体预览(背包里的
 * 玩家模型、生物图鉴等)走本渲染器 —— 带 3D 光照/朝向/旋转,输出纹理按目标尺寸
 * 铺满,内容分辨率 = 目标尺寸,不依赖 guiScale。
 */
class ComposeOversizedEntityRenderer(
    private val emitElement: (GuiElementRenderState) -> Unit,
) {
    private var texture: GpuTexture? = null
    private var textureView: GpuTextureView? = null
    private var depthTexture: GpuTexture? = null
    private var depthTextureView: GpuTextureView? = null

    private val projection = Projection()

    private val projectionMatrixBuffer = ProjectionMatrixBuffer("compose-pip-entity")

    private val submitNodeStorage = SubmitNodeStorage()

    /** 原版 [net.minecraft.client.gui.render.pip.PictureInPictureRenderer] 主流程:实体渲染进 PIP 纹理后 blit 提交。
     *  @param pose Compose 布局矩阵(定位实体到实际屏幕位置;原版 GuiEntityRenderState 恒等 pose 无位置信息)。 */
    fun prepare(
        renderState: GuiEntityRenderState,
        featureRenderDispatcher: FeatureRenderDispatcher,
        guiScale: Int,
        color: Int = -1,
        pose: Matrix3x2f = Matrix3x2f(),
    ) {
        val width = (renderState.x1() - renderState.x0()) * guiScale
        val height = (renderState.y1() - renderState.y0()) * guiScale
        val needsResize = texture == null || texture!!.getWidth(0) != width || texture!!.getHeight(0) != height
        if (!needsResize && textureIsReadyToBlit()) {
            blitTexture(renderState, color, pose)
            return
        }
        prepareTexturesAndProjection(needsResize, width, height)
        RenderSystem.outputColorTextureOverride = textureView
        RenderSystem.outputDepthTextureOverride = depthTextureView
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        val poseStack = PoseStack()
        poseStack.translate(width / 2f, getTranslateY(height), 0f)
        // 实体按目标尺寸适配:PIP 纹理 = size,scale = size(实体 1 单位 = 1 格,
        // 与物品 16 单位语义不同,scale 直接取 size 铺满离屏纹理)。
        val scale = guiScale * renderState.scale()
        poseStack.scale(scale, scale, -scale)
        renderToTexture(renderState, poseStack)
        featureRenderDispatcher.renderAllFeatures(submitNodeStorage)
        modelViewStack.popMatrix()
        RenderSystem.outputColorTextureOverride = null
        RenderSystem.outputDepthTextureOverride = null
        blitTexture(renderState, color, pose)
    }

    /** 原版 [net.minecraft.client.gui.render.pip.GuiEntityRenderer.renderToTexture]:UI 光照 + 平移/旋转 + 实体 submit。
     *  注意与 [ComposeOversizedItemRenderer.renderToTexture] 一致,先做 Y 翻转
     *  (scale(1,-1,-1)) —— 离屏纹理经 blit 的 v 坐标(0,1,1,0)翻转后回正,若省略会倒立。
     *  实体模型锚点在脚底(顶点 y ∈ [0, boundingBoxHeight]),prepare 已把 poseStack 平移
     *  到离屏纹理中心 —— 需按模型半高平移使**实体中心**对齐纹理中心,否则脚底在中心、
     *  身体向上超出纹理只能看到下半身。半高平移最后调用(最先作用于顶点:中心 → 原点 →
     *  旋转 → 复原),使 rotation 绕模型中心而非脚底。 */
    private fun renderToTexture(renderState: GuiEntityRenderState, poseStack: PoseStack) {
        poseStack.scale(1f, -1f, -1f)
        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI)
        val translation: Vector3fc = renderState.translation()
        poseStack.translate(translation.x(), translation.y(), translation.z())
        val h = renderState.renderState().boundingBoxHeight
        poseStack.mulPose(renderState.rotation())
        poseStack.translate(0f, -h / 2f, 0f)
        val overriddenCameraAngle: Quaternionfc? = renderState.overrideCameraAngle()
        val cameraRenderState = CameraRenderState()
        if (overriddenCameraAngle != null) {
            cameraRenderState.orientation = overriddenCameraAngle.conjugate(Quaternionf()).rotateY(Math.PI.toFloat())
        }
        Minecraft.getInstance().entityRenderDispatcher.submit(
            renderState.renderState(),
            cameraRenderState,
            0.0,
            0.0,
            0.0,
            poseStack,
            submitNodeStorage as SubmitNodeCollector,
        )
    }

    /** 原版 [net.minecraft.client.gui.render.pip.PictureInPictureRenderer.blitTexture],输出经 [emitElement] 提交到 Compose 渲染器。 */
    private fun blitTexture(renderState: GuiEntityRenderState, color: Int, pose: Matrix3x2f) {
        emitElement(
            BlitRenderState(
                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.singleTexture(textureView!!, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)),
                pose,
                renderState.x0(), renderState.y0(), renderState.x1(), renderState.y1(),
                0f, 1f, 1f, 0f,
                color.premultipliedForPipeline(),
                renderState.scissorArea(),
                null,
            )
        )
    }

    /** 实体渲染状态每帧 fresh(姿态/动画会变),不做跨帧复用 —— 实体总是重绘。 */
    private fun textureIsReadyToBlit(): Boolean = false

    /** 原版 [net.minecraft.client.gui.render.pip.GuiEntityRenderer.getTranslateY](Compose 管线 guiScale 恒 1)。 */
    private fun getTranslateY(height: Int): Float = height / 2f

    private fun prepareTexturesAndProjection(needsResize: Boolean, width: Int, height: Int) {
        if (texture != null && needsResize) {
            texture!!.close()
            texture = null
            textureView!!.close()
            textureView = null
            depthTexture!!.close()
            depthTexture = null
            depthTextureView!!.close()
            depthTextureView = null
        }
        val device: GpuDevice = RenderSystem.getDevice()
        if (texture == null) {
            texture = device.createTexture({ "Compose entity texture" }, 13, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
            textureView = device.createTextureView(texture!!)
            depthTexture = device.createTexture({ "Compose entity depth texture" }, 9, GpuFormat.D32_FLOAT, width, height, 1, 1)
            depthTextureView = device.createTextureView(depthTexture!!)
        }
        device.createCommandEncoder().clearColorAndDepthTextures(texture!!, GuiRenderer.CLEAR_COLOR, depthTexture!!, 0.0)
        projection.setupOrtho(-1000f, 1000f, width.toFloat(), height.toFloat(), true)
        RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC)
    }

    /** 释放全部 GPU 纹理资源(缓存淘汰时调用,防显存泄漏)。 */
    fun close() {
        texture?.let { it.close(); texture = null }
        textureView?.let { it.close(); textureView = null }
        depthTexture?.let { it.close(); depthTexture = null }
        depthTextureView?.let { it.close(); depthTextureView = null }
        projectionMatrixBuffer.close()
    }
}
