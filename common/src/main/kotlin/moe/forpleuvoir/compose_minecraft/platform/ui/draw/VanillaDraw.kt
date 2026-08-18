package moe.forpleuvoir.compose_minecraft.platform.ui.draw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import com.mojang.blaze3d.pipeline.RenderPipeline
import moe.forpleuvoir.compose_minecraft.platform.render.GuiCommandSink
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import org.joml.Matrix3x2f
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * 绘制修饰符扩展(T.38):在 `DrawScope` 绘制阶段访问「1:1 像素投影的原生 GUI 绘制桥」。
 *
 * `Modifier.vanillaDraw` 让业务方在绘制修饰符内通过 [VanillaGuiGraphics]
 * 使用**与原版 [net.minecraft.client.gui.GuiGraphicsExtractor] 形态一致的
 * 渲染 API**(`fill` / `fillGradient` / `text` / `enableScissor` 等)。
 *
 * ## 渲染层级与空间(重要)
 * vanillaDraw 的内容**不进入原版 GuiRenderState**,而是由 [VanillaGuiGraphics]
 * 转译成与后端完全相同的 element 类型(`ColoredRectangleRenderState` /
 * `GuiTextRenderState` 等),经 [GuiCommandSink] 注入
 * [moe.forpleuvoir.compose_minecraft.platform.render.ComposeGuiRenderer] 的
 * items 列表**头部**(先于本帧 Compose 内容收集),由 Compose 的 **1:1 像素投影
 * 渲染通道**(`setupOrtho(窗口宽, 窗口高)` + 像素级裁剪)与 Compose 内容同一次
 * 提交绘制:
 * - **完全不受原版 guiScale 影响** —— 与 Compose 场景同坐标系(窗口像素 1:1),
 *   文字 9px、矩形像素级对齐,不依赖窗口 GUI 缩放;
 * - 位于本场景所有 Compose 内容**之下**(列表头部先画,Compose 后画盖在上面):
 *   Compose 不透明处完全覆盖、透明/半透明处可见(像"垫在 Compose 下的底图");
 * - 与原版 GUI(HUD/toast)无关:不写入原版 GuiRenderState,不参与原版分段;
 *   F3 调试覆盖层(after-blur 段)仍在本场景之上。
 *
 * ## 为什么不能直接使用原版 GuiGraphicsExtractor
 * 原版 [net.minecraft.client.gui.GuiGraphicsExtractor] 由 Minecraft 每帧构造、
 * 经 `Screen.extractRenderState(graphics)` 传入,它把元素写入**原版
 * GuiRenderState**,由原版 GuiRenderer 以 **GUI 投影**(`窗口 / guiScale`)绘制
 * —— 内容必然整体放大 guiScale 倍,与 1:1 的 Compose 内容无法像素对齐。
 * 本平台 Compose 后端(ComposeGuiRenderer)不经过 GuiGraphicsExtractor——
 * 它自建收集器并自行设置 1:1 像素投影,这正是 Compose 内容不受 guiScale
 * 影响的原因。因此 `vanillaDraw` 提供同形态 API 的翻译层而非复用原版
 * extractor 实例。
 *
 * ## 坐标换算
 * 场景尺寸 = 窗口像素(T.24 1:1,1dp == 1 像素);本平台的裁剪与投射均为
 * **像素级 1:1**。因此换算为「本地坐标 + 节点场景偏移
 * (`LayoutCoordinates.positionInRoot()`,**包含 graphicsLayer 图层位移**)→
 * 窗口像素」后四舍五入取整,直接作为像素坐标传入 [VanillaGuiGraphics]:
 * ```
 * Modifier.vanillaDraw {
 *     val r = guiRect(0f, 0f, size.width, size.height)   // 本节点整块区域
 *     fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0x40FF0000.toInt())
 *     text(Minecraft.getInstance().font, "vanilla!", r.left.roundToInt(), r.top.roundToInt(), 0xFFFFFFFF.toInt())
 * }
 * ```
 * 图形图层(graphicsLayer)的 **translationX/Y、rotation、scale 变换不在
 * 换算内** —— 原生 GUI API 是轴对齐整数矩形,无法表达仿射变换。请在未做
 * graphicsLayer 变换的子树中使用 vanillaDraw。
 *
 * ## 帧生命周期
 * 桥接绘制由 renderFrame 在 scene.render 之后**每帧执行**(绕开 Compose
 * 图层脏标记缓存,见 [VanillaDrawState] KDoc),几何取节点最近一次 draw
 * 快照。绘制阶段之外回调整体跳过。
 *
 * Popup/Dialog 图层内:场景偏移以弹层自身原点为基准(未叠加弹层在窗口中的位移),
 * 如弹层内需要精确窗口坐标请自行叠加弹层位置。
 *
 * ## 弃用说明(2025-08,T.38 实测后判定)
 * 本 API **已弃用,保留兼容、不再演进**:
 * - **层级缺陷(弃用主因)**:所有 vanilla 元素经 [VanillaGuiGraphics] 注入
 *   ComposeGuiRenderer.items **头部**,固定垫全局最底 —— 弹层(Popup/Dialog)
 *   内的 vanilla 同样垫底,会被主场景的不透明 Compose 内容盖住,无法按
 *   图层/节点级控制 z 序(实测遮挡矩阵确认此行为);
 * - **API 完整性不足**:1:1 桥是翻译层,不支持 `blitSprite` / `item` /
 *   `tooltip` 等原版高级 API;
 * - 需要**完整原版 API 或节点级层级**时,请直接使用原版
 *   [net.minecraft.client.gui.GuiGraphicsExtractor](`Screen.extractRenderState`
 *   参数)自行转译;需要与 Compose 同空间的 1:1 像素绘制时,请自行实现
 *   GuiCommandSink 注入(参考本文件桥的实现)。
 *
 * @param onDraw 绘制回调:每帧在 renderFrame 内执行,可访问 [VanillaDrawScope]
 * (含当前帧 [VanillaGuiGraphics] 与坐标换算助手)。
 * vanilla 内容经 1:1 通道先画,位于本场景所有 Compose 内容之下。
 */
@Deprecated(
    message = "vanillaDraw 已弃用:vanilla 元素固定垫全局最底,弹层(Popup/Dialog)内的 vanilla " +
        "会被主场景不透明 Compose 内容盖住,无法按图层/节点级控制 z 序;且 1:1 桥为翻译层,不支持 " +
        "blitSprite/item/tooltip 等原版高级 API。需要完整原版 API 或节点级层级时请直接使用原版 " +
        "GuiGraphicsExtractor(Screen.extractRenderState 参数)自行转译。本 API 保留兼容,不再演进。",
    level = DeprecationLevel.WARNING,
)
@Composable
fun Modifier.vanillaDraw(onDraw: VanillaDrawScope.() -> Unit): Modifier =
    this then VanillaDrawElement(LocalVanillaDrawState.current, onDraw)

/**
 * `vanillaDraw` 帧级状态:持有已挂载节点回调,并在每帧把 1:1 绘制桥
 * ([VanillaGuiGraphics]) 注入回调。
 *
 * ## 为什么需要 [frameCallbacks] 每帧重放(T.38 关键机制)
 * 本平台 Compose 图层是"命令烘焙"模型:`GraphicsLayer.record` 只在图层
 * isDirty 时执行,静态帧只回放缓存命令、**不再执行节点的 draw 代码**。若
 * vanilla 绘制挂在 DrawModifierNode.draw 里,vanilla 元素只会进入**首帧**,
 * 之后每帧新收集器中都没有它 → 视觉上"不生效"。
 * 因此 [VanillaDrawNode] 的 draw() 仅负责在脏帧更新几何快照(origin/size),
 * 真正的 `onDraw` 回调由 [runFrameCallbacks] 在 `renderFrame` 的
 * scene.render 之后**每帧执行**(几何取最近一次快照)—— vanilla 元素每帧
 * 重新注入当前帧的收集器,与图层显示列表缓存完全解耦。
 *
 * @see Modifier.vanillaDraw(已弃用,原因见其 KDoc)
 */
@Deprecated(
    message = "vanillaDraw(T.38)已弃用:层级缺陷(垫全局最底,弹层内容不可控)与 API 不完整,见 Modifier.vanillaDraw 弃用说明。",
    level = DeprecationLevel.WARNING,
)
class VanillaDrawState internal constructor() {

    /** 已挂载的 vanillaDraw 节点回调([VanillaDrawNode] attach/detach 维护,渲染线程访问) */
    internal val frameCallbacks = mutableListOf<(VanillaGuiGraphics) -> Unit>()

    /**
     * 每帧重放全部 vanillaDraw 回调(由 MinecraftComposeScene.renderFrame 调用;
     * [sink] 即当前帧的 ComposeGuiRenderer —— 桥元素经此注入 Compose 收集器,
     * 与 Compose 内容同一次 1:1 像素提交)。
     */
    internal fun runFrameCallbacks(sink: GuiCommandSink) {
        if (frameCallbacks.isEmpty()) return  // 无挂载回调时零开销(避免每帧构造桥对象)
        val graphics = VanillaGuiGraphics(sink)
        for (cb in frameCallbacks.toList()) cb(graphics)
    }
}

/**
 * 提供 [VanillaDrawState] 的 CompositionLocal,由
 * [moe.forpleuvoir.compose_minecraft.platform.screen.MinecraftComposeScene.setContent]
 * 在场景组合根部提供(实例稳定,不触发重组)。
 *
 * @see Modifier.vanillaDraw(已弃用)
 */
@Deprecated(
    message = "vanillaDraw(T.38)已弃用,见 Modifier.vanillaDraw 弃用说明。",
    level = DeprecationLevel.WARNING,
)
val LocalVanillaDrawState = staticCompositionLocalOf<VanillaDrawState> {
    error(
        "LocalVanillaDrawState not provided. " +
            "Modifier.vanillaDraw requires a MinecraftComposeScene host (ComposeScreen)."
    )
}

/**
 * `vanillaDraw` 绘制回调作用域:暴露当前帧的 1:1 像素绘制桥
 * [VanillaGuiGraphics] 与「节点本地坐标 → 窗口像素」换算。
 *
 * 本平台的渲染坐标系即窗口像素 1:1(裁剪、投射均不做 GUI 缩放),因此本地
 * 坐标 + 节点场景偏移([originInRoot])即为窗口像素,四舍五入取整后**直接**
 * 作为像素坐标传入 [VanillaGuiGraphics](不除 guiScale)。
 *
 * 换算助手为面向消费者的公共 API(devOnly 演示与下游模组使用,本模块内
 * 未全部引用,抑制 unused 告警)。
 *
 * @property graphics 当前帧的 1:1 像素绘制桥(原版 GUI API 形态,不受 guiScale 影响)
 * @property originInRoot 本节点左上角在场景(窗口像素)坐标系中的位置
 * @property size 本节点尺寸(像素)
 *
 * @see Modifier.vanillaDraw(已弃用)
 */
@Suppress("unused")
@Deprecated(
    message = "vanillaDraw(T.38)已弃用,见 Modifier.vanillaDraw 弃用说明。",
    level = DeprecationLevel.WARNING,
)
class VanillaDrawScope(
    val graphics: VanillaGuiGraphics,
    val originInRoot: Offset,
    val size: Size,
) {
    /** 本节点左上角对应的窗口像素坐标 */
    val guiOrigin: IntOffset get() = IntOffset(guiX(0f), guiY(0f))

    /** 本地 x → 窗口像素整数 x */
    fun guiX(localX: Float): Int = (originInRoot.x + localX).roundToInt()

    /** 本地 y → 窗口像素整数 y */
    fun guiY(localY: Float): Int = (originInRoot.y + localY).roundToInt()

    /** 本地长度(宽)→ 窗口像素整数长度 */
    fun guiWidth(localWidth: Float): Int = localWidth.roundToInt()

    /** 本地高度 → 窗口像素整数高度 */
    fun guiHeight(localHeight: Float): Int = localHeight.roundToInt()

    /**
     * 本地矩形 → 窗口像素 [Rect](Float 像素值;传给 [VanillaGuiGraphics] 的
     * `fill` / `enableScissor` 等 int 参数 API 前请自行 `.roundToInt()`)。
     */
    fun guiRect(left: Float, top: Float, right: Float, bottom: Float): Rect =
        Rect(
            originInRoot.x + left,
            originInRoot.y + top,
            originInRoot.x + right,
            originInRoot.y + bottom,
        )

    /** 本地点在场景中的换算偏移(节点左上角到本地原点的偏移) */
    fun guiOffset(localX: Float, localY: Float): IntOffset =
        IntOffset(guiX(localX), guiY(localY))
}

/**
 * 1:1 像素投影的原生 GUI 绘制桥(T.38 方案 B)。
 *
 * 提供与原版 [net.minecraft.client.gui.GuiGraphicsExtractor] **形态一致**的
 * 常用绘制 API(`fill` / `fillGradient` / `horizontalLine` / `verticalLine` /
 * `text` / `centeredText` / `enableScissor` / `disableScissor`),但**不写入
 * 原版 GuiRenderState** —— 每个调用构造与后端完全相同的 element 类型
 * (`ColoredRectangleRenderState` / `GuiTextRenderState` 等),经 [GuiCommandSink]
 * 注入 [moe.forpleuvoir.compose_minecraft.platform.render.ComposeGuiRenderer]
 * 的 items 列表,由 Compose 渲染器的 **1:1 像素投影 + 像素级裁剪**绘制:
 * - 坐标语义 = 窗口像素(与 Compose 场景同坐标系,不受原版 guiScale 影响);
 * - 文字复用原版字体管线(9px 基准行高,与 Compose BasicText 同度量);
 * - scissor 为像素矩形,直接随 element 提交(渲染器像素级钳制/翻转)。
 *
 * 桥是"API 翻译层":渲染端零重复 —— 元素构造、分组、上传、绘制全部复用
 * ComposeGuiRenderer(见其 prepare/upload/draw)。
 *
 * 注意:pose 恒为恒等矩阵(1:1 无仿射变换);blit / blitSprite / item /
 * itemDecorations / tooltip / setTooltipForNextFrame / entity 等原版高级 API
 * **暂不支持**(后续按需补齐,首版裁剪)。
 *
 * @see Modifier.vanillaDraw(已弃用)
 */
@Suppress("unused")
@Deprecated(
    message = "vanillaDraw(T.38)已弃用,见 Modifier.vanillaDraw 弃用说明。",
    level = DeprecationLevel.WARNING,
)
class VanillaGuiGraphics(private val sink: GuiCommandSink) {

    /** 像素 scissor 栈(与 vanilla 语义一致:enable 入栈 / disable 出栈) */
    private val scissorStack = ArrayDeque<ScreenRectangle>()

    private val currentScissor: ScreenRectangle? get() = scissorStack.peek()

    // ── scissor ─────────────────────────────────────────────────────

    /** 入栈一个像素级裁剪矩形;[disableScissor] 配对出栈 */
    fun enableScissor(x0: Int, y0: Int, x1: Int, y1: Int) {
        scissorStack.push(ScreenRectangle(x0, y0, x1 - x0, y1 - y0))
    }

    /** 出栈一个裁剪矩形(与 [enableScissor] 配对) */
    fun disableScissor() {
        if (scissorStack.isNotEmpty()) scissorStack.pop()
    }

    /** 判断点是否在任一已启用裁剪矩形内(无裁剪时恒为 true,同 vanilla) */
    fun containsPointInScissor(x: Int, y: Int): Boolean {
        for (rect in scissorStack) {
            if (x >= rect.left() && x < rect.right() && y >= rect.top() && y < rect.bottom()) return true
        }
        return true
    }

    // ── 矩形 ───────────────────────────────────────────────────────

    /** 填充实心矩形(颜色 0xAARRGGBB;坐标将规范化,同 vanilla fill) */
    fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        fill(RenderPipelines.GUI, x0, y0, x1, y1, color)
    }

    /** 填充实心矩形(指定 pipeline) */
    fun fill(pipeline: RenderPipeline, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var a = x0
        var b = y0
        var c = x1
        var d = y1
        if (a < c) {
            val tmp = a
            a = c
            c = tmp
        }
        if (b < d) {
            val tmp = b
            b = d
            d = tmp
        }
        innerFill(pipeline, TextureSetup.noTexture(), a, b, c, d, color, color)
    }

    /** 垂直渐变填充(上色 [color1] → 下色 [color2]) */
    fun fillGradient(x0: Int, y0: Int, x1: Int, y1: Int, color1: Int, color2: Int) {
        innerFill(RenderPipelines.GUI, TextureSetup.noTexture(), x0, y0, x1, y1, color1, color2)
    }

    /** 水平线段(1px 高),同 vanilla 语义 */
    fun horizontalLine(x0: Int, x1: Int, y: Int, color: Int) {
        var left = x0
        var right = x1
        if (right < left) {
            val tmp = left
            left = right
            right = tmp
        }
        fill(left, y, right + 1, y + 1, color)
    }

    /** 垂直线段(1px 宽),同 vanilla 语义 */
    fun verticalLine(x: Int, y0: Int, y1: Int, color: Int) {
        var top = y0
        var bottom = y1
        if (bottom < top) {
            val tmp = top
            top = bottom
            bottom = tmp
        }
        fill(x, top + 1, x + 1, bottom, color)
    }

    private fun innerFill(
        pipeline: RenderPipeline,
        textureSetup: TextureSetup,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color1: Int,
        color2: Int,
    ) {
        // 照抄 vanilla GuiGraphicsExtractor.innerFill:像素矩形 + 恒等 pose + 当前 scissor
        sink.addElement(
            ColoredRectangleRenderState(
                pipeline, textureSetup, Matrix3x2f(), x0, y0, x1, y1, color1, color2, currentScissor
            )
        )
    }

    // ── 文本 ───────────────────────────────────────────────────────

    /** 绘制文本(字符串,经视觉顺序处理;默认带阴影),同 vanilla text 语义 */
    fun text(font: Font, str: String?, x: Int, y: Int, color: Int, dropShadow: Boolean = true) {
        if (str != null) {
            text(font, Language.getInstance().getVisualOrder(FormattedText.of(str)), x, y, color, dropShadow)
        }
    }

    /** 绘制文本(已处理视觉顺序的序列),同 vanilla text 语义 */
    fun text(font: Font, str: FormattedCharSequence, x: Int, y: Int, color: Int, dropShadow: Boolean = true) {
        if (ARGB.alpha(color) != 0) {
            sink.addText(
                GuiTextRenderState(font, str, Matrix3x2f(), x, y, color, 0, dropShadow, false, currentScissor)
            )
        }
    }

    /** 绘制文本(富文本组件),同 vanilla 语义 */
    fun text(font: Font, component: Component, x: Int, y: Int, color: Int, dropShadow: Boolean = true) {
        text(font, component.getVisualOrderText(), x, y, color, dropShadow)
    }

    /** 以 [x] 为中心绘制文本(字符串) */
    fun centeredText(font: Font, str: String, x: Int, y: Int, color: Int) {
        text(font, str, x - font.width(str) / 2, y, color)
    }

    /** 以 [x] 为中心绘制文本(富文本组件) */
    fun centeredText(font: Font, component: Component, x: Int, y: Int, color: Int) {
        val toRender = component.getVisualOrderText()
        text(font, toRender, x - font.width(toRender) / 2, y, color)
    }
}

private class VanillaDrawElement(
    private val state: VanillaDrawState,
    val onDraw: VanillaDrawScope.() -> Unit,
) : ModifierNodeElement<VanillaDrawNode>() {
    override fun create(): VanillaDrawNode = VanillaDrawNode(state, onDraw)

    override fun update(node: VanillaDrawNode) {
        node.onDraw = onDraw
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "vanillaDraw"
        properties["onDraw"] = onDraw
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VanillaDrawElement) return false
        return state === other.state && onDraw === other.onDraw
    }

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + onDraw.hashCode()
        return result
    }
}

private class VanillaDrawNode(
    private val state: VanillaDrawState,
    var onDraw: VanillaDrawScope.() -> Unit,
) : Modifier.Node(), DrawModifierNode {

    /** 最近一次 draw 期几何快照(脏帧更新;静态帧沿用,供每帧 vanilla 重放) */
    private var geometryOrigin: Offset = Offset.Zero
    private var geometrySize: Size = Size.Zero
    private var hasGeometry = false

    override fun onAttach() {
        state.frameCallbacks += ::invokeVanilla
    }

    override fun onDetach() {
        state.frameCallbacks -= ::invokeVanilla
    }

    /** 每帧 vanilla 重放入口(renderFrame 内、经 1:1 桥注入时调用) */
    private fun invokeVanilla(graphics: VanillaGuiGraphics) {
        if (!hasGeometry) return   // 首次 draw 之前(几何未知)跳过
        onDraw(VanillaDrawScope(graphics, geometryOrigin, geometrySize))
    }

    override fun ContentDrawScope.draw() {
        // 仅脏帧执行:更新几何快照(节点在场景/窗口像素坐标系中的偏移与尺寸,
        // 经 LayoutCoordinates.positionInRoot 获取 —— 包含父链全部位置与
        // graphicsLayer 图层位移,不含图层 translation/rotation/scale)。
        // 不在此处直接调用绘制桥 —— 图层脏标记缓存会让 vanilla 元素只进首帧,
        // 见 VanillaDrawState KDoc;原版绘制由 state.frameCallbacks 每帧重放。
        geometryOrigin = requireLayoutCoordinates().positionInRoot()
        geometrySize = size
        hasGeometry = true
        drawContent()
    }
}