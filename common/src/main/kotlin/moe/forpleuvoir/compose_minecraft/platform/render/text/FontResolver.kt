package moe.forpleuvoir.compose_minecraft.platform.render.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mojang.logging.LogUtils
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.ui.text.boldRaw
import moe.forpleuvoir.compose_minecraft.platform.ui.text.fontOriginal
import moe.forpleuvoir.compose_minecraft.platform.ui.text.italicRaw
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

/**
 * 字体系统统一抽象(重构,总设计 docs/text-measurement-redesign.md §3)。
 *
 * 设计原则:
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
 * 度量规格(泛化):**一切影响渲染宽度/行盒的样式属性的唯一载体**。
 *
 * 已知成员:
 * - [emPx]:最终像素 em;
 * - [bold]:原版位图渲染器每字符 +1 网格像素(getCharWidth 加宽);
 *   矢量粗体(合成/真字重)不改变 advance。
 *
 * ⚠️ 演进规则:未来任何影响 advance/行盒的属性(现知特例:混淆 §k 在
 * 位图通道按随机字形取宽、天然抖动属原版原生行为,stb 通道刻意锁定
 * 原字符宽以稳布局)**必须**新增字段并同步全部实现 —— 本签名变更会
 * 迫使每个 PlatformFont 显式表态,禁止再出现调用方零散补丁(A5)。
 */
data class MeasureSpec(
    val emPx: Float,
    val bold: Boolean = false,
    /** 渲染样式快照(mc.font 渲染通道的权威计宽输入;矢量通道可 null) */
    val style: Style? = null,
)

/**
 * 度量规格归一化:只保留**决定字形与 advance 的样式字段**(字体 / 粗体 / 斜体),
 * 剔除纯绘制属性(颜色 / 阴影色 / 点击悬停事件 / 插入文本 / 下划线 / 删除线 / 乱码)。
 *
 * 度量池([FontResolver.pool]、[VanillaPipelineMetrics.pool])按规格分桶,而一个码点的
 * advance 只取决于「用哪个字形集、要不要加粗」—— 颜色等绘制属性既不进 splitter 计宽、
 * 也不改变字形集。若把它们留在规格里,同一段文字换一次颜色(例如悬停态的交互反馈色)
 * 就会落到另一个池桶、另行计算并**另行缓存**一份 advance;两份缓存分别在不同时刻取值
 * 时(字体管线尚未就绪 / 已就绪)就会量出两个宽度,按内容取宽的容器随之抖动。
 * 颜色由绘制端消费(节点样式 / overrideColor),不归度量。
 */
internal fun MeasureSpec.layoutOnly(): MeasureSpec {
    val source = style ?: return this
    var normalized = Style.EMPTY
    source.fontOriginal?.let { normalized = normalized.withFont(it) }
    if (source.boldRaw == true) normalized = normalized.withBold(true)
    if (source.italicRaw == true) normalized = normalized.withItalic(true)
    return if (normalized == source) this else copy(style = normalized)
}

/**
 * 逐码点度量(emPx 绝对值;架构法则 A5「度量恒等于渲染」):
 * - [advance]:**必须回答「渲染端实际绘制这个码点的宽度」** —— mc.font 通道
 *   经归属链([owningGlyphOwner])取归属字形集宽度,stb 链取 hmtx;
 *   比例字体逐字符各测各宽,kern 逐对独立,无任何平均近似;
 * - [lineHeight]/[baselineFromTop]:该 em 下的自然行盒与基线。
 */
interface RunMetrics {
    fun advance(codepoint: Int): Float
    fun kern(prev: Int, next: Int): Float = 0f

    /** 行盒高(px,该字体的当前 em 下) */
    val lineHeight: Float

    /** 行顶到基线距离(px,正值;基线唯一来源,I4) */
    val baselineFromTop: Float

    /** 装饰线厚度/阴影偏移(px,字体自身设计的原生值;随目标 em 统一缩放) */
    val decorThicknessPx: Float
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
    override val decorThicknessPx: Float get() = base.decorThicknessPx * ratio
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

    /**
     * 该 spec 下的完整度量(advance/行盒/基线单源给出,无旁路方法;
     * bold 等宽度效应由实现按 spec 处理 —— A5 度量≡渲染)。
     */
    fun metricsAt(spec: MeasureSpec): RunMetrics
}

/** 一个码点的最终渲染归属(沿 fallback 链解析的结果) */
data class GlyphOwner(val font: PlatformFont, val channel: FontChannel)

/**
 * 解析结果:一次解析、全程携带(架构法则 A3「绑定随数据走」—— 布局期取得的
 * 实例引用将随绘制命令到达渲染端,两端不可能分叉)。
 */
class ResolvedFont internal constructor(
    val font: PlatformFont,
    val spec: MeasureSpec,
) {
    val emPx: Float get() = spec.emPx
    val metrics: RunMetrics = font.metricsAt(spec)
    val lineHeightPx: Float get() = metrics.lineHeight
    val baselineFromTopPx: Float get() = metrics.baselineFromTop

    /** 码点归属缓存(布局/切段高频查询;covers 可能是 JNI 查询) */
    private val ownerCache = java.util.concurrent.ConcurrentHashMap<Int, GlyphOwner>()

    /**
     * 该码点的最终渲染归属:本字体覆盖 → 本字体自身通道;
     * 否则沿 [PlatformFont.fallbackId] 链下探,终局必达 MC_BITMAP(I6)。
     */
    fun ownerOf(codepoint: Int): GlyphOwner = ownerForChannel(codepoint, allowStb = true)

    /**
     * 归属解析(可排除 STB 通道):定向退回(VANILLA)时 STB 字体无法由
     * mc.font 渲染 —— 沿 fallback 链下探到首个非 STB 通道且覆盖该码点的
     * 字体(终局必达位图);allowStb=true 时与 ownerOf 相同。
     */
    fun ownerForChannel(codepoint: Int, allowStb: Boolean): GlyphOwner {
        val key = if (allowStb) codepoint else -codepoint.dec()
        // 归属解析与通道无关(通道过滤由上游 bitmapPreferred 的 id 替换承担);
        // allowStb 仅作为缓存键维度保留(VANILLA 定向查询与常规查询分开计)
        return ownerCache.computeIfAbsent(key) { owningGlyphOwner(this.font, codepoint) }
    }

    /** 本字体是否覆盖整段文本(快速路径;控制字符视为不覆盖) */
    fun coversAll(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isControlCodepoint(cp) || !font.covers(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }
}

/** 链深上限:注册表回退链禁止成环(注册期校验),此值为防御性深度界 */
private const val MAX_CHAIN_DEPTH = 8

/**
 * **归属链唯一实现**(font-system 修复  升维):「一个码点由谁渲染」
 * 的全部答案都从这里产生 —— 渲染端分段提交(GuiStateBackend.submitOwnedRun
 * 经 [ResolvedFont.ownerForChannel])与测量端归属化计宽
 * ([VanillaPipelineMetrics.advance])消费的是**同一个**解析结果,
 * 不存在第二份链遍历。规则:从 [start] 起,首个覆盖非控制码点的字体;
 * 控制码点无字形、任何字体不归属 → 必达终端位图(链尽同)。
 */
internal fun owningGlyphOwner(start: PlatformFont, codepoint: Int): GlyphOwner {
    var font = start
    var visited = 0
    while (visited++ < MAX_CHAIN_DEPTH) {
        if (!isControlCodepoint(codepoint) && font.covers(codepoint)) return GlyphOwner(font, font.channel)
        val next = font.fallbackId?.let { id -> FontRegistry[id] }
        if (next == null) {
            // 链尽(含终端位图字体):交原版位图渲染(missing 方框兜底)
            return GlyphOwner(font, FontChannel.MC_BITMAP)
        }
        font = next
    }
    FONT_LOGGER.error("[ComposeMinecraft] fallback chain too deep at font={}, treating as bitmap", font.id)
    return GlyphOwner(font, FontChannel.MC_BITMAP)
}

/** 字体注册表(唯一登记处;数据驱动,无特判)。 */
object FontRegistry {

    private val fonts = HashMap<FontDescription, PlatformFont>()

    /**
     * 注册(替换语义):同 id 重注册覆盖旧条目 —— 支持自定义字体资源重载后
     * 的重建场景;旧 [ResolvedFont] 绑定随旧布局自然淘汰,无需主动清理。
     */
    @Synchronized
    fun register(font: PlatformFont) {
        fonts[font.id] = font
    }

    /** 注销(自定义字体卸载;未注册为空操作)。 */
    @Synchronized
    fun unregister(id: FontDescription) {
        fonts.remove(id)
    }

    @Synchronized
    operator fun get(id: FontDescription): PlatformFont? = fonts[id]
}

/**
 * 注册字体文件(定案接入统一字体体系):经 mc.font(FreeType)渲染,
 * advance 直接询问 splitter 样式化计宽(TTF/OTF 一律权威同源);
 * 自然行盒/基线由注册时的 FreeType face 度量折算到网格。
 */
class CustomFreeTypeFont internal constructor(
    override val id: FontDescription,
    override val defaultSizeSp: Float = 18f,
) : PlatformFont {

    /** FreeType face 折算到网格(ascent/upm×grid);注册时更新 */
    internal var naturalLineHeight: Float = VanillaRunMetrics.lineHeight
    internal var naturalBaseline: Float = VanillaRunMetrics.baselineFromTop

    override val channel: FontChannel get() = FontChannel.MC_FREETYPE

    /** 与原版注入参数一致(TrueTypeGlyphProvider size=9) */
    override val providerEmPx: Float get() = 9f

    /** FontSet 注册时已并入默认字体 provider 链,缺字由引擎回退,视为全覆盖 */
    override fun covers(codepoint: Int): Boolean = true

    override val fallbackId: FontDescription? = null

    private val natural: RunMetrics = object : RunMetrics {
        override fun advance(codepoint: Int): Float = 0f // advance 全走权威计宽,此值不参与
        override val lineHeight: Float get() = this@CustomFreeTypeFont.naturalLineHeight
        override val baselineFromTop: Float get() = this@CustomFreeTypeFont.naturalBaseline
        override val decorThicknessPx: Float get() = 1f
    }

    override fun metricsAt(spec: MeasureSpec): RunMetrics =
        FontMetrics.mcFont(
            this,
            spec.copy(style = spec.style ?: Style.EMPTY.withFont(id)),
            native = natural,
        )
}

/**
 * 唯一字体决策点(架构法则 A2)。
 *
 * 「默认字体是谁」的唯一配置点 = [defaultFontId](A8,无模式开关):
 * 过渡期由旧配置项派生, 起改为直接赋值并删除旧开关。
 */
object FontResolver {

    /** 缺失字体 id 只告警一次(防日志刷屏) */
    private val warnedMissing = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<FontDescription, Boolean>())

    /**
     * 当前默认字体 id(A8 唯一配置点;dev 对照场景直接改写此值)。
     * **快照状态**:改写即触发读取它的组合失效并重排版 —— 默认字体的
     * 解析结果永远跟随此值,不存在需要手动刷新的第二份状态。
     */
    var defaultFontId: FontDescription by androidx.compose.runtime.mutableStateOf(
        BuiltinFonts.fusionPixel.id
    )

    /** 当前默认字体(注册表条目;缺失属启动期程序错误,fail-loud)。 */
    fun defaultFont(): PlatformFont =
        FontRegistry[defaultFontId]
            ?: throw IllegalStateException("default font $defaultFontId not registered")


    /**
     * 解析指定字体在 [emPx](最终像素 em)下的绑定。
     * 未知 id:ERROR(一次)后退回默认字体 —— 永不抛出,永不静默错量;
     * 默认字体未注册属启动期程序错误,直接失败(fail-loud)。
     */
    fun resolve(id: FontDescription?, spec: MeasureSpec): ResolvedFont {
        val font = id?.let { FontRegistry[it] }
        if (font == null) {
            // 显式指定了未知字体 id:ERROR(一次)后退回默认 —— 禁止静默错量
            if (id != null && warnedMissing.add(id)) {
                FONT_LOGGER.error("[ComposeMinecraft] unknown font id {}, fallback to default", id)
            }
            return resolveFont(defaultFont(), spec)
        }
        return resolveFont(font, spec)
    }

    private fun resolveFont(font: PlatformFont, spec: MeasureSpec): ResolvedFont {
        val layoutSpec = spec.layoutOnly()
        return pool.computeIfAbsent(font to layoutSpec) { ResolvedFont(font, layoutSpec) }
    }

    fun resolveDefault(spec: MeasureSpec): ResolvedFont = resolve(null, spec)

    /** 按 style 解析(null fontOriginal = 默认字体;规格取自样式) */
    fun resolve(style: Style, spec: MeasureSpec): ResolvedFont =
        resolve(style.fontOriginal, spec.copy(bold = style.boldRaw == true))

    /**
     * 按「run」解析(font-system 重构 :删除无消费的 text 参数):
     * 返回主字体绑定;不覆盖的字符由布局/渲染两侧经同一 [ResolvedFont.ownerForChannel]
     * 链落至回退字体并使用该字体自己的度量(「逐字符归属」定案 —— 历史
     * 「整串退回」入口已废弃,KDoc 同步更正)。
     */
    fun resolveForRun(id: FontDescription?, spec: MeasureSpec): ResolvedFont {
        return resolveFont(
            id?.let { FontRegistry[it] } ?: defaultFont(),
            spec,
        )
    }

    /**
     *  过渡入口:按字体的原生参考 em([PlatformFont.providerEmPx])解析 ——
     * 数值与旧全局快照度量完全一致(纯结构迁移); 尺寸空间统一后,
     * 全部消费点改传真实 emPx,本入口删除。
     * 未知 id 与 [resolve] 同语义:ERROR(一次)后退回默认,不抛出。
     */
    fun resolveNative(style: Style): ResolvedFont {
        val id = style.fontOriginal
        val font = id?.let { FontRegistry[it] }
        if (font != null) return resolveFont(font, MeasureSpec(font.providerEmPx, style.boldRaw == true))
        if (id != null && warnedMissing.add(id)) {
            FONT_LOGGER.error("[ComposeMinecraft] unknown font id {} (native path), fallback to default", id)
        }
        val def = defaultFont()
        return resolveFont(def, MeasureSpec(def.providerEmPx, style.boldRaw == true))
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 位图优先解析(「退回即全部退回」):STB 字体无法由 mc.font 渲染,
     * 强制原版通道时其 id 应替换为沿 fallback 链的首个非 STB 等价字体
     * (系统链 → minecraft:default);FreeType/位图字体原样返回。
     */
    fun bitmapPreferred(id: FontDescription): FontDescription {
        var f = FontRegistry[id] ?: return id
        var hops = 0
        while (f.channel == FontChannel.STB_VECTOR && hops++ < 8) {
            f = f.fallbackId?.let { FontRegistry[it] } ?: break
        }
        return f.id
    }

    private val pool = java.util.concurrent.ConcurrentHashMap<Pair<PlatformFont, MeasureSpec>, ResolvedFont>()

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
object BuiltinFonts {

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

    /** 系统矢量链的注册 id(平台私有命名空间;位图档对应 minecraft:default) */
    val systemFontId: FontDescription =
        FontDescription.Resource(Identifier.fromNamespaceAndPath("compose_minecraft", "system_font"))

    /**
     * 系统矢量字体链(stb:msyh/simhei/seguisym 等, 定案:
     * 「stb 默认字体」= 系统字体链,**不是** minecraft:default)。
     */
    val systemChain: PlatformFont = SystemChainFont()

    /** minecraft:default —— 原版位图字体(与系统链平权的独立条目) */
    val vanillaDefault: PlatformFont =
        VanillaBitmapFont(FontDescription.DEFAULT)

    /** minecraft:unifont —— 位图终端字体(Unicode 兜底;missing 方框由引擎兜底) */
    val uniFontTerminal: PlatformFont =
        VanillaBitmapFont(FontDescription.Resource(Identifier.withDefaultNamespace("unifont")))

    val alt: PlatformFont = VanillaBitmapFont(FontDescription.Resource(Identifier.withDefaultNamespace("alt")))
    val illagerAlt: PlatformFont = VanillaBitmapFont(FontDescription.Resource(Identifier.withDefaultNamespace("illageralt")))

    fun registerAll() {
        listOf(fusionPixel, fusionPixelMono, systemChain, vanillaDefault, uniFontTerminal, alt, illagerAlt)
            .forEach { FontRegistry.register(it) }
    }
}

/** 控制字符无字形:任何矢量字体不归属,一律回退原版测量/渲染 */
internal fun isControlCodepoint(cp: Int): Boolean = cp in 0x00..0x1F || cp == 0x7F

/**
 * 度量工厂(去特判收口):目标 em 缩放、样式兜底、回退口径
 * 只在此实现一次;PlatformFont 实现只声明原生数据。
 */
internal object FontMetrics {

    /** mc.font 渲染通道(MC_FREETYPE/MC_BITMAP):advance=splitter 样式化计宽 */
    fun mcFont(
        font: PlatformFont,
        spec: MeasureSpec,
        /** 网格原生态度量(禁止传入已按目标 em 缩放的值 —— 防双重缩放) */
        native: RunMetrics,
    ): RunMetrics {
        val effSpec = (if (spec.style != null) spec
        else spec.copy(style = Style.EMPTY.withFont(font.id))).layoutOnly()
        return VanillaPipelineMetrics.of(font, effSpec, native)
    }

    /** stb 矢量链通道:混合源按目标 em 缩放;链未就绪退原版(同比例) */
    fun stbChain(spec: MeasureSpec, source: TrueTypeMetricsSource?): RunMetrics =
        source?.at(spec.emPx) ?: scaledVanilla(spec.emPx)

    fun scaledVanilla(emPx: Float): RunMetrics =
        if (emPx == VanillaRunMetrics.lineHeight) VanillaRunMetrics
        else ScaledRunMetrics(VanillaRunMetrics, emPx / VanillaRunMetrics.lineHeight)
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
                coverage = { t, cp -> !isControlCodepoint(cp) && (cp == ' '.code || t.hasGlyph(cp)) },
            )
        }
    }

    override val channel: FontChannel get() = FontChannel.MC_FREETYPE
    override val providerEmPx: Float get() = TextRenderConfig.pixelFontEmSp
    override val defaultSizeSp: Float get() = TextRenderConfig.pixelFontEmSp * 2f

    override fun covers(codepoint: Int): Boolean =
        codepoint == ' '.code || (fontRef?.hasGlyph(codepoint) ?: false)

    override val fallbackId: FontDescription get() = FontDescription.DEFAULT

    override fun metricsAt(spec: MeasureSpec): RunMetrics =
        // native 必须是网格原生态;缩放与样式兜底统一在 FontMetrics
        FontMetrics.mcFont(
            this, spec,
            native = mixed?.at(providerEmPx) ?: VanillaRunMetrics,
        )
}

/**
 * 系统矢量字体链(定案):stb 渲染器的默认字体 = 系统字体链
 * (微软雅黑等),与原版位图 minecraft:default 是**两个平权字体**。
 * 缺字回退 minecraft:default 位图(终端)。
 */
private class SystemChainFont : PlatformFont {

    override val id: FontDescription get() = BuiltinFonts.systemFontId
    override val channel: FontChannel get() = FontChannel.STB_VECTOR

    /** stb 链加载 em(未就绪时以原版行高为准) */
    override val providerEmPx: Float
        get() = TrueTypeFontManager.regularChain().firstOrNull()?.baseSizePx
            ?: VanillaRunMetrics.lineHeight

    /** 系统字体默认字号(A6 现值定案:16sp) */
    override val defaultSizeSp: Float get() = 16f

    override fun covers(codepoint: Int): Boolean =
        TrueTypeFontManager.regularChain().any { it.hasGlyph(codepoint) }

    /** 缺字回退 minecraft:default 位图(unifont 终端覆盖) */
    override val fallbackId: FontDescription get() = BuiltinFonts.vanillaDefault.id

    override fun metricsAt(spec: MeasureSpec): RunMetrics =
        // 矢量粗体不改 advance(bold 由 stb 合成/真字重承载);口径统一在工厂
        FontMetrics.stbChain(spec, TrueTypeFontManager.metricsOrNull())
}

/** 原版资源字体(alt/unifont 等):位图通道,fallbackId=null 即链终局 */
private class VanillaBitmapFont(
    override val id: FontDescription,
) : PlatformFont {

    override val channel: FontChannel get() = FontChannel.MC_BITMAP
    override val providerEmPx: Float get() = VanillaRunMetrics.lineHeight // 9 网格
    override val defaultSizeSp: Float get() = 18f

    /** 终端字体视为全覆盖(缺失码点由引擎画方框);其余资源字体同样交原版解析 */
    override fun covers(codepoint: Int): Boolean = true

    override val fallbackId: FontDescription? = null

    override fun metricsAt(spec: MeasureSpec): RunMetrics =
        FontMetrics.mcFont(this, spec, native = VanillaRunMetrics)
}

/**
 * 原版管线权威度量(定案; 归属化升维)。
 *
 * **advance 的唯一合法答案 =「渲染端真正画这个码点的字形集」的宽度**:
 * - 归属:owningGlyphOwner 链解析 —— 与渲染端分段提交
 *   (GuiStateBackend.submitOwnedRun → VanillaBitmapSubmitter)消费
 *   **同一个**解析结果,两端不可能分叉(A5 度量≡渲染);
 * - 宽度:以**归属字体 id** 询问 mc.font.splitter(粗体加宽、provider 覆盖、
 *   未来任何样式效应自动携带,零隐性约定);
 * - 缩放:`spec.emPx / 归属字体.providerEmPx`,与位图 pose 比 k 同源(I3)。
 *
 * 归属=自身时公式退化为自身字形集 × 自身网格比;行盒/基线/装饰按自身
 * 自然度量 × 自身网格比(网格一律以字体自报 [PlatformFont.providerEmPx]
 * 为准,不再有第二份硬编码值)。kern 恒 0(原版无字距)。
 */
internal class VanillaPipelineMetrics private constructor(
    private val font: PlatformFont,
    private val spec: MeasureSpec,
    /** 行盒/基线来源(自然度量,与字符宽无关) */
    private val natural: RunMetrics,
) : RunMetrics {

    /** 自身网格比(行盒/基线/装饰;advance 不用它 —— advance 按归属网格比) */
    private val ratio: Float = spec.emPx / font.providerEmPx

    private val cache = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    override fun advance(codepoint: Int): Float = cache.computeIfAbsent(codepoint) {
        val ch = String(Character.toChars(it))
        val owner = owningGlyphOwner(font, codepoint).font
        val style = (spec.style ?: Style.EMPTY).withFont(owner.id)
        mc.font.splitter
            .stringWidth(net.minecraft.network.chat.Component.literal(ch).setStyle(style)) *
            (spec.emPx / owner.providerEmPx)
    }

    override fun kern(prev: Int, next: Int): Float = 0f
    override val lineHeight: Float get() = natural.lineHeight * ratio
    override val baselineFromTop: Float get() = natural.baselineFromTop * ratio
    override val decorThicknessPx: Float get() = natural.decorThicknessPx * ratio

    companion object {
        private val pool =
            java.util.concurrent.ConcurrentHashMap<Pair<FontDescription, MeasureSpec>, VanillaPipelineMetrics>()

        fun of(
            font: PlatformFont,
            spec: MeasureSpec,
            natural: RunMetrics,
        ): RunMetrics = pool.computeIfAbsent(font.id to spec) {
            VanillaPipelineMetrics(font, spec, natural)
        }
    }
}

/**
 * 原版装饰几何(收口):下划线/删除线厚度与阴影偏移 = 行高的九分比、
 * 下限 1px —— 锚点对齐原版 Font(y+9 / y+4.5);网格行高动态取
 * mc.font.lineHeight(部分模组会修改),不写死。
 */

/** 原版位图度量(mc.font 自身;网格原生值) */
internal object VanillaRunMetrics : RunMetrics {

    /** 原版设计基线(0.8 × 设计行高,历史布局约定) */
    private const val VANILLA_LAYOUT_BASELINE = 7.2f

    override fun advance(codepoint: Int): Float =
        mc.font.splitter.stringWidth(String(Character.toChars(codepoint)))

    override val lineHeight: Float
        get() = mc.font.lineHeight.toFloat()

    override val baselineFromTop: Float = VANILLA_LAYOUT_BASELINE

    /** 原版装饰几何:设计上就是 1 网格像素 */
    override val decorThicknessPx: Float = 1f
}
