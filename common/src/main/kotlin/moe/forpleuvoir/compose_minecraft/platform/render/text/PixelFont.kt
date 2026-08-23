package moe.forpleuvoir.compose_minecraft.platform.render.text

import com.mojang.logging.LogUtils
import moe.forpleuvoir.compose_minecraft.mc
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer

/**
 * 平台内置 Fusion Pixel 像素字体族单例(P3 像素化)。
 *
 * **职责边界**(与 [TrueTypeFontManager] 严格分离):
 * - 本单例 = `compose_minecraft:fusion_pixel`(及 mono 变体)的**度量与覆盖判定**
 *   (布局 advance/行高/基线、缺字码点判定),em = [TextRenderConfig.pixelFontEmSp],
 *   与原版 FreeType 渲染端(`font/fusion_pixel*.json` 的 provider size 同值)逐像素一致;
 * - [TrueTypeFontManager] 链 = 自研 stb 渲染器的系统字体链,渲染 minecraft:default
 *   的 run —— 内置像素字体**绝不入链**。
 *
 * 懒加载一次(stb 解析,渲染线程);资源缺失时打 ERROR 并安全退回原版语义。
 */
internal object PixelFont {

    private val DEFAULT_ID: Identifier =
        Identifier.fromNamespaceAndPath("compose_minecraft", "fusion_pixel")

    private val LOGGER = LogUtils.getLogger()

    /**
     * 资源通道(MC 环境铁律):**必须经 `mc.resourceManager` 读取** —— 与
     * shader/lang 相同的资源包栈通道,Fabric dev 与发布环境行为一致。
     * 类加载器直读在 MC 运行时不可靠(P3 实测:静默失败 → 度量回退系统字体、
     * 输入框被原版位图字形顶替),已废弃。
     */
    private const val RESOURCE_DIR = "font/"

    /** 字体 id → 资源文件名(与 font/fusion_pixel*.json 的 provider file 一致) */
    private val FONT_RESOURCES: Map<Identifier, String> = mapOf(
        Identifier.fromNamespaceAndPath("compose_minecraft", "fusion_pixel") to
            "fusion-pixel-12px-proportional-zh_hans.ttf",
        Identifier.fromNamespaceAndPath("compose_minecraft", "fusion_pixel_mono") to
            "fusion-pixel-12px-monospaced-zh_hans.ttf",
    )

    private val fonts = HashMap<Identifier, TrueTypeFont>()

    /** 按字体 id 取实例(懒加载一次,失败打 ERROR 不再静默) */
    @Synchronized
    fun fontOf(id: Identifier): TrueTypeFont? {
        fonts[id]?.let { return it }
        val path = FONT_RESOURCES[id] ?: return null
        return runCatching {
            val bytes = mc.resourceManager
                .open(Identifier.fromNamespaceAndPath("compose_minecraft", "font/$path"))
                .use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() as ByteBuffer
            TrueTypeFont.load(buffer, TextRenderConfig.pixelFontEmSp)
        }.onFailure {
            LOGGER.error("[ComposeMinecraft] 像素字体加载失败: {}", path, it)
        }.getOrNull().also { loaded ->
            if (loaded != null) fonts[id] = loaded
        }
    }

    private val loaded: TrueTypeFont? by lazy { fontOf(DEFAULT_ID) }

    /** 像素字体实例(未打包/解析失败为 null,调用方按原版语义兜底) */
    fun fontOrNull(): TrueTypeFont? = loaded

    /** 像素字体是否覆盖该码点(空格恒视为覆盖;与 FreeType 渲染端同读一份 glyf) */
    fun covers(codepoint: Int): Boolean =
        codepoint == ' '.code || (loaded?.hasGlyph(codepoint) ?: false)

    /**
     * 像素优先度量源:覆盖码点取像素字体 advance/字距(与原版渲染端一致),
     * 缺字码点退回原版度量(该字符将由 minecraft:default 字形渲染,宽度即其
     * 真实渲染宽度)。行高/基线 = 像素字体自然行盒(@pixelFontEmSp em)。
     */
    val metricsSource: MetricsSource? by lazy {
        loaded?.let { pixel ->
            object : MetricsSource {
                override fun charAdvance(codepoint: Int): Float =
                    if (covers(codepoint)) pixel.codepointAdvance(codepoint)
                    else VanillaMetricsSource.charAdvance(codepoint)

                override fun codepointKern(prev: Int, next: Int): Float {
                    if (prev < 0 || !covers(next)) return 0f
                    if (!covers(prev)) return 0f
                    return pixel.codepointKernAdvance(prev, next)
                }

                override val lineHeight: Float
                    get() = pixel.lineHeightPx

                override val baselineFromTop: Float
                    get() = pixel.ascentPx
            }
        }
    }
}
