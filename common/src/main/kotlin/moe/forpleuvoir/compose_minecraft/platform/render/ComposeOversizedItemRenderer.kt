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
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState
import net.minecraft.client.renderer.texture.OverlayTexture

/**
 * 画中画渲染器(T.37,参照原版 [OversizedItemRenderer]/[PictureInPictureRenderer] 的实现):
 * 渲染 **oversized 物品**(方块等模型超出 16 槽位的 3D 物品)到独立 PIP 纹理,再 blit 到 GUI。
 *
 * 与 [McItemPlugin] 的普通物品(atlas blit)互补:普通物品 → [GuiItemAtlas] 烘焙;
 * oversized 物品 → 本渲染器(带深度/3D 渲染,方块以真实 3D 展示)。
 *
 * 差异:原版 [PictureInPictureRenderer.blitTexture] 提交到原版 [GuiRenderState],
 * 本器经 [emitElement] 回调提交到 Compose 渲染器(1:1 像素管道)。
 */
class ComposeOversizedItemRenderer(
    private val emitElement: (GuiElementRenderState) -> Unit,
) {
    private var texture: GpuTexture? = null
    private var textureView: GpuTextureView? = null
    private var depthTexture: GpuTexture? = null
    private var depthTextureView: GpuTextureView? = null

    private val projection = Projection()

    private val projectionMatrixBuffer = ProjectionMatrixBuffer("compose-pip-item")

    private val submitNodeStorage = SubmitNodeStorage()

    private var modelOnTextureIdentity: Any? = null

    /**
     * 原版 [OversizedItemRenderer.renderToTexture] + [PictureInPictureRenderer.prepare] 主流程:
     * 模型渲染进 PIP 纹理后按 [blitTexture] 提交。
     */
    fun prepare(renderState: OversizedItemRenderState, featureRenderDispatcher: FeatureRenderDispatcher, guiScale: Int, color: Int = -1) {
        val width = (renderState.x1() - renderState.x0()) * guiScale
        val height = (renderState.y1() - renderState.y0()) * guiScale
        val needsResize = texture == null || texture!!.getWidth(0) != width || texture!!.getHeight(0) != height
        if (!needsResize && textureIsReadyToBlit(renderState)) {
            blitTexture(renderState, color)
            return
        }
        prepareTexturesAndProjection(needsResize, width, height)
        RenderSystem.outputColorTextureOverride = textureView
        RenderSystem.outputDepthTextureOverride = depthTextureView
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        val poseStack = PoseStack()
        poseStack.translate(width / 2f, getTranslateY(height, guiScale), 0f)
        // 模型按目标尺寸适配:PIP 纹理 = size,scale = size(模型 16 单位 GUI 视觉 → size 像素)。
        // 原版只对 oversized 用 scale=16 居中大纹理,我们按 size 铺满离屏纹理。
        val scale = guiScale * width.toFloat()
        poseStack.scale(scale, scale, -scale)
        renderToTexture(renderState, poseStack)
        featureRenderDispatcher.renderAllFeatures(submitNodeStorage)
        modelViewStack.popMatrix()
        RenderSystem.outputColorTextureOverride = null
        RenderSystem.outputDepthTextureOverride = null
        blitTexture(renderState, color)
    }

    /** 原版 [OversizedItemRenderer.renderToTexture]:Y 翻转 + 中心偏移 + 模型 submit(flat/3D 光照)。
     *  尺寸以 [OversizedItemRenderState] 的 x0..y1(bounds = 目标 size)为准,不依赖 oversizedItemBounds ——
     *  普通物品 isOversizedInGui=false 时该值 null,不能用作渲染依据。 */
    private fun renderToTexture(renderState: OversizedItemRenderState, poseStack: PoseStack) {
        poseStack.scale(1f, -1f, -1f)
        val guiItemState = renderState.guiItemRenderState()
        val x0 = renderState.x0().toFloat()
        val y0 = renderState.y0().toFloat()
        val x1 = renderState.x1().toFloat()
        val y1 = renderState.y1().toFloat()
        val itemBoundsCenterX = (x0 + x1) / 2f
        val itemBoundsCenterY = (y0 + y1) / 2f
        val slotCenterX = guiItemState.x() + (x1 - x0) / 2f
        val slotCenterY = guiItemState.y() + (y1 - y0) / 2f
        poseStack.translate((slotCenterX - itemBoundsCenterX) / (x1 - x0), (itemBoundsCenterY - slotCenterY) / (x1 - x0), 0f)
        val itemStackRenderState = guiItemState.itemStackRenderState()
        val flat = !itemStackRenderState.usesBlockLight()
        if (flat) {
            Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_FLAT)
        } else {
            Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D)
        }
        itemStackRenderState.submit(poseStack, submitNodeStorage as SubmitNodeCollector, 15728880, OverlayTexture.NO_OVERLAY, 0)
        modelOnTextureIdentity = itemStackRenderState.modelIdentity
    }

    /** 原版 [PictureInPictureRenderer.blitTexture],输出经 [emitElement] 提交到 Compose 渲染器。 */
    private fun blitTexture(renderState: OversizedItemRenderState, color: Int) {
        emitElement(
            BlitRenderState(
                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.singleTexture(textureView!!, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)),
                renderState.pose(),
                renderState.x0(), renderState.y0(), renderState.x1(), renderState.y1(),
                0f, 1f, 1f, 0f,
                color.premultipliedForPipeline(),
                renderState.scissorArea(),
                null,
            )
        )
    }

    /** 原版 [OversizedItemRenderer.textureIsReadyToBlit]:模型不变且非动画时直接复用纹理。 */
    private fun textureIsReadyToBlit(renderState: OversizedItemRenderState): Boolean {
        val itemStackRenderState = renderState.guiItemRenderState().itemStackRenderState()
        return !itemStackRenderState.isAnimated() && itemStackRenderState.modelIdentity == modelOnTextureIdentity
    }

    /** 原版 [OversizedItemRenderer.getTranslateY]。 */
    private fun getTranslateY(height: Int, guiScale: Int): Float = height / 2f

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
            texture = device.createTexture({ "Compose oversized item texture" }, 13, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
            textureView = device.createTextureView(texture!!)
            depthTexture = device.createTexture({ "Compose oversized item depth texture" }, 9, GpuFormat.D32_FLOAT, width, height, 1, 1)
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