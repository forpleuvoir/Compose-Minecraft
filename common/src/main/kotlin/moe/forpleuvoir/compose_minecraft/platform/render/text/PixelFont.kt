package moe.forpleuvoir.compose_minecraft.platform.render.text

import com.mojang.logging.LogUtils
import moe.forpleuvoir.compose_minecraft.mc
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer

/**
 * 平台内置 Fusion Pixel 像素字体文件加载(P1 重构后仅承担「资源加载」职责):
 * - 度量/覆盖/回退等**决策**已上收 [BuiltinFonts] 的 FusionPixelFont(普通
 *   PlatformFont 条目,零特判);本单例只负责把 ttf 资源解析为 [TrueTypeFont];
 * - **资源通道(MC 环境铁律)**:必须经 `mc.resourceManager` 读取 —— 与
 *   shader/lang 相同的资源包栈通道;类加载器直读在 MC 运行时不可靠
 *   (P3 实测静默失败),已废弃;
 * - 懒加载一次(stb 解析,渲染线程);失败打 ERROR 并返回 null,调用方落
 *   位图兜底(VanillaRunMetrics / MC_BITMAP 通道)。
 */
internal object PixelFont {

    private val LOGGER = LogUtils.getLogger()

    private val PROPORTIONAL_ID: Identifier =
        Identifier.fromNamespaceAndPath("compose_minecraft", "fusion_pixel")

    private val MONO_ID: Identifier =
        Identifier.fromNamespaceAndPath("compose_minecraft", "fusion_pixel_mono")

    /** 字体 id → 资源文件名(与 font/fusion_pixel*.json 的 provider file 一致) */
    private val FONT_RESOURCES: Map<Identifier, String> = mapOf(
        PROPORTIONAL_ID to "fusion-pixel-12px-proportional-zh_hans.ttf",
        MONO_ID to "fusion-pixel-12px-monospaced-zh_hans.ttf",
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

    /** proportional 变体(BuiltinFonts.fusionPixel 使用) */
    fun fontOfProportional(): TrueTypeFont? = fontOf(PROPORTIONAL_ID)

    /** mono 变体(BuiltinFonts.fusionPixelMono 使用;等宽场景) */
    fun fontOfMono(): TrueTypeFont? = fontOf(MONO_ID)
}
