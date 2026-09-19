package moe.forpleuvoir.compose_minecraft.platform.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import moe.forpleuvoir.compose_minecraft.mc
import net.minecraft.client.gui.screens.Screen

/**
 * 内置对话框动画的默认数值。
 *
 * 全部是 `var`:业务模组(如 ibuki_gourd)可绑定到自己的配置文件 —— 改完对**之后新建**的
 * 对话框生效(`DialogAnimation.Preset()` 的默认值在调用时求值,故无需改调用方)。
 * 单个对话框要单独调,直接传 `DialogAnimation.Preset(...)`;要完全换掉动画,传
 * `DialogAnimation.Custom { ... }`。缓动也可整体替换(`LinearEasing` 等任意 [Easing])。
 */
object DialogAnimationDefaults {

    /** 遮罩色(默认黑 40%)。 */
    var scrimColor: Color = Color.Black.copy(alpha = 0.4f)

    /** 进出场时长(毫秒)。 */
    var durationMillis: Int = 200

    /** 内容入场起始缩放 / 出场目标缩放。 */
    var initialScale: Float = 0.8f

    /** 进出场缓动(默认 `CubicBezierEasing(0f, 0f, 0.2f, 1f)`,可换成任意 [Easing])。 */
    var easing: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
}

/**
 * 对话框的全局默认值(业务模组可绑定到自己的配置文件)。
 *
 * 与 [ComposeScreenDefaults] **分开**:对话框是覆盖层,背后通常有父屏/世界
 * (半透明遮罩会透出),需要独立于全屏屏的设定。
 */
object DialogComposeScreenDefaults {

    /** 新建对话框是否**禁用**世界渲染;默认 `false` = 不禁用 = 照常渲染世界。 */
    var disableWorldRender: Boolean = false

    /**
     * 新建对话框默认使用的动画;默认 [DialogAnimation.Default](平台内置)。
     *
     * 想全局改对话框动画(比如整体换成 [DialogAnimation.None] 或带自定义数值的
     * [DialogAnimation.Preset])改这里即可,不必在每个调用点传参;
     * 单个对话框仍可用 `animation = ...` 覆盖。
     */
    var animation: DialogAnimation = DialogAnimation.Default
}

/**
 * 对话框进出场动画配置。
 *
 * 无论选哪种,**关闭时序都由平台协调**(见 [ScreenCloseCoordinator]):
 * 关闭请求到达 → 播完出场动画 → 才真正关屏。
 */
sealed interface DialogAnimation {

    /** 平台内置:遮罩淡入淡出 + 内容 fade/缩放入出场(数值见 [DialogAnimationDefaults])。 */
    object Default : DialogAnimation

    /** 数值可调的内置动画。 */
    data class Preset(
        val scrimColor: Color = DialogAnimationDefaults.scrimColor,
        val durationMillis: Int = DialogAnimationDefaults.durationMillis,
        val initialScale: Float = DialogAnimationDefaults.initialScale,
    ) : DialogAnimation

    /**
     * 完全自定义:包装器内部自行驱动动画。
     *
     * 要参与关闭时序,内部用 [rememberScreenVisibilityState] 或 [ScreenExitEffect] 接入;
     * 不接的话由平台的"帧静默自动判定"兜底。
     */
    class Custom(
        val wrapper: @Composable (@Composable () -> Unit) -> Unit,
    ) : DialogAnimation

    /** 不做进出场动画(请求关闭即关屏)。 */
    object None : DialogAnimation
}

/**
 * 构造一个 MC [Screen] 形态的 Compose 对话框(**不自动打开**)。
 *
 * 与  的"场景内 Dialog 图层"区别:本函数返回独立的原版屏幕,可跨屏覆盖、
 * 独立持有输入与 IME 焦点;图层版 Dialog 仍在同一 Compose 场景内。
 *
 * 关闭语义:Esc([dismissOnBackPress])/ 点击遮罩([dismissOnClickOutside])/
 * 业务调用 [ComposeScreen.requestClose] → 播完出场动画 → 关屏 → 触发 [onDismiss]。
 *
 * @param parent 关闭后返回的父屏(默认当前屏);null = 关闭回游戏 / 主菜单
 * @param renderParentScreen 是否把父屏渲染在对话框之下(默认 true,配合半透明遮罩)
 * @param disableWorldRender 是否**禁用**世界渲染;默认取对话框自己的全局值
 *   [DialogComposeScreenDefaults.disableWorldRender](默认 false = 不禁用 = 渲染世界)。
 *   对话框背后是原版屏或世界时(半透明遮罩会透出),应保持 false,否则透出的是空背景
 * @param pauseGame 是否暂停游戏;默认继承父屏状态(父屏不暂停则对话框也不暂停)
 * @param onDismiss 真正关屏后的回调
 */
fun DialogComposeScreen(
    parent: Screen? = mc.gui.screen(),
    renderParentScreen: Boolean = true,
    disableWorldRender: Boolean = DialogComposeScreenDefaults.disableWorldRender,
    pauseGame: Boolean = parent?.isPauseScreen ?: true,
    density: Float = 1f,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    animation: DialogAnimation = DialogComposeScreenDefaults.animation,
    content: @Composable () -> Unit,
): ComposeScreen {
    val screen = ComposeScreen(
        parent = parent,
        renderParentScreen = renderParentScreen,
        density = density,
        disableWorldRender = disableWorldRender,
        pauseGame = pauseGame,
        closeOnEsc = dismissOnBackPress,
        // 对话框自己的动画(遮罩 + 面板缩放)已覆盖进出场,屏级动画必须关掉,否则会叠加一次整屏滑动
        animation = ScreenAnimation.None,
        // 遮罩把父屏留在原地,不让父屏退场(否则会出现"父屏先消失、遮罩才盖上来"的闪动)
        exitParentOnOpen = false,
        content = { DialogContent(dismissOnClickOutside, animation, content) },
    )
    onDismiss?.let(screen::onClosed)
    return screen
}

/**
 * 构造并打开一个对话框屏幕(主线程执行 `setScreen`)。
 *
 * @return 创建的屏幕实例,可用于 [ComposeScreen.requestClose] / [ComposeScreen.onClosed]
 */
fun openDialogComposeScreen(
    parent: Screen? = mc.gui.screen(),
    renderParentScreen: Boolean = true,
    disableWorldRender: Boolean = DialogComposeScreenDefaults.disableWorldRender,
    pauseGame: Boolean = parent?.isPauseScreen ?: true,
    density: Float = 1f,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    animation: DialogAnimation = DialogComposeScreenDefaults.animation,
    content: @Composable () -> Unit,
): ComposeScreen {
    val screen = DialogComposeScreen(
        parent = parent,
        renderParentScreen = renderParentScreen,
        disableWorldRender = disableWorldRender,
        pauseGame = pauseGame,
        density = density,
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        onDismiss = onDismiss,
        animation = animation,
        content = content,
    )
    // 父屏为 ComposeScreen 时标记可复活(与 ComposeScreen.open 同约定):
    // 对话框关闭返回父屏后,父屏场景与组合状态保留,不会重头重建
    (parent as? ComposeScreen)?.reopenable = true
    // Screen 切换统一走主线程(调用方可能来自协程/事件线程)
    mc.execute { mc.gui.setScreen(screen) }
    return screen
}

/** 按 [animation] 分发;自定义包装器直接接管内容。 */
@Composable
private fun DialogContent(
    dismissOnClickOutside: Boolean,
    animation: DialogAnimation,
    content: @Composable () -> Unit,
) {
    when (animation) {
        DialogAnimation.None -> Box(Modifier.fillMaxSize()) { content() }

        is DialogAnimation.Custom -> animation.wrapper(content)

        DialogAnimation.Default -> AnimatedDialogLayer(
            scrimColor = DialogAnimationDefaults.scrimColor,
            durationMillis = DialogAnimationDefaults.durationMillis,
            initialScale = DialogAnimationDefaults.initialScale,
            dismissOnClickOutside = dismissOnClickOutside,
            content = content,
        )

        is DialogAnimation.Preset -> AnimatedDialogLayer(
            scrimColor = animation.scrimColor,
            durationMillis = animation.durationMillis,
            initialScale = animation.initialScale,
            dismissOnClickOutside = dismissOnClickOutside,
            content = content,
        )
    }
}

/**
 * 内置对话框动画:遮罩与内容各一个 [AnimatedVisibility],共用同一个
 * [rememberScreenVisibilityState](第 2 层接入 → 出场动画播完才放行关屏);
 * 内容侧同步广播 [LocalScreenAnimationProgress] 供子组件跟随。
 */
@Composable
private fun AnimatedDialogLayer(
    scrimColor: Color,
    durationMillis: Int,
    initialScale: Float,
    dismissOnClickOutside: Boolean,
    content: @Composable () -> Unit,
) {
    val visibility = rememberScreenVisibilityState()
    val coordinator = LocalScreenCloseCoordinator.current
    val easing = DialogAnimationDefaults.easing
    val animationSpec = tween<Float>(durationMillis, easing = easing)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(animationSpec = animationSpec),
            exit = fadeOut(animationSpec = animationSpec),
        ) {
            val dismissModifier = if (dismissOnClickOutside) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    coordinator?.requestClose()
                }
            } else {
                Modifier
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrimColor)
                    .then(dismissModifier)
            )
        }

        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(animationSpec = animationSpec) +
                    scaleIn(initialScale = initialScale, animationSpec = animationSpec),
            exit = fadeOut(animationSpec = animationSpec) +
                    scaleOut(targetScale = initialScale, animationSpec = animationSpec),
        ) {
            val progress by transition.animateFloat(label = "screenAnimationProgress") {
                when (it) {
                    EnterExitState.PreEnter -> 0f
                    EnterExitState.Visible -> 1f
                    EnterExitState.PostExit -> 0f
                }
            }
            CompositionLocalProvider(LocalScreenAnimationProgress provides progress) {
                content()
            }
        }
    }
}
