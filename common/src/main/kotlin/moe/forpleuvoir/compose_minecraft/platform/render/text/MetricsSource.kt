package moe.forpleuvoir.compose_minecraft.platform.render.text

/**
 * TrueType 混合度量源(统一实现,总设计 I2/A5):
 *
 * - 链上首含字形字体 → 其 HMetrics × `target/chainEm`(链网格比);
 * - 缺字码点 → 原版位图度量 × `target/vanillaEm`(位图 9 网格比)——
 *   **回退段按其自身渲染通道的网格比缩放**,与 [VanillaBitmapSubmitter]
 *   的 pose 比(k=emPx/providerEm)严格一致,宽度永不失配;
 * - 行高/基线 = 首字体自然度量 × target/chainEm。
 *
 * 结果按 targetEmPx 缓存([at]);advance/kern 另有逐码点缓存。
 */
internal class TrueTypeMetricsSource(
    /** 有序回退链:首字体为排版基准 */
    private val chain: List<TrueTypeFont>,
    private val vanillaFallback: RunMetrics,
    /** 链字体的原生网格 em(stb 加载 em / fusion 设计网格 12) */
    private val chainEmPx: Float,
    /** 覆盖判定定制(如像素字体空格恒覆盖);null = 各字体 hasGlyph */
    private val coverage: ((TrueTypeFont, Int) -> Boolean)? = null,
) {

    /** 原版位图网格 em(跟随行高,动态) */
    private val vanillaEmPx: Float get() = vanillaFallback.lineHeight

    private val perTarget = java.util.concurrent.ConcurrentHashMap<Float, RunMetrics>()

    /** 该目标 emPx 下的度量视图(缓存单例) */
    fun at(targetEmPx: Float): RunMetrics = perTarget.computeIfAbsent(targetEmPx) {
        if (targetEmPx == chainEmPx && vanillaEmPx == chainEmPx) plain
        else Mixed(it)
    }

    private fun ownerFont(codepoint: Int): TrueTypeFont? =
        if (isControlCodepoint(codepoint)) null
        else chain.firstOrNull { coverage?.invoke(it, codepoint) ?: it.hasGlyph(codepoint) }

    private fun chainAdvance(font: TrueTypeFont, cp: Int): Float =
        font.codepointAdvance(cp)

    private val advanceCache = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val kernCache = java.util.concurrent.ConcurrentHashMap<Long, Float>()

    /** 原生(@chainEm/@vanillaEm 混合)数值;[Mixed] 再按目标 em 线性缩放 */
    private val plain: RunMetrics = object : RunMetrics {
        override fun advance(codepoint: Int): Float =
            advanceCache.computeIfAbsent(codepoint) {
                ownerFont(it)?.codepointAdvance(it) ?: vanillaFallback.advance(it)
            }

        override fun kern(prev: Int, next: Int): Float =
            kernCache.computeIfAbsent((prev.toLong() shl 32) or next.toLong()) {
                if (isControlCodepoint(next)) return@computeIfAbsent 0f
                val owner = chain.firstOrNull { f ->
                    coverage?.invoke(f, next) ?: f.hasGlyph(next)
                } ?: return@computeIfAbsent 0f
                if (prev >= 0) {
                    val prevOk = coverage?.invoke(owner, prev) ?: owner.hasGlyph(prev)
                    if (!prevOk) return@computeIfAbsent 0f
                }
                owner.codepointKernAdvance(prev, next)
            }

        override val lineHeight: Float
            get() = chain.firstOrNull()?.lineHeightPx ?: vanillaFallback.lineHeight

        override val baselineFromTop: Float
            get() = chain.firstOrNull()?.ascentPx ?: vanillaFallback.baselineFromTop
        override val decorThicknessPx: Float
            get() = vanillaFallback.decorThicknessPx
    }

    /** 目标 em 视图:链内数值 ×target/chainEm;原版回退数值 ×target/vanillaEm */
    private inner class Mixed(private val target: Float) : RunMetrics {
        private val kc = target / chainEmPx
        private val kv = target / vanillaEmPx

        override fun advance(codepoint: Int): Float =
            if (ownerFont(codepoint) != null) plain.advance(codepoint) * kc
            else vanillaFallback.advance(codepoint) * kv

        override fun kern(prev: Int, next: Int): Float = plain.kern(prev, next) * kc

        override val lineHeight: Float get() = plain.lineHeight * kc
        override val baselineFromTop: Float get() = plain.baselineFromTop * kc
        override val decorThicknessPx: Float get() = plain.decorThicknessPx * kc
    }
}
