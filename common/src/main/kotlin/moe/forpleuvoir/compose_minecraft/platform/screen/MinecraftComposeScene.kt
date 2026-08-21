package moe.forpleuvoir.compose_minecraft.platform.screen
import moe.forpleuvoir.compose_minecraft.mc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.PointerEventResult
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.mojang.blaze3d.platform.cursor.CursorType
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.ComposeGuiRenderer
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.MinecraftRenderContext
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.LocalVanillaDrawState
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.VanillaDrawState
import moe.forpleuvoir.compose_minecraft.platform.ui.popup.LocalPopupHost
import moe.forpleuvoir.compose_minecraft.platform.ui.popup.PopupHostOverlay
import moe.forpleuvoir.compose_minecraft.platform.ui.popup.PopupHostState
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalCharFilter
import net.minecraft.client.Minecraft

/**
 * Minecraft 平台的 Compose Scene 宿主(阶段 D)。
 *
 * 包装 CMP 移植的 [CanvasLayersComposeScene]:
 * - 密度默认 1f,场景尺寸 = Minecraft 窗口像素(T.24:1:1,不再除 guiScale),
 *   1dp == 1 像素;可传 [density] 放大(2f 时 1dp == 2 像素,UI 元素视觉放大,
 *   与官方桌面 density 语义一致,文本字号同步放大);
 * - 由 [ComposeScreen](原版 Screen 桥接)每帧调用 [renderFrame],把绘制命令记录进
 *   [MinecraftCanvas] 并提交到当前帧的 [ComposeGuiRenderer](直接进入当前 RenderTarget,
 *   无离屏纹理);
 * - 输入(pointer/key)、文字渲染、弹出层等为后续阶段。
 */
@OptIn(InternalComposeUiApi::class)
class MinecraftComposeScene(
    width: Int,
    height: Int,
    /** 平台适配点(T.26):场景密度,默认 1f(1dp == 1 像素);业务方可传 >1f 放大 UI */
    density: Float = 1f,
    /**
     * 平台接入点工厂(平台开放点):默认 [MinecraftPlatformContext]。
     *
     * 依赖方模组可注入自定义 [PlatformContext] 实现 —— 推荐继承
     * [MinecraftPlatformContext] 覆写部分成员(可访问场景公开状态:
     * textInputService / sceneContainerSize / desiredCursorType /
     * onSemanticsChanged / getSemanticsOwners,并复用
     * [MinecraftPlatformContext.toMinecraftCursorType] 光标映射),或完全自建。
     *
     * 注意:工厂在字段初始化期调用(CanvasLayersComposeScene 构造之前),自定义实现
     * 构造时只能访问本场景已初始化的公开状态,不得读取内部 scene 字段(尚未赋值,
     * 会触发属性初始化顺序 NPE)。
     */
    platformContextFactory: (MinecraftComposeScene) -> PlatformContext = { MinecraftPlatformContext(it) },
) {
    /** 每帧命令记录的画布(不分配 CPU/GPU 位图,纯命令列表) */
    private val canvas = MinecraftCanvas()

    private val renderContext = MinecraftRenderContext()

    /**
     * 本屏的独立 GUI 渲染器(T.24):extract 阶段经 [renderFrame] 收集 Compose 命令,
     * gui 阶段由 GuiRendererMixin 在 GuiRenderer.draw 的 after-blur(HUD)段之前提交。
     * [ComposeScreen] init/removed 负责注册/注销 [ComposeGuiRenderer.active]。
     */
    val renderer = ComposeGuiRenderer()

    /**
     * vanillaDraw 帧级状态(T.38):持有已挂载的 vanillaDraw/postVanillaDraw
     * 节点回调,由 [renderFrame] 把当前帧收集器([ComposeGuiRenderer])与当前帧
     * 原版 [net.minecraft.client.gui.GuiGraphicsExtractor]
     * ([VanillaDrawState.graphics],由 ComposeScreen.extractRenderState 写入)
     * 传入 [VanillaDrawState.runFrameCallbacks] /
     * [VanillaDrawState.runPostFrameCallbacks]:1:1 绘制桥
     * ([moe.forpleuvoir.compose_minecraft.platform.ui.draw.VanillaGuiGraphics])
     * 经 [GuiCommandSink] 注入本收集器(前渲染元素在头、后渲染元素在尾);
     * guiScale 通道经当前帧原版 extractor 走原版 GuiRenderState。
     * 组合根部经 [LocalVanillaDrawState] 提供给业务
     * (`Modifier.vanillaDraw` / `Modifier.postVanillaDraw`)。
     *
     * 平台开放点:public —— 自定义宿主可直接读写帧态。
     *
     * @see moe.forpleuvoir.compose_minecraft.platform.ui.draw.vanillaDraw
     * @see moe.forpleuvoir.compose_minecraft.platform.ui.draw.postVanillaDraw
     */
    val vanillaDrawState = VanillaDrawState()

    /**
     * 当前组合提供的字符过滤器(默认全放行)。由 [setContent] 的组合在每次重组时写入,
     * 供 charTyped 输入分发使用(文本输入计划 §5,LocalCharFilter)。
     */
    private val charFilter = AtomicReference<(Int) -> Boolean>({ true })


    /**
     * Compose 侧请求的原版光标(I9 指针图标):由平台接入点 PlatformContext.setPointerIcon
     * 写入,经 [ComposeScreen.extractRenderState] 每帧通过原版
     * [net.minecraft.client.gui.GuiGraphicsExtractor.requestCursor] 提交 —— 走原版
     * per-frame 光标管线(帧末 applyCursor → Window.selectCursor,带去重),
     * 避免与原版光标重置互相覆盖。null = 无请求(原版默认光标)。
     *
     * 平台开放点:public —— 自定义 PlatformContext 的 setPointerIcon 覆写写入。
     */
    var desiredCursorType: CursorType? = null

    /**
     * 窗口像素尺寸(供 WindowInfo.containerSize,Dialog 居中/Popup 裁剪使用)。
     * 独立于 [scene] 的字段:不能在构造期间读取 scene 属性(CanvasLayersComposeScene
     * 构造即查询 containerSize,而 scene 尚未赋值完成,见 windowInfo 处注释),
     * 由 [renderFrame] 每帧与 resize 同源同步;场景构造期间保持 IntSize.Zero。
     *
     * 平台开放点:public —— 自定义 PlatformContext 的 windowInfo 读取。
     */
    var sceneContainerSize = IntSize.Zero
        private set

    /**
     * 已捕获的语义树所有者(复述系统数据源):由 PlatformContext.semanticsOwnerListener
     * 在场景/图层附着时登记,ComposeScreen 朗读时遍历全部 owner 的语义节点。
     * 主场景 owner 最先(init 时 onOwnerAppended),其后每个 Popup/Dialog 图层
     * 各一个 owner(顺序 = 图层栈)。
     */
    internal val capturedSemanticsOwners = mutableListOf<SemanticsOwner>()

    /**
     * 语义树变化回调(复述系统):owner 语义变化时触发,ComposeScreen 借此在
     * 焦点变化时补触发原版朗读调度。注意此回调在语义快照提交时被调用,非合成期间。
     *
     * 平台开放点:public —— 自定义 PlatformContext 的 semanticsOwnerListener 覆写触发。
     */
    var onSemanticsChanged: (() -> Unit)? = null

    /** 登记语义树所有者(复述系统,平台开放点:自定义 semanticsOwnerListener 调用) */
    fun addSemanticsOwner(semanticsOwner: SemanticsOwner) {
        capturedSemanticsOwners += semanticsOwner
    }

    /** 注销语义树所有者(复述系统,平台开放点:自定义 semanticsOwnerListener 调用) */
    fun removeSemanticsOwner(semanticsOwner: SemanticsOwner) {
        capturedSemanticsOwners -= semanticsOwner
    }

    /** 全部已登记语义树(复述系统读取入口) */
    fun getSemanticsOwners(): List<SemanticsOwner> = capturedSemanticsOwners.toList()

    /** 平台接入点实现(factory 注入,默认 [MinecraftPlatformContext]) */
    val platformContext: PlatformContext = platformContextFactory(this)

    private val scene: ComposeScene = CanvasLayersComposeScene(
        density = Density(density),
        size = IntSize(width.coerceAtLeast(1), height.coerceAtLeast(1)),
        // 主线程驱动:MC 的 extract/render 都在主线程,recompose 同步刷新
        coroutineContext = Dispatchers.Unconfined,
        platformContext = platformContext,
        // MC 每帧都会调用 render(),无需额外 invalidate 调度
        invalidate = {},
    )

    /** 设置场景内容(同 [ComposeScene.setContent]) */
    fun setContent(content: @Composable () -> Unit) {
        scene.setContent {
            // 根级 PopupHost:场景根自动挂载主机与宿主(业务弹层经 LocalPopupHost
            // 注册,由根 PopupHostOverlay 统一渲染,不污染业务父布局测量)。
            val popupHostState = remember { PopupHostState() }
            CompositionLocalProvider(
                LocalPopupHost provides popupHostState,
                // T.38:vanillaDraw 帧态(LocalVanillaDrawState),实例稳定不触发重组
                LocalVanillaDrawState provides vanillaDrawState,
            ) {
                // 读取 LocalCharFilter(业务方可经 CompositionLocalProvider 覆盖),
                // 写入场景侧引用供输入分发使用
                charFilter.set(LocalCharFilter.current)
                content()
                PopupHostOverlay()
            }
        }
    }

    /** 判断 codepoint 是否允许进入场景(文本输入计划 §5:过滤器位于输入分发链上) */
    fun isCharAccepted(codePoint: Int): Boolean = charFilter.get().invoke(codePoint)

    /**
     * 转发指针事件到场景(阶段 F)。坐标 = 像素(密度 1 时 1px == 1 GUI 单位,与场景
     * 尺寸一致);密度 >1f 时事件坐标仍为像素,由场景内部按 density 换算 dp。
     * 见 [ComposeScene.sendPointerEvent]。
     */
    fun sendPointerEvent(
        eventType: PointerEventType,
        position: Offset,
        scrollDelta: Offset = Offset.Zero,
        type: PointerType = PointerType.Mouse,
        keyboardModifiers: PointerKeyboardModifiers? = null,
        button: PointerButton? = null,
    ): PointerEventResult = scene.sendPointerEvent(
        eventType = eventType,
        position = position,
        scrollDelta = scrollDelta,
        type = type,
        keyboardModifiers = keyboardModifiers,
        button = button,
    )

    /** 转发键盘事件到场景(阶段 F)。返回 true 表示事件已被内容消费。 */
    fun sendKeyEvent(keyEvent: KeyEvent): Boolean = scene.sendKeyEvent(keyEvent)

    /** 调整场景尺寸(GUI 单位);仅在变化时触发重排 */
    fun resize(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (scene.size != IntSize(w, h)) {
            scene.size = IntSize(w, h)
        }
    }

    /**
     * 同步 Minecraft 窗口尺寸(像素,T.24:1:1,不再除 guiScale)并渲染当前帧:
     * 重组/布局/绘制到 [canvas],再提交到 [renderer] 收集器(gui 阶段由
     * GuiRendererMixin 提交)。由 [ComposeScreen.extractRenderState] 每帧调用
     * (调用前已写入 [VanillaDrawState.graphics] 当前帧原版 extractor)。
     *
     * 帧内 vanilla 回调时序:
     * 1. [VanillaDrawState.runFrameCallbacks]——前渲染(vanillaDraw):1:1 桥
     *    元素注入收集器列表头部 → 画在 Compose 内容之下;guiScale 通道走原版
     *    extractor(受原版 guiScale,时序由原版 GUI 阶段决定);
     * 2. `renderContext.render(...)`——Compose 内容收集与提交;
     * 3. [VanillaDrawState.runPostFrameCallbacks]——后渲染(postVanillaDraw):
     *    1:1 桥元素注入列表尾部 → 画在全部 Compose 内容之上;guiScale 通道仍
     *    走原版 extractor(画在 Compose 之下,见 KDoc 限制)。
     */
    fun renderFrame() {
        val windowState = mc.gameRenderer.gameRenderState().windowRenderState
        sceneContainerSize = IntSize(windowState.width, windowState.height)
        resize(
            width = windowState.width,
            height = windowState.height,
        )
        canvas.clearCommands()
        scene.render(canvas, System.nanoTime())
        // 当前帧原版 extractor(ComposeScreen 已写入)与 GUI 缩放系数(guiScale 通道换算用;
        // 1:1 通道不参与绘制,仅原版通道以 GUI 单位绘制时使用)
        val graphics = vanillaDrawState.graphics
        val guiScale = windowState.guiScale.toFloat().coerceAtLeast(1f)
        // T.38:每帧重放前渲染回调(绕开 Compose 图层"命令烘焙"脏标记缓存 ——
        // GraphicsLayer.record 静态帧不重跑 draw 块)。桥元素注入在
        // renderContext.render 之前 → 元素位于列表头部,画在 Compose 内容之下。
        vanillaDrawState.runFrameCallbacks(renderer, graphics, guiScale)
        renderContext.render(canvas, renderer)
        // T.38:每帧重放后渲染回调(在 renderContext.render 之后 → 元素位于列表
        // 尾部,画在全部 Compose 内容之上;guiScale 通道例外,见 KDoc)。
        vanillaDrawState.runPostFrameCallbacks(renderer, graphics, guiScale)
    }

    /** 释放场景(组合、Recomposer 等) */
    fun close() = scene.close()
}
