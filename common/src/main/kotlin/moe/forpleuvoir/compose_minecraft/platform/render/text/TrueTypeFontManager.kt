package moe.forpleuvoir.compose_minecraft.platform.render.text

import com.mojang.logging.LogUtils
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 字体加载与选择(T.TT,D1 开放设计 + D4 失败回退)。
 *
 * 双字重槽位:
 * - **常规体**:[TextRenderConfig.fontSources] —— 布局度量与默认字形来源,
 *   全部失败 → [isReady] == false,调用方回退原版渲染;
 * - **粗体字重**:[TextRenderConfig.boldFontSources](可选)—— 非空且加载成功时
 *   粗体 run 使用真粗体字形;为空/全部失败 → 退化膨胀合成粗体。
 *
 * - 懒加载:首次查询时同步加载(一次性 IO,布局线程可接受);
 * - 配置指纹:各槽位对应列表内容 hash 变化即失效重载;失败按指纹缓存避免重试;
 * - 线程:加载加锁幂等;查询路径无锁读。
 */
internal object TrueTypeFontManager {

    private val LOGGER: Logger = LogUtils.getLogger()

    private class Loaded(
        val font: TrueTypeFont,
        /** 实际加载成功的字体源路径(优先级列表中靠前的失效源会被跳过) */
        val sourcePath: String,
        /** 常规体才有度量(粗体只用于绘制字形,不参与布局) */
        val metrics: TrueTypeMetricsSource?,
        val configFingerprint: Int,
    )

    @Volatile
    private var regular: Loaded? = null

    @Volatile
    private var bold: Loaded? = null

    @Volatile
    private var regularFailedFingerprint: Int = 0

    @Volatile
    private var boldFailedFingerprint: Int = 0

    /** 当前生效常规字体(未加载/全部失败返回 null) */
    fun fontOrNull(): TrueTypeFont? = ensureRegular()?.font

    /** 当前生效字体的度量来源 */
    fun metricsOrNull(): TrueTypeMetricsSource? = ensureRegular()?.metrics

    /** 当前生效粗体字重(未配置/全部失败返回 null → 调用方退化合成粗体) */
    fun boldFontOrNull(): TrueTypeFont? = ensureBold()?.font

    /** 常规体是否就绪 */
    val isReady: Boolean
        get() = ensureRegular() != null

    private fun regularFingerprint(): Int =
        java.util.Objects.hashCode(TextRenderConfig.fontSources)

    private fun boldFingerprint(): Int =
        java.util.Objects.hashCode(TextRenderConfig.boldFontSources)

    private fun ensureRegular(): Loaded? {
        val fp = regularFingerprint()
        regular?.let { if (it.configFingerprint == fp) return it }
        if (regularFailedFingerprint == fp) return null
        synchronized(this) {
            regular?.let { if (it.configFingerprint == fp) return it }
            if (regularFailedFingerprint == fp) return null
            val result = loadFromConfig(TextRenderConfig.fontSources, fp)
            if (result == null) {
                regularFailedFingerprint = fp
                LOGGER.error(
                    "[ComposeMinecraft] TrueType font load failed for all {} source(s); falling back to vanilla text rendering",
                    TextRenderConfig.fontSources.size,
                )
            } else {
                LOGGER.info("[ComposeMinecraft] TrueType font ready: {}", result.sourcePath)
            }
            regular = result
            return result
        }
    }

    /** 粗体字重懒加载(可选槽位) */
    private fun ensureBold(): Loaded? {
        if (TextRenderConfig.boldFontSources.isEmpty()) return null
        val fp = boldFingerprint()
        bold?.let { if (it.configFingerprint == fp) return it }
        if (boldFailedFingerprint == fp) return null
        synchronized(this) {
            bold?.let { if (it.configFingerprint == fp) return it }
            if (boldFailedFingerprint == fp) return null
            val result = loadFromConfig(TextRenderConfig.boldFontSources, fp)
            if (result == null) {
                boldFailedFingerprint = fp
                LOGGER.warn("[ComposeMinecraft] bold font sources all failed; synthetic bold will be used")
            } else {
                LOGGER.info("[ComposeMinecraft] bold font ready: {}", result.sourcePath)
            }
            bold = result
            return result
        }
    }

    /** 按给定字体源列表优先级逐个尝试(D1/D4) */
    private fun loadFromConfig(sources: List<FontSource>, fingerprint: Int): Loaded? {
        sources.forEach { source ->
            try {
                val file: Path = Paths.get(source.path)
                if (!Files.isRegularFile(file)) {
                    LOGGER.warn("[ComposeMinecraft] font source not found: {}", source.path)
                    return@forEach
                }
                val font = TrueTypeFont.load(TrueTypeFont.readFile(file), TextRenderConfig.baseFontSizePx)
                // 度量仅常规体需要(粗体字形不参与布局);粗体槽传 null
                // 布局度量 = 混合源:TTF 缺字码点回退原版度量(布局与绘制同源,
                // 缺字回退段不再需要任何缩放适配 —— 预留宽即原版渲染宽)
                val metrics = if (sources === TextRenderConfig.fontSources) {
                    TrueTypeMetricsSource(font, VanillaMetricsSource)
                } else null
                return Loaded(font, source.path, metrics, fingerprint)
            } catch (t: Throwable) {
                LOGGER.error("[ComposeMinecraft] failed to load font source {}", source.path, t)
            }
        }
        return null
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
