package moe.forpleuvoir.compose_minecraft.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.MinecraftPointerIcon
import androidx.compose.ui.input.pointer.MinecraftPointerIconKind
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.PointerEventResult
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import moe.forpleuvoir.compose_minecraft.platform.render.ComposeGuiRenderer
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderContext
import moe.forpleuvoir.compose_minecraft.platform.textinput.MinecraftTextInputService
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
     * 当前组合提供的字符过滤器(默认全放行)。由 [setContent] 的组合在每次重组时写入,
     * 供 charTyped 输入分发使用(文本输入计划 §5,LocalCharFilter)。
     */
    private val charFilter = AtomicReference<(Int) -> Boolean>({ true })

    /** 平台文本输入服务(IME Service):桥接 MC TextInputManager 与 Compose 编辑缓冲 */
    internal val textInputService = MinecraftTextInputService()

    /**
     * Compose 侧请求的原版光标(I9 指针图标):由平台接入点 [PlatformContext.setPointerIcon]
     * 写入,经 [ComposeScreen.extractRenderState] 每帧通过原版
     * [net.minecraft.client.gui.GuiGraphicsExtractor.requestCursor] 提交 —— 走原版
     * per-frame 光标管线(帧末 applyCursor → Window.selectCursor,带去重),
     * 避免与原版光标重置互相覆盖。null = 无请求(原版默认光标)。
     */
    internal var desiredCursorType: CursorType? = null
        private set

    private val scene: ComposeScene = CanvasLayersComposeScene(
        density = Density(density),
        size = IntSize(width.coerceAtLeast(1), height.coerceAtLeast(1)),
        // 主线程驱动:MC 的 extract/render 都在主线程,recompose 同步刷新
        coroutineContext = Dispatchers.Unconfined,
        platformContext = object : PlatformContext.Empty() {
            // 注入 MC IME 服务(替代 EmptyPlatformTextInputService 默认值)
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override val textInputService: PlatformTextInputService
                get() = this@MinecraftComposeScene.textInputService

            // 新版 API 会话(BasicTextField(state)):绑定 request 的 onEditCommand/value,
            // 使 preedit 事件能写入编辑缓冲;会话取消时解绑(IME 停止由 RootNodeOwner 的
            // textInputService.stopInput() 负责)
            @OptIn(ExperimentalComposeUiApi::class)
            override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
                this@MinecraftComposeScene.textInputService.bindRequest(request)
                try {
                    awaitCancellation()
                } finally {
                    this@MinecraftComposeScene.textInputService.unbindRequest(request)
                }
            }

            // I9 指针图标:Compose 光标请求 → MC 原版光标类型(经原版 per-frame 管线生效,
            // 由 RootNodeOwner.PointerIconServiceImpl 在指针 Enter/Exit 时调用)
            override fun setPointerIcon(pointerIcon: PointerIcon) {
                desiredCursorType = pointerIcon.toMinecraftCursorType()
            }
        },
        // MC 每帧都会调用 render(),无需额外 invalidate 调度
        invalidate = {},
    )

    /** 设置场景内容(同 [ComposeScene.setContent]) */
    fun setContent(content: @Composable () -> Unit) {
        scene.setContent {
            // 读取 LocalCharFilter(业务方可经 CompositionLocalProvider 覆盖),
            // 写入场景侧引用供输入分发使用
            charFilter.set(LocalCharFilter.current)
            content()
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
     * GuiRendererMixin 提交)。由 [ComposeScreen.extractRenderState] 每帧调用。
     */
    fun renderFrame() {
        val windowState = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState
        resize(
            width = windowState.width,
            height = windowState.height,
        )
        canvas.clearCommands()
        scene.render(canvas, System.nanoTime())
        renderContext.render(canvas, renderer)
    }

    /** 释放场景(组合、Recomposer 等) */
    fun close() = scene.close()
}

/**
 * Compose [PointerIcon] → MC 原版 [CursorType] 映射(I9 指针图标):
 * - Default → 标准箭头;Crosshair → 十字;Text → I 形(文本);Hand → 手型;
 * - 未知/自定义图标实现回退默认箭头(与原占位行为一致,不触发错误)。
 */
private fun PointerIcon.toMinecraftCursorType(): CursorType = when ((this as? MinecraftPointerIcon)?.kind) {
    MinecraftPointerIconKind.Crosshair -> CursorTypes.CROSSHAIR
    MinecraftPointerIconKind.Text -> CursorTypes.IBEAM
    MinecraftPointerIconKind.Hand -> CursorTypes.POINTING_HAND
    else -> CursorTypes.ARROW
}
