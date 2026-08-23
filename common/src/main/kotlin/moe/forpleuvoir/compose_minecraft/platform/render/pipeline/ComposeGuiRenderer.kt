package moe.forpleuvoir.compose_minecraft.platform.render.pipeline
import moe.forpleuvoir.compose_minecraft.mc

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexConsumer
import moe.forpleuvoir.compose_minecraft.platform.render.text.GlyphAtlas
import moe.forpleuvoir.compose_minecraft.platform.render.text.GlyphCache
import moe.forpleuvoir.compose_minecraft.platform.render.text.GuiGlyphRenderState
import moe.forpleuvoir.compose_minecraft.platform.render.text.MinecraftGuiText
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
import net.minecraft.client.renderer.state.WindowRenderState
import net.minecraft.client.renderer.state.gui.GlyphRenderState
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.client.renderer.state.gui.GuiItemRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.joml.Matrix4f
import org.joml.Vector2f
import moe.forpleuvoir.compose_minecraft.platform.render.state.ItemRenderState
import moe.forpleuvoir.compose_minecraft.platform.render.pip.EntityPipRenderState
import moe.forpleuvoir.compose_minecraft.platform.render.pip.ComposeOversizedItemRenderer
import moe.forpleuvoir.compose_minecraft.platform.render.pip.ComposeOversizedEntityRenderer
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
     * 追加一个实体渲染状态(T.37):包装原版 [GuiEntityRenderState] + 色调色,
     * 由渲染器在 prepare 阶段经 [ComposeOversizedEntityRenderer] 离屏 PIP 渲染。
     * 默认空实现(不支持实体的 sink 忽略)。
     */
    fun addEntity(entity: EntityPipRenderState) = Unit

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

    /** 单个有序命令:元素/文本/物品/实体画中画/画中画五选一 */
    private class Item(
        val element: GuiElementRenderState? = null,
        val text: GuiTextRenderState? = null,
        val itemState: ItemRenderState? = null,
        val entityState: EntityPipRenderState? = null,
        val pipState: PictureInPictureRenderState? = null,
    )

    /**
     * T.25:吸收另一收集器的命令到**本收集器末尾**(元素顺序在自身之前,用于
     * 「渲染父屏」:父屏内容画在子屏之下),并清空对方 —— 对方收集器不被提交
     * (父屏已 removed,renderer 未注册),每帧被重新填充,不清空会无限累积。
     *
     * P3②:吸收前先落盘双方开启中的文本批次 —— 父屏批次必须先于本屏既有
     * 元素之后、被吸收内容之前定序,否则跨渲染器的连续同 key run 会错序。
     */
    fun absorbAndClear(other: ComposeGuiRenderer) {
        flushTextBatch()
        other.flushTextBatch()
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

    /**
     * 实体离屏(PIP)渲染器缓存:按稳定 key([EntityPipRenderState.identityKey],如实体实例标识)
     * 各持独立纹理,同一实体跨帧复用;LRU 上限与物品 PIP 缓存共享 [PIP_RENDERER_CACHE_LIMIT],
     * 淘汰时 [ComposeOversizedEntityRenderer.close] 释放纹理。
     */
    private val entityPipRenderers = object : LinkedHashMap<Any, ComposeOversizedEntityRenderer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, ComposeOversizedEntityRenderer>?): Boolean {
            if (size <= PIP_RENDERER_CACHE_LIMIT) return false
            eldest?.value?.close()
            return true
        }
    }

    // ── P3② 文本批次合并状态 ─────────────────────────────────────────────
    //
    // 目标:同页同 scissor 的**连续** gui_text run 合并为单次提交,消掉每 run 的
    // 元素对象/数组拷贝/bounds 计算等固定开销。层级安全设计(用户拍板:我们是
    // Compose UI 树,z 序 = 记录顺序,**绝不做原版 sortElements 式重排**):
    // - 合并仅发生在记录顺序中相邻的 run 之间 —— flush-on-key-change:
    //   (page, scissor) 变化、或任何非文本元素插入,立即落盘再开新批;
    // - 落盘元素按开启批时序 append 进 [items],所有 quad 的绘制顺序与逐元素
    //   提交完全一致,SrcOver 混合结果不变 → 层级零变化;
    // - 缓冲为原始数组 + 游标,容量跨帧复用(稳态零分配)。

    /** 是否有开启中的文本批 */
    private var textBatchOpen = false

    /** 开启批的图集页(纹理绑定 key) */
    private var textBatchPage = -1

    /** 开启批的 scissor(裁剪 key;ScreenRectangle 值相等语义) */
    private var textBatchScissor: ScreenRectangle? = null

    /** 交错 [x, y, u, v] 平铺缓冲(**世界坐标**,追加时已按各自 pose 预变换) */
    private var textBatchVertices = FloatArray(2048)

    /** 每顶点 0xAARRGGBB 缓冲 */
    private var textBatchColors = IntArray(512)

    private var textBatchVCount = 0
    private var textBatchCCount = 0

    /** pose 预变换 scratch(渲染线程单线程复用;与原版 addVertexWith2DPose 同一实现) */
    private val transformVec2 = Vector2f()

    // ── GuiCommandSink ─────────────────────────────────────────

    override fun addElement(element: GuiElementRenderState) {
        if (element is GuiGlyphRenderState) {
            val scissor = element.scissorArea()
            if (!textBatchOpen ||
                element.pageIndex != textBatchPage ||
                scissorChanged(scissor, textBatchScissor)
            ) {
                flushTextBatch()
                textBatchOpen = true
                textBatchPage = element.pageIndex
                textBatchScissor = scissor
            }
            appendTextElement(element)
            return
        }
        // 非文本元素是天然的批次屏障:先落盘,保住其前后内容的相对顺序
        flushTextBatch()
        items.add(Item(element = element))
    }

    override fun addText(text: GuiTextRenderState) {
        flushTextBatch()
        items.add(Item(text = text))
    }

    override fun addItem(item: ItemRenderState) {
        flushTextBatch()
        items.add(Item(itemState = item))
    }

    override fun addEntity(entity: EntityPipRenderState) {
        flushTextBatch()
        items.add(Item(entityState = entity))
    }

    override fun addPicturesInPictureState(state: PictureInPictureRenderState) {
        flushTextBatch()
        items.add(Item(pipState = state))
    }

    /**
     * 追加一个 [GuiGlyphRenderState] 的 quad 到开启批:局部坐标经该元素自带
     * pose 预变换为世界坐标(与原版 `addVertexWith2DPose` 内部同为
     * `Matrix3x2fc.transformPosition`,恒等替换),UV/顶点色原样拷贝。
     */
    private fun appendTextElement(element: GuiGlyphRenderState) {
        val src = element.vertices
        val srcColors = element.colors
        val needV = textBatchVCount + src.size
        val needC = textBatchCCount + srcColors.size
        if (textBatchVertices.size < needV) {
            textBatchVertices = textBatchVertices.copyOf(maxOf(needV, textBatchVertices.size * 2))
        }
        if (textBatchColors.size < needC) {
            textBatchColors = textBatchColors.copyOf(maxOf(needC, textBatchColors.size * 2))
        }
        val pose = element.pose
        var si = 0 // 源顶点游标(x,y,u,v)
        var vi = 0 // 源顶点序号(取色)
        var di = textBatchVCount
        var ci = textBatchCCount
        while (si + 4 <= src.size) {
            pose.transformPosition(src[si], src[si + 1], transformVec2)
            textBatchVertices[di] = transformVec2.x
            textBatchVertices[di + 1] = transformVec2.y
            textBatchVertices[di + 2] = src[si + 2]
            textBatchVertices[di + 3] = src[si + 3]
            textBatchColors[ci] = srcColors[vi]
            vi++
            di += 4
            ci++
            si += 4
        }
        textBatchVCount = needV
        textBatchCCount = needC
    }

    /**
     * 落盘开启中的文本批:整段拷贝为精确长度数组,作为单个合并元素按当前
     * 记录位置 append 进 [items]。key 复位,缓冲容量保留跨帧复用。
     */
    private fun flushTextBatch() {
        if (!textBatchOpen) return
        val page = textBatchPage
        val scissor = textBatchScissor
        textBatchOpen = false
        textBatchPage = -1
        textBatchScissor = null
        if (textBatchVCount == 0) return
        items.add(
            Item(
                element = MergedTextRenderState(
                    vertices = textBatchVertices.copyOf(textBatchVCount),
                    colors = textBatchColors.copyOf(textBatchCCount),
                    scissor = scissor,
                    pageIndex = page,
                ),
            )
        )
        textBatchVCount = 0
        textBatchCCount = 0
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
        // P3① 图集 LRU 时钟 + 活跃页水位淘汰(淘汰页本帧不再分配,
        // 游标在帧末 flushRetiredPages 重置复用)
        GlyphCache.onFrameStart()
        // P3②:收集阶段(extract)开启的文本批在本帧 prepare 前落盘
        flushTextBatch()
        if (items.isEmpty()) {
            GlyphAtlas.flushRetiredPages()
            return
        }
        prepare()
        vertexBuffer.upload()
        draw()
        // 帧末清理(对应原版 GuiRenderer.render 的 endDraw/endFrame/clear 段)
        vertexBuffer.endDraw()
        vertexBuffer.endFrame()
        draws.clear()
        items.clear()
        // P3①:draw 完成后重置已退役页游标 —— 此前本帧元素仍持有旧槽位 UV,
        // 提前重置会让下一帧收集阶段覆盖其内容造成花屏
        GlyphAtlas.flushRetiredPages()
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
                item.itemState != null -> prepareItem(item.itemState, mc)
                item.entityState != null -> prepareEntity(item.entityState, mc)
                item.pipState != null -> { /* 其他 PIP 类型暂不处理 */ }
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

    /**
     * 实体离屏渲染:每个实体实例(identityKey)一个独立 renderer/纹理
     * (防止同帧多个实体实例互相覆盖纹理),色调色经 [EntityPipRenderState.color] 传入。
     */
    private fun prepareEntity(entry: EntityPipRenderState, mc: Minecraft) {
        val renderer = entityPipRenderers.getOrPut(entry.identityKey) {
            ComposeOversizedEntityRenderer { addElementToMesh(it) }
        }
        renderer.prepare(entry.state, mc.gameRenderer.featureRenderDispatcher(), 1, entry.color, entry.pose)
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
        val windowState = mc.gameRenderer.gameRenderState().windowRenderState
        // 1:1 像素投影(不除 guiScale)
        guiProjection.setupOrtho(1000.0f, 11000.0f, windowState.width.toFloat(), windowState.height.toFloat(), true)
        RenderSystem.setProjectionMatrix(guiProjectionMatrixBuffer.getBuffer(guiProjection), ProjectionType.ORTHOGRAPHIC)
        val mainRenderTarget = mc.gameRenderer.mainRenderTarget()
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
        val window = mc.gameRenderer.gameRenderState().windowRenderState
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

    /**
     * P3② 批次合并产物:(page, scissor) 相同的连续 gui_text run 合并为单个
     * 提交元素。顶点已是**世界坐标**(追加时按各自 pose 预变换,与
     * `addVertexWith2DPose` 逐位一致),buildVertices 直接以恒等位姿写出。
     * bounds = 世界包围盒与 scissor 求交(对齐 [GuiGlyphRenderState] 语义)。
     */
    private class MergedTextRenderState(
        private val vertices: FloatArray,
        private val colors: IntArray,
        private val scissor: ScreenRectangle?,
        private val pageIndex: Int,
    ) : GuiElementRenderState {

        override fun pipeline(): RenderPipeline = MinecraftGuiText.pipeline

        override fun textureSetup(): TextureSetup = GlyphAtlas.textureSetup(pageIndex)

        override fun scissorArea(): ScreenRectangle? = scissor

        override fun buildVertices(vertexConsumer: VertexConsumer) {
            var i = 0
            var vi = 0
            while (i + 4 <= vertices.size) {
                vertexConsumer
                    .addVertex(vertices[i], vertices[i + 1], 0.0f)
                    .setUv(vertices[i + 2], vertices[i + 3])
                    .setColor(colors[vi])
                vi++
                i += 4
            }
        }

        override fun bounds(): ScreenRectangle {
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
            // 世界坐标无需再变换;floor/ceil 保守取整后与 scissor 求交
            val world = ScreenRectangle(
                kotlin.math.floor(minX).toInt(),
                kotlin.math.floor(minY).toInt(),
                kotlin.math.ceil(maxX - minX).toInt(),
                kotlin.math.ceil(maxY - minY).toInt(),
            )
            return scissor?.intersection(world) ?: world
        }
    }

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
