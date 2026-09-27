package moe.forpleuvoir.compose_minecraft.platform.render

import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * 临时性能探针：按「帧内阶段」累计 CPU 耗时，每 [WINDOW_FRAMES] 帧打一行汇总
 * （每阶段：每帧均值 / 调用次数 / 单次峰值），按每帧均值从大到小排序。
 *
 * 用法：
 * - `ComposeGuiProfiler.measure("阶段名") { ... }` —— 包住要计时的代码块；
 * - `ComposeGuiProfiler.count("计数名", n)` —— 只计数；
 * - 每帧末尾调用一次 [frame]（在帧入口最后一行）。
 *
 * 不需要时把 [ENABLED] 置 false（探针变成空调用，各调用点保留即可），
 * 或连同调用点一起删除。
 */
object ComposeGuiProfiler {

    /** 总开关。 */
    const val ENABLED: Boolean = true

    /** 汇总窗口（帧）。60 FPS 下 120 帧 ≈ 2 秒。 */
    private const val WINDOW_FRAMES: Int = 120

    /** 低于该每帧均值（毫秒）的阶段不打印，避免噪声。 */
    private const val MIN_PER_FRAME_MS: Double = 0.01

    private val logger = LoggerFactory.getLogger("ComposeMinecraft/Profiler")

    private class Acc {
        var nanos: Long = 0L
        var calls: Int = 0
        var maxNanos: Long = 0L
    }

    private val stats = LinkedHashMap<String, Acc>()
    private val gauges = LinkedHashMap<String, Int>()
    private var frames = 0

    /** 包住一个阶段计时。 */
    fun <T> measure(name: String, block: () -> T): T {
        if (!ENABLED) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            add(name, System.nanoTime() - start, 1)
        }
    }

    /** 直接累加一段耗时（已在别处取到 nanoTime 时用）。 */
    fun add(name: String, nanos: Long, calls: Int = 1) {
        if (!ENABLED) return
        val acc = stats.getOrPut(name) { Acc() }
        acc.nanos += nanos
        acc.calls += calls
        if (nanos > acc.maxNanos) acc.maxNanos = nanos
    }

    /** 记录一个「当前值」型指标(如缓存条目数),汇总时按最后值打印。 */
    fun gauge(name: String, value: Int) {
        if (!ENABLED) return
        gauges[name] = value
    }

    /** 只计数。 */
    fun count(name: String, times: Int = 1) {
        if (!ENABLED) return
        stats.getOrPut(name) { Acc() }.calls += times
    }

    /** 每帧末尾调用一次；累计满一个窗口后打印并清零。 */
    fun frame() {
        if (!ENABLED) return
        if (++frames < WINDOW_FRAMES) return
        dump()
        stats.clear()
        gauges.clear()
        frames = 0
    }

    private fun dump() {
        val windowFrames = frames
        val builder = StringBuilder()
        builder.append("探针窗口 ").append(windowFrames).append(" 帧 —— 每帧均值 / 次数 / 单次峰值(ms):")
        for ((name, acc) in stats.entries.sortedByDescending { it.value.nanos }) {
            val perFrameMs = acc.nanos / 1_000_000.0 / windowFrames
            if (perFrameMs < MIN_PER_FRAME_MS) continue
            builder.append('\n').append(
                String.format(
                    Locale.ROOT,
                    "  %-26s %9.3f ms   x%-7d peak %8.3f ms",
                    name, perFrameMs, acc.calls, acc.maxNanos / 1_000_000.0,
                )
            )
        }
        if (gauges.isNotEmpty()) {
            builder.append("\n  —— 当前值:")
            for ((name, value) in gauges) builder.append('\n').append(String.format(Locale.ROOT, "  %-26s %d", name, value))
        }
        logger.info(builder.toString())
    }
}
