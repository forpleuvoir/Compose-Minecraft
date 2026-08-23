package moe.forpleuvoir.compose_minecraft.platform.render.text

import com.mojang.logging.LogUtils
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.ui.text.fontOriginal
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

/**
 * 字体系统统一抽象(P1 重构,总设计 docs/text-measurement-redesign.md §3)。
 *
 * 定案原则(用户拍板):
 * 1. **pixel-font 只是当前默认字体,零特殊处理**;
 * 2. **所有字体一个概念**:像素 / 系统 stb 链 / 原版位图 / 自定义外部字体一律平权,
 *    只有 [FontChannel] 不同;
 * 3. **默认字号是字体的属性**([PlatformFont.defaultSizeSp]),不写死、不散落。
 *
 * 全部字体决策只发生在 [FontResolver.resolve];其余代码只消费 [ResolvedFont]
 * (架构法则 A1/A2,A4–A7 同见总设计 §0)。
 */

/** 本文件共享日志(解析回退告警等;每类告警自带去重) */
private val FONT_LOGGER = LogUtils.getLogger()

/** 渲染通道:字体的唯一本质差异 */
enum class FontChannel {

    /** 自研 stb 管线(GlyphCache 图集,矢量光栅化;缺字内联位图提交) */
    STB_VECTOR,

    /** 原版 FreeType(fusion_pixel json / 自定义注册字体经 MC FontManager) */
    MC_FREETYPE,

    /** 原版位图字形(mc.font 直接提交;missing 方框兜底) */
    MC_BITMAP,
}

/**
 * 逐码点度量(emPx 绝对值;架构法则 A5「度量恒等于渲染」):
 * - [advance]:TTF 读 hmtx(`stbtt_GetCodepointHMetrics`)、位图读 splitter
 *   widthProvider —— 比例字体逐字符各测各宽,kern 逐对独立,无任何平均近似;
 * - [lineHeight]/[baselineFromTop]:该 em 下的自然行盒与基线。
 */
interface RunMetrics {
    fun advance(codepoint: Int): Float
    fun kern(prev: Int, next: Int): Float = 0f

    /** 行盒高(px,该字体的当前 em 下) */
    val lineHeight: Float

    /** 行顶到基线距离(px,正值;基线唯一来源,I4) */
    val baselineFromTop: Float
}

/** 内部桥接:按比例缩放另一份度量(advance/kern/行盒线性于 em) */
internal class ScaledRunMetrics(
    private val base: RunMetrics,
    private val ratio: Float,
) : RunMetrics {
    override fun advance(codepoint: Int): Float = base.advance(codepoint) * ratio
    override fun kern(prev: Int, next: Int): Float = base.kern(prev, next) * ratio
    override val lineHeight: Float get() = base.lineHeight * ratio
    override val baselineFromTop: Float get() = base.baselineFromTop * ratio
}

/**
 * 所有字体的唯一抽象。实现方只需声明:通道、网格 em、默认字号、覆盖判定、
 * 回退目标与度量 —— 无任何模式开关参与(A8)。
 */
interface PlatformFont {
    val id: FontDescription
    val channel: FontChannel

    /** 光栅网格 em(px):fusion=12(json size)/ 位图=9 / stb=加载 em。 */
    val providerEmPx: Float

    /** 本字体的默认字号(sp;未显式指定 fontSize 时用 —— A6/I5)。 */
    val defaultSizeSp: Float

    fun covers(codepoint: Int): Boolean

    /**
     * 缺字回退目标字体 id(null = 本字体即终端)。链条终局必达 MC_BITMAP
     * 终端字体(I6);禁止成环(注册时校验)。
     */
    val fallbackId: FontDescription?

    /** 该 em 下的逐码点度量(实现应返回内部缓存的稳定实例) */
    fun metricsAt(emPx: Float): RunMetrics

    fun lineHeightAt(emPx: Float): Float
    fun baselineFromTopAt(emPx: Float): Float
}

/** 一个码点的最终渲染归属(沿 fallback 链解析的结果) */
data class GlyphOwner(val font: PlatformFont, val channel: FontChannel)

/**
 * 解析结果:一次解析、全程携带(架构法则 A3「绑定随数据走」—— 布局期取得的
 * 实例引用将随绘制命令到达渲染端,两端不可能分叉)。
 */
class ResolvedFont internal constructor(
    val font: PlatformFont,
    val emPx: Float,
) {
    val metrics: RunMetrics = font.metricsAt(emPx)
    val lineHeightPx: Float = font.lineHeightAt(emPx)
    val baselineFromTopPx: Float = font.baselineFromTopAt(emPx)

    /** 码点归属缓存(布局/切段高频查询;covers 可能是 JNI 查询) */
    private val ownerCache = java.util.concurrent.ConcurrentHashMap<Int, GlyphOwner>()

    /**
     * 该码点的最终渲染归属:本字体覆盖 → 本字体自身通道;
     * 否则沿 [PlatformFont.fallbackId] 链下探,终局必达 MC_BITMAP(I6)。
     */
    fun ownerOf(codepoint: Int): GlyphOwner = ownerCache.computeIfAbsent(codepoint) {
        var font = this.font
        var visited = 0
        while (visited++ < MAX_CHAIN_DEPTH) {
            if (font.covers(codepoint)) return@computeIfAbsent GlyphOwner(font, font.channel)
            val next = font.fallbackId?.let { id -> FontRegistry[id] }
            if (next == null) {
                // 链尽(含终端位图字体):交原版位图渲染(missing 方框兜底)
                return@computeIfAbsent GlyphOwner(font, FontChannel.MC_BITMAP)
            }
            font = next
        }
        FONT_LOGGER.error("[ComposeMinecraft] fallback chain too deep at font={}, treating as bitmap", font.id)
        GlyphOwner(font, FontChannel.MC_BITMAP)
    }
    companion object {
        private const val MAX_CHAIN_DEPTH = 8
    }
}

/** 字体注册表(唯一登记处;数据驱动,无特判)。 */
object FontRegistry {

    private val fonts = HashMap<FontDescription, PlatformFont>()

    @Synchronized
    fun register(font: PlatformFont) {
        val prev = fonts.put(font.id, font)
        require(prev == null || prev === font) { "duplicate font registration: ${font.id}" }
    }

    @Synchronized
    operator fun get(id: FontDescription): PlatformFont? = fonts[id]
}

/**
 * 唯一字体决策点(架构法则 A2)。
 *
 * 「默认字体是谁」的唯一配置点 = [defaultFontId](A8,无模式开关):
 * 过渡期(P1/P2)由旧配置项派生,P2 起改为直接赋值并删除旧开关。
 */
object FontResolver {

    /** 缺失字体 id 只告警一次(防日志刷屏) */
    private val warnedMissing = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<FontDescription, Boolean>())

    /**
     * 当前默认字体。过渡语义:像素模式 → fusion_pixel;stb 对照模式 →
     * minecraft:default(与旧行为一致;P2 起为可写配置点)。
     */
    val defaultFontId: FontDescription
        get() = if (TextRenderConfig.usePixelDefaultFont) BuiltinFonts.fusionPixel.id
        else BuiltinFonts.defaultChain.id

    /**
     * 解析指定字体在 [emPx](最终像素 em)下的绑定。
     * 未知 id:ERROR(一次)后退回默认字体 —— 永不抛出,永不静默错量;
     * 默认字体未注册属启动期程序错误,直接失败(fail-loud)。
     */
    fun resolve(id: FontDescription?, emPx: Float): ResolvedFont {
        val font = id?.let { FontRegistry[it] }
        if (font == null) {
            // 显式指定了未知字体 id:ERROR(一次)后退回默认 —— 禁止静默错量
            if (id != null && warnedMissing.add(id)) {
                FONT_LOGGER.error("[ComposeMinecraft] unknown font id {}, fallback to default", id)
            }
            return resolveFont(defaultFont(), emPx)
        }
        return resolveFont(font, emPx)
    }

    private fun defaultFont(): PlatformFont =
        FontRegistry[defaultFontId]
            ?: throw IllegalStateException("default font $defaultFontId not registered")

    private fun resolveFont(font: PlatformFont, emPx: Float): ResolvedFont =
        pool.computeIfAbsent(BindingKey(font, emPx)) { ResolvedFont(font, emPx) }

    fun resolveDefault(emPx: Float): ResolvedFont = resolve(null, emPx)

    /** 按 style 解析(null fontOriginal = 默认字体) */
    fun resolve(style: Style, emPx: Float): ResolvedFont = resolve(style.fontOriginal, emPx)

    /**
     * P1 过渡入口:按字体的原生参考 em([PlatformFont.providerEmPx])解析 ——
     * 数值与旧全局快照度量完全一致(纯结构迁移);P2 尺寸空间统一后,
     * 全部消费点改传真实 emPx,本入口删除。
     * 未知 id 与 [resolve] 同语义:ERROR(一次)后退回默认,不抛出。
     */
    fun resolveNative(style: Style): ResolvedFont {
        val id = style.fontOriginal
        val font = id?.let { FontRegistry[it] }
        if (font != null) return resolveFont(font, font.providerEmPx)
        if (id != null && warnedMissing.add(id)) {
            FONT_LOGGER.error("[ComposeMinecraft] unknown font id {} (native path), fallback to default", id)
        }
        val def = defaultFont()
        return resolveFont(def, def.providerEmPx)
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private data class BindingKey(val font: PlatformFont, val emPx: Float)

    private val pool = java.util.concurrent.ConcurrentHashMap<BindingKey, ResolvedFont>()

    init {
        BuiltinFonts.registerAll()
    }
}

/**
 * 平台内置字体(注册表首批条目;全部只是普通 [PlatformFont],无一特判)。
 *
 * - fusionPixel:compose_minecraft:fusion_pixel(proportional;mono 变体另册),
 *   原版 FreeType 渲染 + 同文件 stb 度量(hmtx 已证与 FreeType 全等);
 * - defaultChain:minecraft:default,stb 就绪走矢量链,否则位图;
 *   缺字回退 unifont(位图终端);
 * - 原版资源字体族(alt/unifont 等):位图通道,自身即终端。
 */
internal object BuiltinFonts {

    /** fusion_pixel proportional(平台当前默认字体 —— 仅此一处表达这一事实) */
    val fusionPixel: PlatformFont = FusionPixelFont(
        id = FontDescription.Resource(Identifier.fromNamespaceAndPath("compose_minecraft", "fusion_pixel")),
        mono = false,
    )

    /** fusion_pixel mono 变体(等宽场景,业务显式指定) */
    val fusionPixelMono: PlatformFont = FusionPixelFont(
        id = FontDescription.Resource(Identifier.fromNamespaceAndPath("compose_minecraft", "fusion_pixel_mono")),
        mono = true,
    )

    /** minecraft:default(stb 就绪 ? 矢量链 : 位图;缺字回退 unifont) */
    val defaultChain: PlatformFont = DefaultChainFont()

    /** minecraft:unifont —— 位图终端字体(Unicode 兜底;missing 方框由引擎兜底) */
    val uniFontTerminal: PlatformFont =
        VanillaBitmapFont(FontDescription.Resource(Identifier.withDefaultNamespace("unifont")))

    val alt: PlatformFont = VanillaBitmapFont(FontDescription.Resource(Identifier.withDefaultNamespace("alt")))
    val illagerAlt: PlatformFont = VanillaBitmapFont(FontDescription.Resource(Identifier.withDefaultNamespace("illageralt")))

    fun registerAll() {
        listOf(fusionPixel, fusionPixelMono, defaultChain, uniFontTerminal, alt, illagerAlt)
            .forEach { FontRegistry.register(it) }
    }
}

/** fusion_pixel(proportional / mono):FreeType 渲染 + 同文件 stb 度量 */
private class FusionPixelFont(
    override val id: FontDescription,
    mono: Boolean,
) : PlatformFont {

    /** 加载失败已在 PixelFont 打 ERROR;null → 度量/覆盖全部落位图兜底 */
    private val fontRef: TrueTypeFont? by lazy {
        if (mono) PixelFont.fontOfMono() else PixelFont.fontOfProportional()
    }

    /** 混合度量(像素网格 12 + 原版回退按各自网格比缩放;空格恒覆盖) */
    private val mixed: TrueTypeMetricsSource? by lazy {
        fontRef?.let { f ->
            TrueTypeMetricsSource(
                chain = listOf(f),
                vanillaFallback = VanillaRunMetrics,
                chainEmPx = providerEmPx,
                coverage = { t, cp -> cp == ' '.code || t.hasGlyph(cp) },
            )
        }
    }

    override val channel: FontChannel get() = FontChannel.MC_FREETYPE
    override val providerEmPx: Float get() = TextRenderConfig.pixelFontEmSp
    override val defaultSizeSp: Float get() = TextRenderConfig.pixelFontEmSp * 2f

    override fun covers(codepoint: Int): Boolean =
        codepoint == ' '.code || (fontRef?.hasGlyph(codepoint) ?: false)

    override val fallbackId: FontDescription get() = FontDescription.DEFAULT

    override fun metricsAt(emPx: Float): RunMetrics =
        mixed?.at(emPx) ?: scaledVanilla(emPx)

    override fun lineHeightAt(emPx: Float): Float =
        (fontRef?.lineHeightPx ?: VanillaRunMetrics.lineHeight) * (emPx / providerEmPx)

    override fun baselineFromTopAt(emPx: Float): Float =
        (fontRef?.ascentPx ?: VanillaRunMetrics.baselineFromTop) * (emPx / providerEmPx)

    private fun scaledVanilla(emPx: Float): RunMetrics =
        if (emPx == VanillaRunMetrics.lineHeight) VanillaRunMetrics
        else ScaledRunMetrics(VanillaRunMetrics, emPx / VanillaRunMetrics.lineHeight)
}

/**
 * minecraft:default:stb 就绪 → 矢量链通道;否则位图通道。
 * 度量 = 混合源(链内逐码点,缺字退原版 splitter —— 与位图内联渲染宽度同源)。
 */
private class DefaultChainFont : PlatformFont {

    private val stbReady: Boolean
        get() = TextRenderConfig.enabled && TrueTypeFontManager.isReady

    override val id: FontDescription get() = FontDescription.DEFAULT
    override val channel: FontChannel get() = if (stbReady) FontChannel.STB_VECTOR else FontChannel.MC_BITMAP

    /** stb 链加载 em(未就绪时以原版行高为准) */
    override val providerEmPx: Float
        get() = TrueTypeFontManager.regularChain().firstOrNull()?.baseSizePx
            ?: VanillaRunMetrics.lineHeight

    override val defaultSizeSp: Float
        get() = if (stbReady) SYSTEM_STB_DEFAULT_SIZE_SP else VANILLA_DEFAULT_SIZE_SP

    override fun covers(codepoint: Int): Boolean =
        TrueTypeFontManager.regularChain().any { it.hasGlyph(codepoint) }

    /** 缺字回退 unifont(位图终端;Unicode 覆盖远超 stb 链) */
    override val fallbackId: FontDescription get() = BuiltinFonts.uniFontTerminal.id

    override fun metricsAt(emPx: Float): RunMetrics {
        // 混合源按目标 em 统一缩放(链内 ×em/chainEm,原版回退 ×em/9)——
        // 回退段宽度与其位图渲染 pose 比严格一致(I2)
        return TrueTypeFontManager.metricsOrNull()?.at(emPx)
            ?: scaledVanilla(emPx)
    }

    private fun scaledVanilla(emPx: Float): RunMetrics =
        if (emPx == VanillaRunMetrics.lineHeight) VanillaRunMetrics
        else ScaledRunMetrics(VanillaRunMetrics, emPx / VanillaRunMetrics.lineHeight)

    override fun lineHeightAt(emPx: Float): Float = metricsAt(emPx).lineHeight
    override fun baselineFromTopAt(emPx: Float): Float = metricsAt(emPx).baselineFromTop

    companion object {
        /** 系统 stb 链默认字号(用户定案现值;随字体携带,非全局常量) */
        const val SYSTEM_STB_DEFAULT_SIZE_SP = 16f

        /** 原版位图默认字号(行高 9 的 2 倍历史语义) */
        const val VANILLA_DEFAULT_SIZE_SP = 18f
    }
}

/** 原版资源字体(alt/unifont 等):位图通道,fallbackId=null 即链终局 */
private class VanillaBitmapFont(
    override val id: FontDescription,
) : PlatformFont {

    override val channel: FontChannel get() = FontChannel.MC_BITMAP
    override val providerEmPx: Float get() = VanillaRunMetrics.lineHeight // 9 网格
    override val defaultSizeSp: Float get() = DefaultChainFont.VANILLA_DEFAULT_SIZE_SP

    /** 终端字体视为全覆盖(缺失码点由引擎画方框);其余资源字体同样交原版解析 */
    override fun covers(codepoint: Int): Boolean = true

    override val fallbackId: FontDescription? = null

    override fun metricsAt(emPx: Float): RunMetrics =
        if (emPx == providerEmPx) VanillaRunMetrics
        else ScaledRunMetrics(VanillaRunMetrics, emPx / providerEmPx)

    override fun lineHeightAt(emPx: Float): Float = metricsAt(emPx).lineHeight
    override fun baselineFromTopAt(emPx: Float): Float = metricsAt(emPx).baselineFromTop
}

/** 原版位图度量(@9 网格原生 em;单例) */
internal object VanillaRunMetrics : RunMetrics {

    /** 历史布局基线约定:0.8 × 9px 行盒(P2 换字体自然度量后删除) */
    private const val VANILLA_LAYOUT_BASELINE = 7.2f

    override fun advance(codepoint: Int): Float =
        mc.font.splitter.stringWidth(String(Character.toChars(codepoint)))

    override val lineHeight: Float
        get() = mc.font.lineHeight.toFloat()

    override val baselineFromTop: Float = VANILLA_LAYOUT_BASELINE
}
