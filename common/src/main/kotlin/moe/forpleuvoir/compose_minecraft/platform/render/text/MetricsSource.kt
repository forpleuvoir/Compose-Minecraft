package moe.forpleuvoir.compose_minecraft.platform.render.text

/**
 * TrueType 度量(stbtt HMetrics/VMetrics,**混合回退**;总设计 §3):
 * - 字体链上有该码点字形 → 链内首个含字形字体的 HMetrics(主字体优先);
 * - 缺字(emoji 等)→ 回退 [vanillaFallback] 的原版位图度量 —— 该字符将由
 *   位图通道内联渲染,布局预留宽度即其真实渲染宽度;
 * - 行高/基线 = 首字体自然度量(排版基准)。
 *
 * advance/kern 结果缓存(每码点/码点对一次查询)。
 */
internal class TrueTypeMetricsSource(
    /** 有序回退链:首字体为排版基准(行高/基线),全部成员按码点供字形与 advance */
    private val chain: List<TrueTypeFont>,
    private val vanillaFallback: RunMetrics,
) : RunMetrics {

    private val advanceCache = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val kernCache = java.util.concurrent.ConcurrentHashMap<Long, Float>()

    /** 链上首个含该码点字形的字体(主字体优先) */
    private fun ownerFont(codepoint: Int): TrueTypeFont? =
        chain.firstOrNull { it.hasGlyph(codepoint) }

    override fun advance(codepoint: Int): Float =
        advanceCache.computeIfAbsent(codepoint) {
            ownerFont(it)?.codepointAdvance(it) ?: vanillaFallback.advance(it)
        }

    /** 字偶距:由提供 [next] 字形的字体计算(优先同时含前后字的成员) */
    override fun kern(prev: Int, next: Int): Float =
        kernCache.computeIfAbsent((prev.toLong() shl 32) or next.toLong()) {
            val owner = chain.firstOrNull { it.hasGlyph(next) } ?: return@computeIfAbsent 0f
            if (prev >= 0 && !owner.hasGlyph(prev)) return@computeIfAbsent 0f
            owner.codepointKernAdvance(prev, next)
        }

    override val lineHeight: Float
        get() = chain.firstOrNull()?.lineHeightPx ?: vanillaFallback.lineHeight

    override val baselineFromTop: Float
        get() = chain.firstOrNull()?.ascentPx ?: vanillaFallback.baselineFromTop
}
