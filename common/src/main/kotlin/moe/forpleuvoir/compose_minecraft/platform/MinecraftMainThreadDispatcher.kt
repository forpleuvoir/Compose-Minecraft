package moe.forpleuvoir.compose_minecraft.platform

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import moe.forpleuvoir.compose_minecraft.mc

/**
 * MC 主线程调度器:调用线程已是 MC 主线程时不派发(就地执行),其他线程排到主线程执行。
 *
 * 供 [moe.forpleuvoir.compose_minecraft.platform.screen.MinecraftComposeScene] 的 coroutineContext
 * 使用:该上下文承载 Recomposer 与场景内的效果协程,重组与 applyChanges 要求串行在固定线程上,
 * 而恢复点可能来自任意线程 —— 全局快照写通知由 `GlobalSnapshotManager` 在 `Dispatchers.Default`
 * 上发出,`delay` 到期恢复由延时调度线程发出,两者都经 `FlushCoroutineDispatcher.dispatch`
 * 的 `scope.launch` 落到本调度器,由本调度器把这些恢复收敛回 MC 主线程。
 *
 * 主线程上 [isDispatchNeeded] 为 false:组合、测量、效果在调用线程(即主线程)立即推进,不多一次派发。
 * 其他线程的恢复先落进场景自己的待执行队列(`FlushCoroutineDispatcher.immediateTasks`),由下一次
 * `MinecraftComposeScene.renderFrame` 经 `BaseComposeScene.render` 的 `performScheduledEffects()` /
 * `performScheduledRecomposerTasks()` flush 在主线程执行(帧级延迟)。`mc.execute` 是**当帧不渲染的场景**
 * (保留复活的父屏、空闲的覆盖层场景等)的兜底通道,由 `Minecraft.runTick` 的 scheduledExecutables 段
 * 每 tick 在主线程执行一次;该通道落到已 flush 过的 block 时按 `immediateTasks.remove` 的返回值跳过。
 */
internal object MinecraftMainThreadDispatcher : CoroutineDispatcher() {

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !mc.isSameThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        mc.execute(block)
    }
}
