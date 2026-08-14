package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.state.gui.GuiRenderState

/**
 * Minecraft 平台的 Compose Scene 宿主(阶段 D)。
 *
 * 包装 CMP 移植的 [CanvasLayersComposeScene]:
 * - 密度固定 1f,场景尺寸 = Minecraft GUI 单位(窗口 px / guiScale),与 GuiRenderer
 *   的 GUI 正交投影一致,1dp == 1 GUI 单位;
 * - 由 [ComposeScreen](原版 Screen 桥接)每帧调用 [renderFrame],把绘制命令记录进
 *   [MinecraftCanvas] 并提交到当前帧的 [GuiRenderState](直接进入当前 RenderTarget,
 *   无离屏纹理);
 * - 输入(pointer/key)、文字渲染、弹出层等为后续阶段。
 */
@OptIn(InternalComposeUiApi::class)
class MinecraftComposeScene(
    width: Int,
    height: Int,
) {
    /** 每帧命令记录的画布(不分配 CPU/GPU 位图,纯命令列表) */
    private val canvas = MinecraftCanvas()

    private val renderContext = MinecraftRenderContext()

    private val scene: ComposeScene = CanvasLayersComposeScene(
        density = Density(1f),
        size = IntSize(width.coerceAtLeast(1), height.coerceAtLeast(1)),
        // 主线程驱动:MC 的 extract/render 都在主线程,recompose 同步刷新
        coroutineContext = Dispatchers.Unconfined,
        platformContext = PlatformContext.Empty(),
        // MC 每帧都会调用 render(),无需额外 invalidate 调度
        invalidate = {},
    )

    /** 设置场景内容(同 [ComposeScene.setContent]) */
    fun setContent(content: @Composable () -> Unit) = scene.setContent(content)

    /**
     * 转发指针事件到场景(阶段 F)。坐标 = GUI 单位(密度 1,与场景尺寸一致),
     * 不需要任何换算。见 [ComposeScene.sendPointerEvent]。
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

    /** 渲染一帧:重组/布局/绘制到 [canvas],再提交到 [renderState] */
    fun render(renderState: GuiRenderState) {
        canvas.clearCommands()
        scene.render(canvas, System.nanoTime())
        renderContext.render(canvas, renderState)
    }

    /**
     * 同步 Minecraft 窗口尺寸(GUI 单位 = 窗口 px / guiScale)并渲染当前帧。
     * 由 [ComposeScreen.extractRenderState] 每帧调用;仅尺寸变化时才触发重排。
     */
    fun renderFrame() {
        val windowState = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState
        resize(
            width = windowState.width / windowState.guiScale,
            height = windowState.height / windowState.guiScale,
        )
        render(Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState)
    }

    /** 释放场景(组合、Recomposer 等) */
    fun close() = scene.close()
}
