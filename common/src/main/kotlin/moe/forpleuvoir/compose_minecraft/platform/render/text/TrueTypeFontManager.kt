package moe.forpleuvoir.compose_minecraft.platform.render.text

import com.mojang.logging.LogUtils
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 字体加载与选择(T.TT,D1 开放设计 + 缺字回退链)。
 *
 * 双链槽位:
 * - **常规体链**:[TextRenderConfig.fontSources] —— 全部加载为**有序回退链**:
     * 首个为主字体(布局度量来源),后续成员按码点兜底(主字体缺字时提供字形,
     * 并以自身度量参与排版);全部失败 → [isReady] == false,调用方回退原版渲染;
 * - **粗体链**:[TextRenderConfig.boldFontSources](可选)—— 同样成链,粗体 run
 *   沿粗体链查找字形;粗体链与常规链都缺字的码点 → 内联原版字形。
 *
 * - 懒加载:首次查询时同步加载(一次性 IO);
 * - 配置指纹:各链列表内容 hash 变化即整链重载;失败按指纹缓存避免重试;
 * - 线程:加载加锁幂等;查询路径无锁读。
 */
internal object TrueTypeFontManager {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** 一条字体回退链:有序字体 + 各自来源路径 + 配置指纹 */
    private class Chain(
        val fonts: List<TrueTypeFont>,
        val sourcePaths: List<String>,
        val metrics: TrueTypeMetricsSource?,
        val configFingerprint: Int,
    )

    @Volatile
    private var regular: Chain? = null

    @Volatile
    private var bold: Chain? = null

    @Volatile
    private var regularFailedFingerprint: Int = 0

    @Volatile
    private var boldFailedFingerprint: Int = 0

    /** 主字体(常规链首,未加载/全部失败返回 null) */
    fun fontOrNull(): TrueTypeFont? = ensureRegular()?.fonts?.firstOrNull()

    /** 主字体度量(布局用;含缺字回退原版度量的混合源) */
    fun metricsOrNull(): TrueTypeMetricsSource? = ensureRegular()?.metrics

    /** 常规体回退链(有序;空 = 未加载/全部失败) */
    fun regularChain(): List<TrueTypeFont> = ensureRegular()?.fonts ?: emptyList()

    /** 粗体回退链(有序;空 = 未配置粗体字重) */
    fun boldChain(): List<TrueTypeFont> = ensureBold()?.fonts ?: emptyList()

    /** 主字体是否就绪 */
    val isReady: Boolean
        get() = ensureRegular()?.fonts?.isNotEmpty() == true

    private fun regularFingerprint(): Int =
        java.util.Objects.hashCode(TextRenderConfig.fontSources)

    private fun boldFingerprint(): Int =
        java.util.Objects.hashCode(TextRenderConfig.boldFontSources)

    private fun ensureRegular(): Chain? {
        val fp = regularFingerprint()
        regular?.let { if (it.configFingerprint == fp) return it }
        if (regularFailedFingerprint == fp) return null
        synchronized(this) {
            regular?.let { if (it.configFingerprint == fp) return it }
            if (regularFailedFingerprint == fp) return null
            val result = loadChain(TextRenderConfig.fontSources, fp, bold = false)
            if (result == null) {
                regularFailedFingerprint = fp
                LOGGER.error(
                    "[ComposeMinecraft] font chain load failed for all {} source(s); falling back to vanilla text rendering",
                    TextRenderConfig.fontSources.size,
                )
            } else {
                LOGGER.info("[ComposeMinecraft] font chain ready: {}", result.sourcePaths)
            }
            regular = result
            return result
        }
    }

    /** 粗体链懒加载(可选槽位) */
    private fun ensureBold(): Chain? {
        if (TextRenderConfig.boldFontSources.isEmpty()) return null
        val fp = boldFingerprint()
        bold?.let { if (it.configFingerprint == fp) return it }
        if (boldFailedFingerprint == fp) return null
        synchronized(this) {
            bold?.let { if (it.configFingerprint == fp) return it }
            if (boldFailedFingerprint == fp) return null
            val result = loadChain(TextRenderConfig.boldFontSources, fp, bold = true)
            if (result == null) {
                boldFailedFingerprint = fp
                LOGGER.warn("[ComposeMinecraft] bold font chain all failed; synthetic bold will be used")
            } else {
                LOGGER.info("[ComposeMinecraft] bold font chain ready: {}", result.sourcePaths)
            }
            bold = result
            return result
        }
    }

    /** 加载一条字体链(逐源尝试,失败的源跳过并告警,成功的全部入链) */
    private fun loadChain(sources: List<FontSource>, fingerprint: Int, bold: Boolean): Chain? {
        val fonts = ArrayList<TrueTypeFont>(sources.size)
        val paths = ArrayList<String>(sources.size)
        sources.forEach { source ->
            try {
                val file: Path = Paths.get(source.path)
                if (!Files.isRegularFile(file)) {
                    LOGGER.warn("[ComposeMinecraft] font source not found, skipped: {}", source.path)
                    return@forEach
                }
                val font = TrueTypeFont.load(TrueTypeFont.readFile(file), TextRenderConfig.baseFontSizePx)
                fonts += font
                paths += source.path
                LOGGER.info("[ComposeMinecraft] font chain member[{}]: {}", fonts.size - 1, source.path)
            } catch (t: Throwable) {
                LOGGER.error("[ComposeMinecraft] failed to load font source {}", source.path, t)
            }
        }
        if (fonts.isEmpty()) return null
        // 度量仅常规链需要(粗体链只用于绘制字形,不参与布局)
        val metrics = if (!bold) TrueTypeMetricsSource(fonts, VanillaMetricsSource) else null
        return Chain(fonts, paths, metrics, fingerprint)
    }

    /** 主动失效缓存(配置外因变化时调用,如资源重载) */
    fun reset() {
        synchronized(this) {
            regular = null
            bold = null
            regularFailedFingerprint = 0
            boldFailedFingerprint = 0
        }
    }
}
