package moe.forpleuvoir.compose_minecraft.platform.render.text

import moe.forpleuvoir.compose_minecraft.mc

/**
 * 度量来源抽象(T.TT)。
 *
 * - 关闭开关 → [VanillaMetricsSource](原版 `font.splitter` 位图度量,现状);
 * - 开启且字体就绪 → [TrueTypeMetricsSource](stbtt HMetrics,原生排版 ——
 *   用户拍板:间距用字体自身度量,任何字体都自然协调;测试场景切换渲染器
 *   时整树重组,回流只发生在 dev 切换瞬间)。
 *
 * 布局层(`MinecraftTextLayout.cumFloatWidths` 增量数组)、绘制层
 * (TrueTypeTextWriter 的字形摆放)与命中测试(光标/选区/前缀宽度)
 * 全部经本接口取数,天然同源:
 *
 * - [charAdvance]:单码点 advance;
 * - [lineHeight]:行盒高;[baselineFromTop]:行顶到基线距离。
 */
interface MetricsSource {

    /** 单码点 advance(1x 基准,布局空间 px) */
    fun charAdvance(codepoint: Int): Float

    /** 行盒高(1x 基准,px) */
    val lineHeight: Float

    /** 行顶到基线距离(1x 基准,px,正值) */
    val baselineFromTop: Float

    /**
     * 字偶距(px):紧接 [prev] 之后绘制 [next] 时附加的水平位移。
     * 原版位图字体无字距(默认 0);TrueType 读字体 kern 表,
     * 与系统渲染器的间距行为一致。
     */
    fun codepointKern(prev: Int, next: Int): Float = 0f
}

/**
 * 原版 MC 位图字体度量(唯一实现)。
 *
 * - advance:`font.splitter.stringWidth`(按码点累加,无字距 —— 与
 *   `cumFloatWidths` 增量语义一致);
 * - 行高 9px 固定;
 * - baselineFromTop = 7.2f(= 0.8×9,历史布局数学的基线约定):段落
 *   firstBaseline/段级基线对齐等布局侧公式历史上用 0.8×行盒,此值保持
 *   逐字节一致;原版/TTF 字形的实际绘制基线由各自渲染端处理
 *   (原版内部 7px;TrueTypeTextWriter 用 VANILLA_GLYPH_BASELINE = 7f)。
 */
internal object VanillaMetricsSource : MetricsSource {

    /** 历史布局基线约定:0.8 × 9px 行盒 */
    private const val VANILLA_LAYOUT_BASELINE = 7.2f

    override fun charAdvance(codepoint: Int): Float =
        mc.font.splitter.stringWidth(String(Character.toChars(codepoint)))

    override val lineHeight: Float
        get() = mc.font.lineHeight.toFloat()

    override val baselineFromTop: Float = VANILLA_LAYOUT_BASELINE
}

/**
 * TrueType 度量(stbtt_GetCodepointHMetrics/GetFontVMetrics,原生排版):
 * 行高 = typo+CJK 下限行盒;advance = 字体自身 HMetrics —— 与字形墨迹同源,
 * 字符间距由字体设计保证(实测反馈:混用原版格宽会导致 "Mi" 等组合忽近忽远)。
 */
/**
 * TrueType 度量(stbtt HMetrics/VMetrics,**混合回退**):
 * - 字体有该码点字形 → 字体自身 HMetrics(原生排版);
 * - 缺字(emoji 等)→ 回退 [vanillaFallback] 的原版位图度量 —— 该字符将由
 *   原版字形内联渲染,布局预留宽度即其真实渲染宽度,无需任何缩放适配。
 * advance 结果缓存(每码点一次查询)。
 */
internal class TrueTypeMetricsSource(
    /** 有序回退链:首字体为排版基准(行高/基线),全部成员按码点供字形与 advance */
    private val chain: List<TrueTypeFont>,
    private val vanillaFallback: MetricsSource,
) : MetricsSource {

    private val advanceCache = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val kernCache = java.util.concurrent.ConcurrentHashMap<Long, Float>()

    /** 链上首个含该码点字形的字体(主字体优先) */
    private fun ownerFont(codepoint: Int): TrueTypeFont? =
        chain.firstOrNull { it.hasGlyph(codepoint) }

    override fun charAdvance(codepoint: Int): Float =
        advanceCache.computeIfAbsent(codepoint) {
            ownerFont(it)?.codepointAdvance(it) ?: vanillaFallback.charAdvance(it)
        }

    /** 字偶距:由提供 [next] 字形的字体计算(优先同时含前后字的成员) */
    override fun codepointKern(prev: Int, next: Int): Float =
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

/**
 * 当前生效的度量来源(布局构造时查询快照):
 * 开关开启且字体加载成功 → TTF 原生度量;否则原版(逐字节现状)。
 */
internal fun activeMetricsSource(): MetricsSource =
    if (TextRenderConfig.enabled) {
        TrueTypeFontManager.metricsOrNull() ?: VanillaMetricsSource
    } else {
        VanillaMetricsSource
    }
