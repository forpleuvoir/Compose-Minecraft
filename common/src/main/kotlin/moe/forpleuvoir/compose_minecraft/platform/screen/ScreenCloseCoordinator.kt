package moe.forpleuvoir.compose_minecraft.platform.screen

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.first

/** 屏幕关闭阶段。 */
enum class ScreenCloseState {
    Open,
    Closing,
    Closed,
}

/** 退出动画参与者句柄:动画跑完时调用 [complete](幂等)。 */
interface CloseAnimationHandle {

    fun complete()
}

/**
 * 屏幕关闭协调器(句柄栅栏)。
 *
 * [requestClose] 只把状态推进到 [ScreenCloseState.Closing],**真正的关屏**
 * (`setScreen` 父屏 / 销毁场景)由 [ComposeScreen] 在 [ScreenCloseState.Closed] 之后执行 ——
 * "退出动画播放完再关屏"因此成为默认行为,而不是让业务自己安排时序。
 *
 * 收尾条件(两条路并联,谁先满足谁生效):
 * 1. 有显式参与者(经 [rememberScreenVisibilityState] / [ScreenExitEffect] / [ScreenExitScope] 注册)
 *    → 全部 [CloseAnimationHandle.complete] 之后收尾(**精确**);
 * 2. 无显式参与者 → [ComposeScreen] 走"帧静默"自动判定:连续若干帧场景无待处理工作
 *    即视为动画已停下(**近似,零 API 负担**)。
 *
 * 两条路都受 [ComposeScreen] 的 5 秒上限约束,不会因动画永不结束而卡住屏幕。
 *
 * 线程:仅游戏主线程访问(组合 / 渲染 / 输入同线程)。
 */
class ScreenCloseCoordinator {

    private var closeState by mutableStateOf(ScreenCloseState.Open)

    private val handles = linkedSetOf<HandleImpl>()

    private var finished = false

    private var closeCallback: (() -> Unit)? = null

    /** 关屏回调是否已触发(一次性语义,见 [invokeClosedCallback])。 */
    private var callbackInvoked = false

    val state: ScreenCloseState get() = closeState

    val isClosing: Boolean get() = closeState == ScreenCloseState.Closing

    val isClosed: Boolean get() = closeState == ScreenCloseState.Closed

    /** 是否已有显式动画参与者(无参与者时由平台按"帧静默"自动判定)。 */
    val hasAnimationParticipants: Boolean get() = handles.isNotEmpty()

    /**
     * 复活代数:每次 [reset](屏幕被重新展示)自增。
     *
     * 内容侧的可见性状态([rememberScreenVisibilityState])以它为 key 重新入场 ——
     * 否则可复活屏复用后,上一次关闭时被置为 false 的 targetState 会让屏一直不可见。
     */
    var generation: Int by mutableStateOf(0)
        private set

    /** 请求关闭(幂等);重复调用无副作用。 */
    fun requestClose() {
        if (closeState != ScreenCloseState.Open) return
        closeState = ScreenCloseState.Closing
        settleIfNoParticipants()
    }

    /** 注册退出动画参与者;句柄漏 [CloseAnimationHandle.complete] 会挡住收尾(有 5 秒上限兜底)。 */
    internal fun registerAnimation(): CloseAnimationHandle = HandleImpl().also(handles::add)

    /** 真正关屏(场景销毁、父屏已恢复)之后的回调。 */
    fun onClosed(block: () -> Unit) {
        closeCallback = block
    }

    /** 触发关屏回调(**一次性**:复位与重复收尾都不会重复回调)。 */
    internal fun invokeClosedCallback() {
        if (callbackInvoked) return
        callbackInvoked = true
        closeCallback?.invoke()
    }

    /** 强制收尾:跳过剩余参与者(上限兜底 / 屏被替换时使用)。 */
    internal fun finishNow() {
        handles.clear()
        performFinish()
    }

    /** 屏幕被重新展示(可复活屏复用时)复位为打开状态。 */
    internal fun reset() {
        if (closeState == ScreenCloseState.Open && !finished) return
        handles.clear()
        finished = false
        closeState = ScreenCloseState.Open
        generation++
        callbackInvoked = false
    }

    private fun settleIfNoParticipants() {
        if (isClosing && handles.isEmpty()) {
            performFinish()
        }
    }

    private fun performFinish() {
        if (finished) return
        finished = true
        closeState = ScreenCloseState.Closed
    }

    private inner class HandleImpl : CloseAnimationHandle {

        private var done = false

        override fun complete() {
            if (done) return
            done = true
            handles.remove(this)
            settleIfNoParticipants()
        }
    }
}

/** 当前屏的关闭协调器(null = 不在 [ComposeScreen] 内容中)。 */
val LocalScreenCloseCoordinator = staticCompositionLocalOf<ScreenCloseCoordinator?> { null }

/**
 * 动画推进度广播:入场 0 → 1、出场 1 → 0;无动画时为 1。
 *
 * 供内容里的小组件跟着一起淡入/缩放,**不必各自注册退出句柄**
 * (平台内置对话框动画会写入本值;业务自定义动画可自行 provide)。
 */
val LocalScreenAnimationProgress = compositionLocalOf { 1f }

/**
 * 退出参与者作用域:供内容里的子组件注册自己的退出动画句柄
 * (`LocalScreenExitScope.current?.hold()`)。
 */
interface ScreenExitScope {

    fun hold(): CloseAnimationHandle
}

/** 当前屏的退出参与者作用域(null = 不在 [ComposeScreen] 内容中)。 */
val LocalScreenExitScope = staticCompositionLocalOf<ScreenExitScope?> { null }

/**
 * 第 2 层(声明式):用返回的可见性状态驱动 `AnimatedVisibility`,
 * 关闭请求到达时自动播放 exit 动画,动画 idle 后放行关屏。
 *
 * ```kotlin
 * val visible = rememberScreenVisibilityState()
 * AnimatedVisibility(visibleState = visible, exit = fadeOut() + scaleOut(0.8f)) { Content() }
 * ```
 *
 * 句柄在组合期就注册(不影响关闭时序),动画 `isIdle && !targetState` 时视为完成。
 *
 * 屏被复活([ScreenCloseCoordinator.reset],可复活屏复用场景)时按 [ScreenCloseCoordinator.generation]
 * **重新入场**:解句柄重建、targetState 回到 true,避免上一次关闭留下的 false 让屏永久不可见。
 */
@Composable
fun rememberScreenVisibilityState(): MutableTransitionState<Boolean> {
    val coordinator = LocalScreenCloseCoordinator.current
    val state = remember { MutableTransitionState(false) }
    val generation = coordinator?.generation ?: 0
    val handle = remember(coordinator, generation) { coordinator?.registerAnimation() }

    LaunchedEffect(coordinator, handle, generation) {
        if (coordinator == null || handle == null) return@LaunchedEffect
        state.targetState = true
        snapshotFlow { coordinator.isClosing }.first { it }
        state.targetState = false
        snapshotFlow { state.isIdle && !state.targetState }.first { it }
        handle.complete()
    }

    DisposableEffect(handle) {
        onDispose { handle?.complete() }
    }

    return state
}

/**
 * 第 3 层(挂起式,任意动画):关闭请求到达时启动 [block],块结束(正常或异常)即视为
 * 该参与者完成;屏被强制收尾 / 场景销毁时协程取消。
 *
 * ```kotlin
 * ScreenExitEffect {
 *     scrim.animateTo(0f, tween(200))
 *     panel.animateTo(0f, tween(150))
 * }
 * ```
 *
 * 句柄同样在组合期注册 —— 只有真的注册了参与者,平台才会等它。
 */
@Composable
fun ScreenExitEffect(block: suspend () -> Unit) {
    val coordinator = LocalScreenCloseCoordinator.current ?: return
    val currentBlock by rememberUpdatedState(block)
    val generation = coordinator.generation
    val handle = remember(coordinator, generation) { coordinator.registerAnimation() }

    LaunchedEffect(coordinator, handle, generation) {
        snapshotFlow { coordinator.isClosing }.first { it }
        try {
            currentBlock()
        } finally {
            handle.complete()
        }
    }

    DisposableEffect(handle) {
        onDispose { handle.complete() }
    }
}
