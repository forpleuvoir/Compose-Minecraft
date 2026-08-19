package moe.forpleuvoir.compose_minecraft.platform.render

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import moe.forpleuvoir.compose_minecraft.platform.ui.text.MinecraftCustomFonts
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.font.TextRenderable
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import net.minecraft.client.renderer.state.WindowRenderState
import net.minecraft.client.renderer.state.gui.GlyphRenderState
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.client.renderer.state.gui.GuiItemRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.joml.Matrix3x2f
import org.joml.Matrix4f
import java.util.*
import kotlin.math.max
import kotlin.math.min


/**
 * Compose 命令收集器:渲染上下文只向 sink 追加渲染状态,不接触 MC 的 [net.minecraft.client.renderer.state.gui.GuiRenderState]。
 *
 * 由 [MinecraftRenderContext] 在 extract 阶段填充(每帧),[ComposeGuiRenderer.render] 在 gui 阶段提交。
 */
interface GuiCommandSink {
    fun addElement(element: GuiElementRenderState)

    fun addText(text: GuiTextRenderState)

    /**
     * 追加一个物品渲染状态(T.37):与 [GuiRenderState.addItem] 对齐 ——
     * 由渲染器在 prepare 阶段统一经 [GuiItemAtlas] 烘焙后转为 blit 提交。
     * 默认空实现(不支持物品的 sink 忽略)。
     */
    fun addItem(item: ItemRenderState) = Unit

    /**
     * 追加一个画中画渲染状态(T.37,原版 [GuiRenderState.addPicturesInPictureState]):
     * 实体预览(GuiEntityRenderState)、oversized 物品等 PIP 内容。
     * 默认空实现(不支持 PIP 的 sink 忽略)。
     */
    fun addPicturesInPictureState(state: PictureInPictureRenderState) = Unit
}

/**
 * 独立的 Compose GUI 渲染器(T.24):与 MC 原版 [net.minecraft.client.renderer.GuiRenderer] 平级,
 * 但拥有完全独立的上下文,解决原版 GUI 渲染链路的两个精度问题:
 *
 * 1. **裁剪精度丢失**:原版 [net.minecraft.client.renderer.GuiRenderer] 的 enableScissor 把
 *    GUI 单位 scissor × guiScale 后 `(int)` 截断 —— 裁剪边界并非实际像素;
 * 2. **非像素投影**:原版投影 = `窗口 / guiScale`(GUI 单位),场景坐标是缩放后的值。
 *
 * 本渲染器:
 * - 投影 = `setupOrtho(1000, 11000, window.width, window.height, true)` —— **1:1 像素**,
 *   场景尺寸 = 窗口像素,1dp == 1px;
 * - scissor 像素级直接提交(不乘 guiScale、不截断),钳制到窗口避免 RenderPass 越界 IAE;
 * - 排序自管:阴影命令最先(最底),其余保持 Compose 记录顺序(z 序),不再需要
 *   原 GuiRenderStateMixin 的排序 hack(该 mixin 已随独立工作流删除);
 * - 文本复用原版字体渲染:GuiTextRenderState.ensurePrepared() → GlyphRenderState;
 * - 与原版共存:Compose 屏打开时本渲染器先提交 Compose 内容(Compose 先画),
 *   原版 guiRenderer 随后绘制 —— F3 调试覆盖层(debug overlay)是原版
 *   Gui.extractRenderState 中独立调用,以最后一个 stratum 画在 Compose 之上
 *   (FPS 信息最上可见);原版 HUD/toast 提取逻辑完全不变。
 *
 * 挂载:extract 阶段由 [MinecraftComposeScene.renderFrame] 填充本收集器;
 * gui 阶段由 [moe.forpleuvoir.compose_minecraft.mixin.GuiRendererMixin] 在
 * 原版 GuiRenderer.render 的 draw() 调用**之前**调用 [render](Compose 先画,
 * 原版随后绘制,F3 调试覆盖层盖在 Compose 之上;不依赖原版 draws 状态,
 * 注入点必触发)。
 */
class ComposeGuiRenderer : GuiCommandSink {

    /**
     * 本帧收集的 GUI 命令(**按记录顺序交错**)—— 保持 Compose z 序:
     * 元素/文本/物品/画中画全部交错记录,prepare 时按记录顺序逐条处理,
     * 不做任何分列批量渲染(否则会穿透上方元素,如 Dialog scrim)。
     */
    private val items = ArrayList<Item>()

    /** 单个有序命令:元素/文本/物品/画中画四选一 */
    private class Item(
        val element: GuiElementRenderState? = null,
        val text: GuiTextRenderState? = null,
        val itemState: ItemRenderState? = null,
        val pipState: PictureInPictureRenderState? = null,
    )

    /** 画中画状态(实体预览等);本版先收集,PIP renderer 机制待接入 */

    /**
     * T.25:吸收另一收集器的命令到**本收集器末尾**(元素顺序在自身之前,用于
     * 「渲染父屏」:父屏内容画在子屏之下),并清空对方 —— 对方收集器不被提交
     * (父屏已 removed,renderer 未注册),每帧被重新填充,不清空会无限累积。
     */
    fun absorbAndClear(other: ComposeGuiRenderer) {
        items.addAll(other.items)
        other.items.clear()
    }

    /** 分组后的 draw 列表(同 pipeline/scissor/texture 合并) */
    private val draws = ArrayList<Draw>()

    /** 顶点缓冲(与原版 GuiRenderer 同构:staging → GPU 池,容量 786432 字节) */
    private val vertexBuffer = StagedVertexBuffer({ "Compose GUI Vertex Buffer" }, 786432)

    private val guiProjection = Projection()

    private val guiProjectionMatrixBuffer = ProjectionMatrixBuffer("compose-gui")

    private var previousScissorArea: ScreenRectangle? = null
    private var previousPipeline: RenderPipeline? = null
    private var previousTextureSetup: TextureSetup? = null
    private var previousDraw: StagedVertexBuffer.Draw? = null

    /**
     * 物品离屏(PIP)渲染器缓存:按稳定 key([ItemRenderState.identityKey],如 Item 单例)各持独立纹理,
     * 同一物品跨帧复用;LRU 上限 [PIP_RENDERER_CACHE_LIMIT],淘汰时 [ComposeOversizedItemRenderer.close]
     * 释放纹理,防显存泄漏。
     */
    private val itemPipRenderers = object : LinkedHashMap<Any, ComposeOversizedItemRenderer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, ComposeOversizedItemRenderer>?): Boolean {
            if (size <= PIP_RENDERER_CACHE_LIMIT) return false
            eldest?.value?.close()
            return true
        }
    }

    // ── GuiCommandSink ─────────────────────────────────────────

    override fun addElement(element: GuiElementRenderState) {
        items.add(Item(element = element))
    }

    override fun addText(text: GuiTextRenderState) {
        items.add(Item(text = text))
    }

    override fun addItem(item: ItemRenderState) {
        items.add(Item(itemState = item))
    }

    override fun addPicturesInPictureState(state: PictureInPictureRenderState) {
        items.add(Item(pipState = state))
    }

    // ── 提交(仿原版 GuiRenderer.render:prepare → upload → draw)────

    /**
     * 提交当前帧收集的 Compose 内容。由 [GuiRendererMixin] 在 gui 阶段、
     * 原版 GuiRenderer.render 的 draw() 调用**之前**调用
     * (Compose 先画,原版随后绘制,F3 调试覆盖层盖在 Compose 之上;
     * 注入点不依赖原版 draws 状态)。
     */
    fun render() {
        // T.32:自定义字体自愈 —— 资源重载清空 FontManager.fontSets 后重建已注册字体
        // (无注册时 O(1) 空检查,见 MinecraftCustomFonts.ensureAlive)
        MinecraftCustomFonts.ensureAlive()
        if (items.isEmpty()) return
        prepare()
        vertexBuffer.upload()
        draw()
        // 帧末清理(对应原版 GuiRenderer.render 的 endDraw/endFrame/clear 段)
        vertexBuffer.endDraw()
        vertexBuffer.endFrame()
        draws.clear()
        items.clear()
    }

    /**
     * 准备阶段:按**记录顺序**逐条处理本帧命令:
     * - 元素 → 直接追加到 mesh(按 pipeline/scissor/textureSetup 分组);
     * - 文本 → [GuiTextRenderState] 立即 [GlyphRenderState] 化(复用原版字体渲染)
     *   并就地追加到 mesh;
     * - 物品 → 离屏 PIP 渲染(按 [ItemRenderState.size])→ blit 追加到 mesh;
     * - 画中画 → 待接入专用 renderer。
     *
     * **不做任何重排**:记录顺序即 Compose z 序。物品/画中画必须与元素/文本交错,
     * 否则会穿透上方元素(如 Popup 盖不住物品)。
     */
    private fun prepare() {
        previousScissorArea = null
        previousPipeline = null
        previousTextureSetup = null
        previousDraw = null
        for (item in items) {
            when {
                item.element != null -> addElementToMesh(item.element)
                item.text != null -> {
                    val text = item.text
                    val pose = text.pose
                    val scissor = text.scissor
                    text.ensurePrepared().visit(object : Font.GlyphVisitor {
                        override fun acceptRenderable(renderable: TextRenderable) {
                            addElementToMesh(GlyphRenderState(pose, renderable, scissor))
                        }
                    })
                }
                item.itemState != null -> prepareItem(item.itemState, Minecraft.getInstance())
                item.pipState != null -> { /* TODO: PIP renderer 机制待接入 */ }
            }
        }
    }

    /**
     * 物品渲染:所有物品走离屏 PIP([ComposeOversizedItemRenderer] 原 OversizedItemRenderer 机制),
     * 每个物品按 [ItemRenderState.size] 渲染到独立纹理再 blit —— 内容分辨率 = 目标尺寸,
     * 不写死 16×16,支持任意 size/动画。画在 Compose 内容之上(原版 item 同批次语义)。
     */

    /** 单物品离屏渲染:每个模型 identity 一个独立 renderer/纹理(防止同帧互相覆盖)。 */
    private fun prepareItem(entry: ItemRenderState, mc: Minecraft) {
        val renderer = itemPipRenderers.getOrPut(entry.itemStackRenderState.modelIdentity) {
            ComposeOversizedItemRenderer { addElementToMesh(it) }
        }
        renderer.prepare(
            OversizedItemRenderState(
                GuiItemRenderState(
                    entry.pose,
                    entry.itemStackRenderState,
                    entry.x,
                    entry.y,
                    entry.scissorArea,
                ),
                entry.x,
                entry.y,
                entry.x + entry.size.width,
                entry.y + entry.size.height,
            ),
            mc.gameRenderer.featureRenderDispatcher(),
            1,
            entry.color,
        )
    }

    private fun addElementToMesh(elementState: GuiElementRenderState) {
        val pipeline = elementState.pipeline()
        val textureSetup = elementState.textureSetup()
        val scissorArea = elementState.scissorArea()
        if (previousDraw == null
            || pipeline !== previousPipeline
            || scissorChanged(scissorArea, previousScissorArea)
            || textureSetup != previousTextureSetup
        ) {
            previousPipeline = pipeline
            previousTextureSetup = textureSetup
            previousScissorArea = scissorArea
            previousDraw = vertexBuffer.appendDraw(
                pipeline.getVertexFormatBinding(0)!!,
                pipeline.getPrimitiveTopology(),
            )
            draws.add(Draw(previousDraw!!, pipeline, textureSetup, scissorArea))
        }
        elementState.buildVertices(vertexBuffer.getVertexBuilder(previousDraw!!))
    }

    private fun scissorChanged(newScissor: ScreenRectangle?, oldScissor: ScreenRectangle?): Boolean {
        if (newScissor === oldScissor) return false
        return if (newScissor != null) newScissor != oldScissor else true
    }

    /** 绘制阶段:像素正交投影 + 绑定主渲染目标 + 逐 draw 提交(照抄原版 executeDrawRange/executeDraw)。 */
    private fun draw() {
        if (draws.isEmpty()) return
        val windowState = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState
        // 1:1 像素投影(不除 guiScale)
        guiProjection.setupOrtho(1000.0f, 11000.0f, windowState.width.toFloat(), windowState.height.toFloat(), true)
        RenderSystem.setProjectionMatrix(guiProjectionMatrixBuffer.getBuffer(guiProjection), ProjectionType.ORTHOGRAPHIC)
        val mainRenderTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(Matrix4f().setTranslation(0.0f, 0.0f, -11000.0f))
        RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(
                { "Compose GUI" },
                mainRenderTarget.getColorTextureView()!!,
                Optional.empty(),
                if (mainRenderTarget.useDepth) mainRenderTarget.getDepthTextureView()!! else null,
                OptionalDouble.empty(),
            )
            .use { renderPass ->
                RenderSystem.bindDefaultUniforms(renderPass)
                renderPass.setUniform("DynamicTransforms", dynamicTransforms)
                for (drawState in draws) {
                    executeDraw(drawState, renderPass)
                }
            }
        // T.24 修复:setProjectionMatrix 是全局渲染状态。Compose 画完后恢复原版
        // guiscale 投影,避免影响随后的原版 GUI 段(draw() 会重设自身投影,
        // 此处恢复为防御性保留,保证后续原版 HUD/F3 段投影正确)。
        restoreVanillaProjection(windowState)
    }

    /** 恢复原版 GUI 的 guiscale 正交投影(width/guiScale),供后续 HUD(after-blur)段使用。 */
    private fun restoreVanillaProjection(windowState: WindowRenderState) {
        val guiScale = windowState.guiScale.toFloat().coerceAtLeast(1f)
        guiProjection.setupOrtho(1000.0f, 11000.0f, windowState.width.toFloat() / guiScale, windowState.height.toFloat() / guiScale, true)
        RenderSystem.setProjectionMatrix(guiProjectionMatrixBuffer.getBuffer(guiProjection), ProjectionType.ORTHOGRAPHIC)
    }

    private fun executeDraw(drawState: Draw, renderPass: RenderPass) {
        val executeInfo = vertexBuffer.getExecuteInfo(drawState.draw) ?: return
        val pipeline = drawState.pipeline
        renderPass.setPipeline(pipeline)
        renderPass.setVertexBuffer(0, executeInfo.vertexBuffer().slice())
        val scissorArea = drawState.scissorArea
        if (scissorArea != null) {
            enableScissor(scissorArea, renderPass)
        } else {
            renderPass.disableScissor()
        }
        val textureSetup = drawState.textureSetup
        if (textureSetup.texure0() != null) {
            renderPass.bindTexture("Sampler0", textureSetup.texure0(), textureSetup.sampler0())
        }
        if (textureSetup.texure1() != null) {
            renderPass.bindTexture("Sampler1", textureSetup.texure1(), textureSetup.sampler1())
        }
        if (textureSetup.texure2() != null) {
            renderPass.bindTexture("Sampler2", textureSetup.texure2(), textureSetup.sampler2())
        }
        renderPass.setIndexBuffer(executeInfo.indexBuffer(), executeInfo.indexType())
        renderPass.drawIndexed(executeInfo.indexCount(), 1, executeInfo.firstIndex(), executeInfo.baseVertex(), 0)
    }

    /**
     * 像素级 scissor 直传(不乘 guiScale、不截断)。
     *
     * MC 的 RenderPass.enableScissor 要求 scissor 落在 renderArea(全屏)内,否则抛 IAE,
     * 因此先钳制到窗口;退化(空/越界)直接 disableScissor。
     * y 轴翻转:MC scissor 原点在左上,enableScissor 的 y 以纹理底部为原点。
     */
    private fun enableScissor(rectangle: ScreenRectangle, renderPass: RenderPass) {
        val window = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState
        val left = max(0, min(rectangle.left(), window.width))
        val top = max(0, min(rectangle.top(), window.height))
        val right = max(0, min(rectangle.right(), window.width))
        val bottom = max(0, min(rectangle.bottom(), window.height))
        if (right <= left || bottom <= top) {
            renderPass.disableScissor()
            return
        }
        renderPass.enableScissor(left, window.height - bottom, right - left, bottom - top)
    }

    /** 一个分组 draw:pipeline/scissor/textureSetup 与顶点缓冲 draw 的绑定 */
    private class Draw(
        val draw: StagedVertexBuffer.Draw,
        val pipeline: RenderPipeline,
        val textureSetup: TextureSetup,
        val scissorArea: ScreenRectangle?,
    )

    companion object {
        /** 物品离屏渲染器缓存上限(LRU 淘汰,防显存泄漏) */
        private const val PIP_RENDERER_CACHE_LIMIT = 64

        /**
         * 当前打开的 Compose 屏渲染器([ComposeScreen] init/removed 维护,渲染线程读写)。
         * [moe.forpleuvoir.compose_minecraft.mixin.GuiRendererMixin] 在 GuiRenderer.render
         * 的 draw() 调用之前读取:非 null 时先提交 Compose 内容(并恢复原版投影),
         * 原版 guiRenderer 随后绘制 —— F3 调试覆盖层画在 Compose 之上。
         */
        @JvmStatic
        var active: ComposeGuiRenderer? = null
            private set

        @JvmStatic
        fun register(renderer: ComposeGuiRenderer) {
            active = renderer
        }

        @JvmStatic
        fun unregister(renderer: ComposeGuiRenderer) {
            if (active === renderer) active = null
        }
    }
}

/**
 * 将调制色预乘 alpha(T.37):物品图集走 [RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA]
 * 预乘 alpha 管线,顶点色作为采样结果乘数,RGB 必须与 alpha 同步缩放 —— 否则单独降 alpha
 * 会发白(覆盖率下降但颜色贡献未降)。alpha == 0xFF 时恒等(不影响纯白/不透明染色)。
 */
internal fun Int.premultipliedForPipeline(): Int {
    val a = (this ushr 24) and 0xFF
    if (a == 0xFF) return this
    val r = ((this ushr 16) and 0xFF) * a / 255
    val g = ((this ushr 8) and 0xFF) * a / 255
    val b = (this and 0xFF) * a / 255
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
