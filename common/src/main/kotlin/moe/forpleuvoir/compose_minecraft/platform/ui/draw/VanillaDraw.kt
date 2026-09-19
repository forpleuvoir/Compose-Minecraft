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
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
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
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.ComposeGuiRenderer

/**
 * 绘制修饰符扩展:在合成时把本节点注册进「原版 GUI 绘制桥」帧管线。
 *
 * ## 双通道设计
 * 每个修饰符([Modifier.vanillaDraw] 与 [Modifier.postVanillaDraw])都有一个
 * [guiScaleEnabled] 开关,选择两条渲染通道之一:
 *
 * - **1:1 通道`guiScaleEnabled = false`(默认)**:业务在 [VanillaDrawScope] 内经
 *   [VanillaGuiGraphics] 使用与原版
 *   [net.minecraft.client.gui.GuiGraphicsExtractor] 形态一致的常用绘制
 *   API(`fill` / `fillGradient` / `text` / `enableScissor` 等)。每个调用被转译成
 *   与后端完全相同的 element 类型(`ColoredRectangleRenderState` /
 *   `GuiTextRenderState`),经 [GuiCommandSink] 注入
 *   [ComposeGuiRenderer] 的
 *   items 列表,由 Compose 渲染器的 **1:1 像素投影 + 像素级裁剪**绘制 ——
 *   **完全不受原版 guiScale 影响**(与 Compose 场景同坐标系:窗口像素 1:1,
 *   1dp == 1 像素,文字 9px、矩形像素级对齐);
 * - **原版通道`guiScaleEnabled = true`**:业务直接在 [VanillaDrawScope.graphics]
 *   上调用**当前帧的真原版 [GuiGraphicsExtractor]** —— 元素写入原版
 *   GuiRenderState,由原版 GuiRenderer 以 **GUI 投影**(`窗口宽高 / guiScale`)
 *   绘制,坐标按 **GUI 单位**(窗口像素 ÷ guiScale,marshal 见 [VanillaDrawScope])
 *   —— 内容随原版 guiScale 整体放大,**blitSprite / item / tooltip /
 *   setTooltipForNextFrame / entity** 等原版高级 API 全部可用(不再是翻译层)。
 *   每帧的原版 extractor 实例由
 *   [moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen] 的
 *   `extractRenderState` 注入 [VanillaDrawState.graphics],仅在 extract 阶段栈内可见。
 *
 * ## 渲染顺序
 * - [Modifier.vanillaDraw]:回调在 Compose 内容收集**之前**执行,1:1 通道的元素
 *   注入 items 列表**头部** —— 画在本场景所有 Compose 内容**之下**(像"垫在
 *   Compose 下的底图";Compose 不透明处完全覆盖、透明/半透明处可见);
 * - [Modifier.postVanillaDraw]:回调在 Compose 内容收集**之后**执行,1:1 通道的
 *   元素注入 items 列表**尾部** —— 画在**所有 Compose 内容之上**(含 Popup/
 *   Dialog 弹出层,真正的最顶层遮罩/装饰);
 * - **原版通道限制**:原版 GUI 在 Compose **之下**绘制(框架渲染层级
 *   ComposeGuiRenderer 在原版普通 GUI 之后、F3 之前提交),因此 `guiScaleEnabled
 *   = true` 时无论 vanillaDraw 还是 postVanillaDraw,内容都位于 Compose 之下;
 *   postVanillaDraw 的"最顶"语义**仅在 1:1 通道成立**(KDoc 各处同此);
 * - F3 调试覆盖层(after-blur 段)始终在本场景之上。
 *
 * ## 为什么需要两种通道(历史)
 * 原版 [GuiGraphicsExtractor] 写的元素必然受原版 GUI 投影 guiScale 缩放,与
 * 1:1 的 Compose 内容无法像素对齐;而 1:1 翻译层又无法覆盖 blitSprite/item/
 * tooltip 等高级 API。双通道让业务按需选择:与 Compose 像素对齐用默认 1:1
 * 通道;需要原版完整渲染能力(图标、tooltip、九宫格贴图)时开原版通道。
 *
 * ## 坐标换算[VanillaDrawScope]
 * 场景尺寸 = 窗口像素(1:1);节点场景偏移 = `LayoutCoordinates.positionInRoot`
 * (**包含 graphicsLayer 图层位移**,不含图层 translation/rotation/scale)。
 * [VanillaDrawScope] 的换算助手按通道产出坐标:
 * - 1:1 通道:本地坐标 + 场景偏移 → **窗口像素整数**(不缩放);
 * - 原版通道:同一像素值 ÷ `guiScale` → **GUI 单位整数**(随原版投影放大)。
 * 因此**同一段业务代码在两种模式下无需改写** —— `guiRect(...)`/
 * `guiX(...)` 等已按通道自动换算,直接传给对应通道的 native API 即可。
 *
 * ## 帧生命周期
 * 桥接绘制由 `renderFrame` 在 `scene.render` 前后**每帧执行**(绕开 Compose
 * 图层脏标记缓存,见 [VanillaDrawState] KDoc),几何取节点最近一次 draw 快照。
 * 绘制阶段之外回调整体跳过。
 *
 * Popup/Dialog 图层内:场景偏移以弹层自身原点为基准(未叠加弹层在窗口中的位移),
 * 如弹层内需要精确窗口坐标请自行叠加弹层位置。
 *
 * @param guiScaleEnabled 是否启用原版 guiScale(原版通道):
 * `true` 走当前帧真 [GuiGraphicsExtractor] / 原版 GuiRenderState(GUI 单位坐标,
 * 受原版投影,blitSprite/item/tooltip 等高级 API 可用,内容画在 Compose 之下);
 * `false`(默认)走 1:1 像素绘制桥(与 Compose 内容同空间像素对齐)。
 * @param onDraw 绘制回调:每帧在 renderFrame 内执行,可访问 [VanillaDrawScope]
 * (含当前通道的 native API 与坐标换算助手)。
 */
@Composable
fun Modifier.vanillaDraw(
    guiScaleEnabled: Boolean = false,
    onDraw: VanillaDrawScope.() -> Unit,
): Modifier = this then VanillaDrawElement(
    state = LocalVanillaDrawState.current,
    guiScaleEnabled = guiScaleEnabled,
    post = false,
    onDraw = onDraw,
)

/**
 * 后渲染版 [Modifier.vanillaDraw]:回调在**所有 Compose 内容收集之后**执行。
 *
 * - 1:1 通道(默认):元素注入收集器 items 列表**尾部**,画在全部 Compose 内容
 *   (含 Popup/Dialog)之**上** —— 适合覆盖式遮罩、描边、调试信息、顶层装饰;
 * - 原版通道([guiScaleEnabled] = true):走当前帧真
 *   [net.minecraft.client.gui.GuiGraphicsExtractor],受原版 guiScale ——
 *   **注意原版 GUI 画在 Compose 之下**,该模式下内容不在 Compose 之上。
 *
 * 其余语义(双通道/坐标换算/帧生命周期)与 [Modifier.vanillaDraw] 完全一致。
 *
 * @param guiScaleEnabled 见 [Modifier.vanillaDraw]
 * @param onDraw 绘制回调:每帧在 Compose 内容收集之后执行
 */
@Composable
fun Modifier.postVanillaDraw(
    guiScaleEnabled: Boolean = false,
    onDraw: VanillaDrawScope.() -> Unit,
): Modifier = this then VanillaDrawElement(
    state = LocalVanillaDrawState.current,
    guiScaleEnabled = guiScaleEnabled,
    post = true,
    onDraw = onDraw,
)

/**
 * `vanillaDraw` / `postVanillaDraw` 帧级状态:持有已挂载节点回调,每帧把
 * 两条通道的 native([VanillaGuiGraphics] 1:1 桥 与 当前帧原版
 * [GuiGraphicsExtractor])注入回调。
 *
 * ## 为什么需要回调每帧重放(关键机制)
 * 本平台 Compose 图层是"命令烘焙"模型:`GraphicsLayer.record` 只在图层
 * isDirty 时执行,静态帧只回放缓存命令、**不再执行节点的 draw 代码**。若
 * vanilla 绘制挂在 DrawModifierNode.draw 里,vanilla 元素只会进入**首帧**,
 * 之后每帧新收集器中都没有它 → 视觉上"不生效"。
 * 因此 [VanillaDrawNode] 的 draw() 仅负责在脏帧更新几何快照(origin/size),
 * 真正的 `onDraw` 回调由 [runFrameCallbacks] / [runPostFrameCallbacks] 在
 * `renderFrame` 内**每帧执行**(几何取最近一次快照)—— vanilla 元素每帧
 * 重新注入当前帧的收集器与当前帧的原版 extractor,与图层显示列表缓存完全解耦。
 *
 * ## 平台开放点
 * 本类 public,`graphics` 供自定义宿主直接读写;标准流程由
 * [moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen] 的
 * `extractRenderState` 维护(renderFrame 前写入当前帧 extractor,完成后清除)。
 *
 * @see Modifier.vanillaDraw
 * @see Modifier.postVanillaDraw
 */
class VanillaDrawState internal constructor() {

    /**
     * 当前帧的原版 [GuiGraphicsExtractor](guiScale 通道用):
     * 由 ComposeScreen.extractRenderState 在 renderFrame 之前写入、
     * renderFrame 完成后清除 —— 回调仅在 extract 阶段栈内可见,
     * 仅当节点 [VanillaDrawScope.guiScaleEnabled] 为 true 时使用。
     */
    var graphics: GuiGraphicsExtractor? = null

    /** 已挂载的前渲染(vanillaDraw)节点回调([VanillaDrawNode] attach/detach 维护,渲染线程访问) */
    internal val frameCallbacks =
        mutableListOf<(VanillaGuiGraphics, GuiGraphicsExtractor?, Float) -> Unit>()

    /** 已挂载的后渲染(postVanillaDraw)节点回调(同上) */
    internal val postFrameCallbacks =
        mutableListOf<(VanillaGuiGraphics, GuiGraphicsExtractor?, Float) -> Unit>()

    /**
     * 每帧重放全部前渲染回调(由 MinecraftComposeScene.renderFrame 在
     * scene.render 之后、renderContext.render 之前调用)。1:1 桥元素注入收集器
     * 头部 → 画在 Compose 内容之下;[graphics] 供 guiScale 通道的原版 extractor。
     */
    internal fun runFrameCallbacks(
        sink: GuiCommandSink,
        graphics: GuiGraphicsExtractor?,
        guiScale: Float,
    ) {
        if (frameCallbacks.isEmpty()) return  // 无挂载回调时零开销(避免每帧构造桥对象)
        val bridge = VanillaGuiGraphics(sink)
        for (cb in frameCallbacks.toList()) cb(bridge, graphics, guiScale)
    }

    /**
     * 每帧重放全部后渲染回调(由 MinecraftComposeScene.renderFrame 在
     * renderContext.render **之后**调用):1:1 桥元素注入收集器尾部 → 画在
     * 全部 Compose 内容之上;guiScale 通道仍走原版 extractor(画在 Compose 之下)。
     */
    internal fun runPostFrameCallbacks(
        sink: GuiCommandSink,
        graphics: GuiGraphicsExtractor?,
        guiScale: Float,
    ) {
        if (postFrameCallbacks.isEmpty()) return
        val bridge = VanillaGuiGraphics(sink)
        for (cb in postFrameCallbacks.toList()) cb(bridge, graphics, guiScale)
    }
}

/**
 * 提供 [VanillaDrawState] 的 CompositionLocal,由
 * [moe.forpleuvoir.compose_minecraft.platform.screen.MinecraftComposeScene.setContent]
 * 在场景组合根部提供(实例稳定,不触发重组)。
 *
 * @see Modifier.vanillaDraw
 * @see Modifier.postVanillaDraw
 */
val LocalVanillaDrawState = staticCompositionLocalOf<VanillaDrawState> {
    error(
        "LocalVanillaDrawState not provided. " +
            "Modifier.vanillaDraw / postVanillaDraw requires a MinecraftComposeScene host (ComposeScreen)."
    )
}

/**
 * `vanillaDraw` / `postVanillaDraw` 绘制回调作用域:暴露当前通道的 native
 * API 与「节点本地坐标 → 绘制空间坐标」换算。
 *
 * ## 两条通道的坐标语义
 * 场景尺寸 = 窗口像素(1:1,1dp == 1 像素,不除 guiScale);节点场景偏移
 * ([originInRoot]) = `LayoutCoordinates.positionInRoot()`(含 graphicsLayer
 * 图层位移,不含图层仿射变换)。换算助手按 [guiScaleEnabled] 产出**当前通道的
 * 坐标空间**:
 * - `guiScaleEnabled = false`(1:1 通道):本地 + 场景偏移 → **窗口像素整数**;
 * - `guiScaleEnabled = true`(原版通道):同一像素值 ÷ [guiScale] →
 *   **GUI 单位整数**(与受原版投影放大后的内容对齐)。
 * 因此同一段业务代码在两种模式下无需改写,`guiRect(...)` / `guiX(...)` 等
 * 已自动换算;传给便捷方法或 native 的坐标恰为对应通道所需。
 *
 * ## native 访问
 * - 1:1 通道:便捷方法转发到 [bridge](像素空间),业务也可直接持有
 *   [VanillaGuiGraphics] 使用其完整翻译层 API;
 * - 原版通道:便捷方法转发到 [graphics](GUI 单位空间,签名同形),业务可直接
 *   使用原版高级 API(`blitSprite` / `item` / `tooltip` / `setTooltipForNextFrame`
 *   / `entity` 等),坐标用换算助手得到 GUI 单位。
 *
 * 换算助手为面向消费者的公共 API(devOnly 演示与下游模组使用,本模块内
 * 未全部引用,抑制 unused 告警)。
 *
 * @property guiScaleEnabled 是否启用原版通道(见 [Modifier.vanillaDraw])
 * @property guiScale 当前窗口的 GUI 缩放系数(1:1 通道下参与换算但等效 1;原版通道下为除数)
 * @property graphics 当前帧的原版 [GuiGraphicsExtractor](仅原版通道非空;GUI 单位坐标)
 * @property bridge 1:1 像素绘制桥(仅 1:1 通道非空;像素坐标)
 * @property originInRoot 本节点左上角在场景(窗口像素)坐标系中的位置
 * @property size 本节点尺寸(像素)
 *
 * @see Modifier.vanillaDraw
 * @see Modifier.postVanillaDraw
 */
@Suppress("unused")
class VanillaDrawScope(
    val guiScaleEnabled: Boolean,
    val guiScale: Float,
    val originInRoot: Offset,
    val size: Size,
    internal val bridge: VanillaGuiGraphics?,
    val graphics: GuiGraphicsExtractor?,
) {
    /** 本节点左上角对应的绘制空间坐标(1:1 = 窗口像素;原版通道 = GUI 单位) */
    val guiOrigin: IntOffset get() = IntOffset(guiX(0f), guiY(0f))

    /** 本地 x → 绘制空间整数 x */
    fun guiX(localX: Float): Int =
        ((originInRoot.x + localX) / scale).roundToInt()

    /** 本地 y → 绘制空间整数 y */
    fun guiY(localY: Float): Int =
        ((originInRoot.y + localY) / scale).roundToInt()

    /** 本地长度(宽)→ 绘制空间整数长度 */
    fun guiWidth(localWidth: Float): Int = (localWidth / scale).roundToInt()

    /** 本地高度 → 绘制空间整数高度 */
    fun guiHeight(localHeight: Float): Int = (localHeight / scale).roundToInt()

    /** 坐标空间缩放系数:原版通道 = guiScale(像素 → GUI 单位),1:1 通道 = 1 */
    private val scale: Float get() = if (guiScaleEnabled) guiScale else 1f

    /**
     * 本地矩形 → 绘制空间 [Rect](Float 值;1:1 通道 = 窗口像素,原版通道 =
     * GUI 单位;传给 int 参数 API 前请自行 `.roundToInt()`)。与 [guiX] 等
     * 助手同规则:已按通道自动换算。
     */
    fun guiRect(left: Float, top: Float, right: Float, bottom: Float): Rect {
        val s = scale
        return Rect(
            (originInRoot.x + left) / s,
            (originInRoot.y + top) / s,
            (originInRoot.x + right) / s,
            (originInRoot.y + bottom) / s,
        )
    }

    /** 本地点在绘制空间中的换算偏移(节点左上角到本地原点的偏移) */
    fun guiOffset(localX: Float, localY: Float): IntOffset =
        IntOffset(guiX(localX), guiY(localY))

    // ── 便捷方法(按通道转发到 native;坐标语义 = 当前通道空间)──────────

    /** 填充实心矩形(颜色 0xAARRGGBB;坐标按当前通道空间,同 vanilla fill) */
    fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        if (guiScaleEnabled) graphics?.fill(x0, y0, x1, y1, color) else bridge?.fill(x0, y0, x1, y1, color)
    }

    /** 垂直渐变填充(上色 [color1] → 下色 [color2]) */
    fun fillGradient(x0: Int, y0: Int, x1: Int, y1: Int, color1: Int, color2: Int) {
        if (guiScaleEnabled) graphics?.fillGradient(x0, y0, x1, y1, color1, color2)
        else bridge?.fillGradient(x0, y0, x1, y1, color1, color2)
    }

    /** 水平线段(1px 高),同 vanilla 语义 */
    fun horizontalLine(x0: Int, x1: Int, y: Int, color: Int) {
        if (guiScaleEnabled) graphics?.horizontalLine(x0, x1, y, color)
        else bridge?.horizontalLine(x0, x1, y, color)
    }

    /** 垂直线段(1px 宽),同 vanilla 语义 */
    fun verticalLine(x: Int, y0: Int, y1: Int, color: Int) {
        if (guiScaleEnabled) graphics?.verticalLine(x, y0, y1, color)
        else bridge?.verticalLine(x, y0, y1, color)
    }

    /** 绘制文本(字符串,经视觉顺序处理;默认带阴影),同 vanilla text 语义 */
    fun text(font: Font, str: String?, x: Int, y: Int, color: Int, dropShadow: Boolean = true) {
        if (guiScaleEnabled) graphics?.text(font, str, x, y, color, dropShadow)
        else bridge?.text(font, str, x, y, color, dropShadow)
    }

    /** 绘制文本(已处理视觉顺序的序列),同 vanilla text 语义 */
    fun text(font: Font, str: FormattedCharSequence, x: Int, y: Int, color: Int, dropShadow: Boolean = true) {
        if (guiScaleEnabled) graphics?.text(font, str, x, y, color, dropShadow)
        else bridge?.text(font, str, x, y, color, dropShadow)
    }

    /** 绘制文本(富文本组件),同 vanilla 语义 */
    fun text(font: Font, component: Component, x: Int, y: Int, color: Int, dropShadow: Boolean = true) {
        if (guiScaleEnabled) graphics?.text(font, component, x, y, color, dropShadow)
        else bridge?.text(font, component, x, y, color, dropShadow)
    }

    /** 以 [x] 为中心绘制文本(字符串) */
    fun centeredText(font: Font, str: String, x: Int, y: Int, color: Int) {
        if (guiScaleEnabled) graphics?.centeredText(font, str, x, y, color)
        else bridge?.centeredText(font, str, x, y, color)
    }

    /** 以 [x] 为中心绘制文本(富文本组件) */
    fun centeredText(font: Font, component: Component, x: Int, y: Int, color: Int) {
        if (guiScaleEnabled) graphics?.centeredText(font, component, x, y, color)
        else bridge?.centeredText(font, component, x, y, color)
    }

    /** 入栈一个裁剪矩形([disableScissor] 配对出栈);坐标语义 = 当前通道空间 */
    fun enableScissor(x0: Int, y0: Int, x1: Int, y1: Int) {
        if (guiScaleEnabled) graphics?.enableScissor(x0, y0, x1, y1)
        else bridge?.enableScissor(x0, y0, x1, y1)
    }

    /** 出栈一个裁剪矩形(与 [enableScissor] 配对) */
    fun disableScissor() {
        if (guiScaleEnabled) graphics?.disableScissor() else bridge?.disableScissor()
    }
}

/**
 * 1:1 像素投影的原生 GUI 绘制桥(方案 B,1:1 通道的 native)。
 *
 * 提供与原版 [net.minecraft.client.gui.GuiGraphicsExtractor] **形态一致**的
 * 常用绘制 API(`fill` / `fillGradient` / `horizontalLine` / `verticalLine` /
 * `text` / `centeredText` / `enableScissor` / `disableScissor`),但**不写入
 * 原版 GuiRenderState** —— 每个调用构造与后端完全相同的 element 类型
 * (`ColoredRectangleRenderState` / `GuiTextRenderState` 等),经 [GuiCommandSink]
 * 注入 [ComposeGuiRenderer]
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
 * **不在本桥内**(需要时请用 [Modifier.vanillaDraw]/[Modifier.postVanillaDraw]
 * 的 `guiScaleEnabled = true` 原版通道,直接使用真 [GuiGraphicsExtractor])。
 *
 * @see Modifier.vanillaDraw
 * @see Modifier.postVanillaDraw
 */
@Suppress("unused")
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

    /** 判断点是否在当前启用的裁剪矩形内(无裁剪栈时恒为 true,同 vanilla 语义) */
    fun containsPointInScissor(x: Int, y: Int): Boolean {
        val rect = scissorStack.peek() ?: return true
        return x >= rect.left() && x < rect.right() && y >= rect.top() && y < rect.bottom()
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
    val guiScaleEnabled: Boolean,
    val post: Boolean,
    val onDraw: VanillaDrawScope.() -> Unit,
) : ModifierNodeElement<VanillaDrawNode>() {
    override fun create(): VanillaDrawNode = VanillaDrawNode(state, guiScaleEnabled, post, onDraw)

    override fun update(node: VanillaDrawNode) {
        // 通道/前后置切换时在对应回调列表间迁移注册
        if (node.guiScaleEnabled != guiScaleEnabled || node.post != post) {
            node.unregister()
            node.guiScaleEnabled = guiScaleEnabled
            node.post = post
            node.register()
        }
        node.onDraw = onDraw
    }

    override fun InspectorInfo.inspectableProperties() {
        name = if (post) "postVanillaDraw" else "vanillaDraw"
        properties["guiScaleEnabled"] = guiScaleEnabled
        properties["onDraw"] = onDraw
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VanillaDrawElement) return false
        return state === other.state &&
            guiScaleEnabled == other.guiScaleEnabled &&
            post == other.post &&
            onDraw === other.onDraw
    }

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + guiScaleEnabled.hashCode()
        result = 31 * result + post.hashCode()
        result = 31 * result + onDraw.hashCode()
        return result
    }
}

private class VanillaDrawNode(
    private val state: VanillaDrawState,
    var guiScaleEnabled: Boolean,
    var post: Boolean,
    var onDraw: VanillaDrawScope.() -> Unit,
) : Modifier.Node(), DrawModifierNode {

    /** 最近一次 draw 期几何快照(脏帧更新;静态帧沿用,供每帧 vanilla 重放) */
    private var geometryOrigin: Offset = Offset.Zero
    private var geometrySize: Size = Size.Zero
    private var hasGeometry = false

    override fun onAttach() {
        register()
    }

    override fun onDetach() {
        unregister()
    }

    fun register() {
        val cb: (VanillaGuiGraphics, GuiGraphicsExtractor?, Float) -> Unit = ::invokeVanilla
        if (post) state.postFrameCallbacks += cb else state.frameCallbacks += cb
    }

    fun unregister() {
        val cb: (VanillaGuiGraphics, GuiGraphicsExtractor?, Float) -> Unit = ::invokeVanilla
        if (post) state.postFrameCallbacks -= cb else state.frameCallbacks -= cb
    }

    /** 每帧 vanilla 重放入口(renderFrame 内、两条通道注入时调用) */
    private fun invokeVanilla(bridge: VanillaGuiGraphics, graphics: GuiGraphicsExtractor?, guiScale: Float) {
        if (!hasGeometry) return   // 首次 draw 之前(几何未知)跳过
        // 存疑(未修复):两个分支各自硬编码 guiScaleEnabled(原版通道 true / 1:1 通道 false),
        // 该值在分支内恒定。当前行为正常,复现"原版绘制缩放判断错"时优先看此处。
        val scope = if (guiScaleEnabled) {
            val g = graphics
            if (g == null) return   // 原版通道但本帧 extractor 缺失 → 跳过
            VanillaDrawScope(guiScaleEnabled, guiScale, geometryOrigin, geometrySize, null, g)
        } else {
            VanillaDrawScope(guiScaleEnabled, guiScale, geometryOrigin, geometrySize, bridge, null)
        }
        onDraw(scope)
    }

    override fun ContentDrawScope.draw() {
        // 仅脏帧执行:更新几何快照(节点在场景/窗口像素坐标系中的偏移与尺寸,
        // 经 LayoutCoordinates.positionInRoot 获取 —— 包含父链全部位置与
        // graphicsLayer 图层位移,不含图层 translation/rotation/scale)。
        // 不在此处直接调用绘制桥 —— 图层脏标记缓存会让 vanilla 元素只进首帧,
        // 见 VanillaDrawState KDoc;原版绘制由 state 回调列表每帧重放。
        geometryOrigin = requireLayoutCoordinates().positionInRoot()
        geometrySize = size
        hasGeometry = true
        drawContent()
    }
}