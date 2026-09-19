package moe.forpleuvoir.compose_minecraft.platform.screen

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import moe.forpleuvoir.compose_minecraft.mc
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compose 屏幕的全局默认值。
 *
 * 业务模组(如 ibuki_gourd)可把这些值绑定到自己的配置文件,无需逐屏传参。
 */
object ComposeScreenDefaults {

    /**
     * 新开的 [ComposeScreen] 是否**禁用**世界渲染。
     *
     * 默认 `false` = 不禁用 = 照常渲染世界(与原版行为一致,开着 UI 也能看到世界)。
     * 置 `true` 则新屏默认停画世界(省掉世界渲染开销),需要世界当背景的屏再用
     * [WorldBackdropEffect] / [WorldBackdropWhileAnimating] 按需借用,或逐屏传
     * [ComposeScreen.disableWorldRender] = false。
     *
     * 业务模组可把本值绑定到自己的配置文件。
     */
    var disableWorldRenderByDefault: Boolean = false

    /**
     * 新开的 [ComposeScreen] 默认使用的进出场动画。
     *
     * 默认 [ScreenAnimation.Default](整屏滑入 + 淡入;关闭时反向播完才关屏)。
     * 不想要动画就置 [ScreenAnimation.None],要统一调数值改 [ScreenAnimationDefaults]。
     *
     * 业务模组可把本值绑定到自己的配置文件。
     */
    var animation: ScreenAnimation = ScreenAnimation.Default
}

/**
 * 世界渲染借用登记处(引用计数)。
 *
 * 计数 > 0 时正常渲染世界;计数为 0 且当前屏是 [ComposeScreen] 时,由
 * `GameRendererMixin` 在 `GameRenderer.renderLevel` 头部取消本帧的世界渲染。
 *
 * 借用的生命周期必须由**真实条件**驱动(屏的存活、动画是否还在跑),不能靠时长估算:
 * - 屏级:[ComposeScreen] 打开即持有、销毁即释放;
 * - 动画级:[WorldBackdropWhileAnimating](非 idle 期间持有,idle 即释放);
 * - 组合级:[WorldBackdropEffect]。
 *
 * 线程:读取发生在渲染线程(主线程),写入可能来自组合/协程,故用原子计数。
 */
object WorldBackdrop {

    private val holds = AtomicInteger(0)

    /** 是否有人借用世界背景(引用计数 > 0)。 */
    val isRequested: Boolean get() = holds.get() > 0

    /** 借用世界渲染;返回的句柄可幂等 [Handle.close]。 */
    fun acquire(): Handle {
        holds.incrementAndGet()
        return Handle()
    }

    /**
     * 混入入口:本帧是否应跳过世界渲染。
     *
     * 仅当无人借用**且**当前屏是 [ComposeScreen] 时才跳过 —— 原版屏与其他模组的屏
     * (含 `Minecraft.grabPanoramixScreenshot` 这类走 `renderLevel` 的原版路径)不受影响。
     */
    @JvmStatic
    fun shouldSkipLevelRender(): Boolean = !isRequested && mc.gui.screen() is ComposeScreen

    /** 借用句柄,线程安全且幂等。 */
    class Handle internal constructor() : AutoCloseable {

        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                holds.decrementAndGet()
            }
        }
    }
}

/**
 * 在 [active] 为 true 期间借用世界渲染(与组合生命周期绑定)。
 *
 * @param active 传业务自己的条件(例如"入场动画还没结束")即可,不需要估算时长
 */
@Composable
fun WorldBackdropEffect(active: Boolean = true) {
    DisposableEffect(active) {
        if (!active) {
            onDispose { }
        } else {
            val handle = WorldBackdrop.acquire()
            onDispose { handle.close() }
        }
    }
}

/**
 * 过渡动画(入场或出场)非 idle 期间借用世界渲染。
 *
 * 典型用法:屏已禁用世界渲染(`disableWorldRender = true`,或全局默认禁用),
 * 但入场时希望先看到世界再被 UI 盖住 —— 把驱动入场动画的那个 state 传进来即可;
 * 动画一 idle 就自动停画世界,**判据是动画是否还在跑,不是配置里的时长**。
 */
@Composable
fun WorldBackdropWhileAnimating(state: MutableTransitionState<Boolean>) {
    WorldBackdropEffect(active = !state.isIdle)
}
