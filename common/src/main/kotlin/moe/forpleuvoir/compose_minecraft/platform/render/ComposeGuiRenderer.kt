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
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.state.WindowRenderState
import net.minecraft.client.renderer.state.gui.GlyphRenderState
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import org.joml.Matrix4f
import java.util.ArrayList
import java.util.Optional
import java.util.OptionalDouble
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
 * - 与原版**共存**:Compose 屏打开时本渲染器先提交 Compose 内容,原版 guiRenderer
 *   随后提交 HUD/toasts(盖在 Compose 之上,与原版「Screen 打开时 HUD 可见」一致)。
 *
 * 挂载:extract 阶段由 [MinecraftComposeScene.renderFrame] 填充本收集器;
 * gui 阶段由 [moe.forpleuvoir.compose_minecraft.mixin.GuiRendererMixin] 在
 * 原版 GuiRenderer.render 的 draw() 调用**之后**调用 [render](原版 GUI 画完后
 * Compose 画在最上层;不依赖原版 draws 状态,注入点必触发)。
 */
class ComposeGuiRenderer : GuiCommandSink {

    /** 本帧收集的 GUI 元素(extract 阶段填充,render 提交后清空) */
    private val elements = ArrayList<GuiElementRenderState>()

    /** 本帧收集的文本状态(prepare 阶段转为 GlyphRenderState) */
    private val texts = ArrayList<GuiTextRenderState>()

    /**
     * T.25:吸收另一收集器的元素到**本收集器末尾**(元素顺序在自身之前,用于
     * 「渲染父屏」:父屏内容画在子屏之下),并清空对方 —— 对方收集器不被提交
     * (父屏已 removed,renderer 未注册),每帧被重新填充,不清空会无限累积。
     */
    fun absorbAndClear(other: ComposeGuiRenderer) {
        elements.addAll(other.elements)
        texts.addAll(other.texts)
        other.elements.clear()
        other.texts.clear()
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

    // ── GuiCommandSink ─────────────────────────────────────────

    override fun addElement(element: GuiElementRenderState) {
        elements.add(element)
    }

    override fun addText(text: GuiTextRenderState) {
        texts.add(text)
    }

    // ── 提交(仿原版 GuiRenderer.render:prepare → upload → draw)────

    /**
     * 提交当前帧收集的 Compose 内容。由 [GuiRendererMixin] 在 gui 阶段、
     * 原版 GuiRenderer.render 的 draw() 调用**之后**调用
     * (原版 GUI 画完后 Compose 画在最上层;注入点不依赖原版 draws 状态)。
     */
    fun render() {
        // T.32:自定义字体自愈 —— 资源重载清空 FontManager.fontSets 后重建已注册字体
        // (无注册时 O(1) 空检查,见 MinecraftCustomFonts.ensureAlive)
        MinecraftCustomFonts.ensureAlive()
        if (elements.isEmpty() && texts.isEmpty()) return
        prepare()
        vertexBuffer.upload()
        draw()
        // 帧末清理(对应原版 GuiRenderer.render 的 endDraw/endFrame/clear 段)
        vertexBuffer.endDraw()
        vertexBuffer.endFrame()
        draws.clear()
        elements.clear()
        texts.clear()
    }

    /**
     * 准备阶段:
     * 1. 文本状态 → [GlyphRenderState](复用原版字体渲染,prepareText 语义);
     * 2. 按 pipeline/scissor/textureSetup 分组追加 draw,把顶点写入 [vertexBuffer]。
     *
     * **不做任何重排**:记录顺序即 Compose z 序 —— 阴影由 Modifier 链保证画在
     * 其内容之前、父背景之后(shadow 修饰符在链上更早 = 更底层)。若把阴影
     * 提到"绝对最前",会被场景全屏不透明背景盖住(T.27 实测:阴影 draw 正常
     * 执行但完全不可见,alpha 放大 13 倍/纯红输出均无显示 —— 根因即排序)。
     * T.18 原版路径正常是因为旧 GuiRenderStateMixin 置底"只在节点内生效"。
     */
    private fun prepare() {
        for (text in texts) {
            val pose = text.pose
            val scissor = text.scissor
            // GlyphVisitor 是 MC Java 嵌套接口,Kotlin 无 SAM 构造器,用匿名对象
            text.ensurePrepared().visit(object : Font.GlyphVisitor {
                override fun acceptRenderable(renderable: TextRenderable) {
                    addElement(GlyphRenderState(pose, renderable, scissor))
                }
            })
        }
        texts.clear()

        previousScissorArea = null
        previousPipeline = null
        previousTextureSetup = null
        previousDraw = null
        for (elementState in elements) {
            addElementToMesh(elementState)
        }
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
        // guiscale 投影,避免影响其后可能存在的原版 GUI 段(当前注入点在原版
        // GUI 之后,无后续段,恢复为防御性保留)。
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
        /**
         * 当前打开的 Compose 屏渲染器([ComposeScreen] init/removed 维护,渲染线程读写)。
         * [GuiRendererMixin] 在 GuiRenderer.draw 的 HUD 段之前读取:非 null 时先提交
         * Compose 内容(并恢复原版投影),原版 guiRenderer 随后画 HUD。
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
