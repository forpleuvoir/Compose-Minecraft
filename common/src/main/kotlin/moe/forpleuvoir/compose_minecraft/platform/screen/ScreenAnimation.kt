package moe.forpleuvoir.compose_minecraft.platform.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged

/**
 * 普通屏幕([ComposeScreen])的进出场动画配置。
 *
 * 与对话框([DialogAnimation])分开:屏幕通常做"整屏滑入滑出",对话框做"遮罩 + 面板缩放"。
 * 进出场都由平台驱动,**业务不需要接关闭时序**:
 * - 入场:每次(重新)入场时进度 0 → 1(动画请求信号变化即重跑,
 *   子屏提前触发的交叉过渡也走这条);
 * - 出场:作为关闭流程的参与者(第 3 层挂起式),播完才真正关屏。
 */
sealed interface ScreenAnimation {

    /** 平台内置:整屏自下而上滑入 + 淡入,出场反向(数值见 [ScreenAnimationDefaults])。 */
    object Default : ScreenAnimation

    /** 数值可调的内置动画(缓动与是否淡入淡出取 [ScreenAnimationDefaults] 的全局值)。 */
    data class Preset(
        val durationMillis: Int = ScreenAnimationDefaults.durationMillis,
        val slideFraction: Float = ScreenAnimationDefaults.slideFraction,
    ) : ScreenAnimation

    /**
     * 完全自定义:包装器内部自行驱动动画。
     *
     * 要参与关闭时序,内部用 [rememberScreenVisibilityState] 或 [ScreenExitEffect] 接入;
     * 不接则由平台的"帧静默自动判定"兜底。
     */
    class Custom(
        val wrapper: @Composable (@Composable () -> Unit) -> Unit,
    ) : ScreenAnimation

    /** 无进出场动画(开屏即显示,请求关闭即关屏)。 */
    object None : ScreenAnimation
}

/**
 * 普通屏幕动画的全局数值(业务模组可绑定到自己的配置文件)。
 *
 * 全部是 `var`:改完对**之后新建**的屏幕生效(`ScreenAnimation.Preset()` 的默认值在调用时求值)。
 */
object ScreenAnimationDefaults {

    /** 进出场时长(毫秒)。 */
    var durationMillis: Int = 220

    /**
     * 滑入起始位移 / 滑出目标位移 = 屏高 × 本值。
     *
     * 0 = 只淡入淡出(不滑动);正数 = 自下而上滑入(默认 0.08,幅度克制)。
     */
    var slideFraction: Float = 0.08f

    /** 是否同时淡入淡出(false = 只滑动;两项都关则等同于 [ScreenAnimation.None])。 */
    var fade: Boolean = true

    /** 缓动(默认 `CubicBezierEasing(0f, 0f, 0.2f, 1f)`,可换成任意 [Easing])。 */
    var easing: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
}

/**
 * 屏幕根动画宿主:按 [animation] 包装内容。
 *
 * 平台在 [ComposeScreen] 的 `setContent` 里调用它,内容因此天然位于
 * [LocalScreenCloseCoordinator] / [LocalScreenExitScope] 之内。
 *
 * @param animationSignal 动画请求信号:值变化即朝 [animationTarget] 重跑一次进度动画
 *   (首次入场时仍为 0,由动画宿主自行从 0 入场)
 * @param animationTarget 目标进度:1 = 完全显示(入场),0 = 完全退场(视觉退场,不关屏)
 * @param onProgress 进度回报(1 = 完全显示,0 = 完全退场):写入 [ComposeScreen.animationProgress]
 */
@Composable
internal fun ScreenAnimationHost(
    animation: ScreenAnimation,
    animationSignal: Int,
    animationTarget: Float,
    onProgress: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    when (animation) {
        ScreenAnimation.None -> content()

        is ScreenAnimation.Custom -> animation.wrapper(content)

        ScreenAnimation.Default -> AnimatedScreenLayer(
            durationMillis = ScreenAnimationDefaults.durationMillis,
            slideFraction = ScreenAnimationDefaults.slideFraction,
            animationSignal = animationSignal,
            animationTarget = animationTarget,
            onProgress = onProgress,
            content = content,
        )

        is ScreenAnimation.Preset -> AnimatedScreenLayer(
            durationMillis = animation.durationMillis,
            slideFraction = animation.slideFraction,
            animationSignal = animationSignal,
            animationTarget = animationTarget,
            onProgress = onProgress,
            content = content,
        )
    }
}

/**
 * 内置整屏动画:一个进度值(1 = 完全显示)同时驱动位移与透明度。
 *
 * - 入场与视觉退场共用一个进度与一个目标值:**谁最后请求谁生效**(打开方向让父屏退场、
 *   关闭方向让父屏入场),不会出现两个动画协程抢同一个 Animatable;
 * - 出场:作为关闭流程的参与者(第 3 层挂起式),播完才真正关屏。
 * - 位移与透明度写在**同一个 graphicsLayer** 里(CMP 的 `alpha(<1)` 等价于 clip 图层,
 *   分开写会把 translationY 裁掉)。
 */
@Composable
private fun AnimatedScreenLayer(
    durationMillis: Int,
    slideFraction: Float,
    animationSignal: Int,
    animationTarget: Float,
    onProgress: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val easing = ScreenAnimationDefaults.easing
    val spec = tween<Float>(durationMillis, easing = easing)
    val progress = remember { Animatable(1f) }
    var screenHeight by remember { mutableStateOf(1f) }

    // 入场 / 视觉退场 / 复活入场 —— 共用一个信号与目标值:
    // 每次请求都朝当前目标动画,后到者胜,不会出现两个协程抢同一个 Animatable。
    // 首次组合(信号 0)从 0 入场
    LaunchedEffect(animationSignal) {
        if (animationSignal == 0) {
            progress.snapTo(0f)
        }
        progress.animateTo(animationTarget, spec)
    }

    // 真正关闭时的出场:挂起式参与者,播完才放行关屏
    ScreenExitEffect {
        progress.animateTo(0f, spec)
    }

    // 进度回报(供 ComposeScreen.animationProgress / 诊断交叉过渡)
    LaunchedEffect(progress) {
        snapshotFlow { progress.value }.collect(onProgress)
    }

    val slide = screenHeight * slideFraction
    val fade = ScreenAnimationDefaults.fade

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeight = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                translationY = (1f - progress.value) * slide
                if (fade) alpha = progress.value
            }
    ) {
        CompositionLocalProvider(LocalScreenAnimationProgress provides progress.value) {
            content()
        }
    }
}
