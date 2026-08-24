package moe.forpleuvoir.compose_minecraft.platform.ui.text
import moe.forpleuvoir.compose_minecraft.mc

import com.mojang.blaze3d.font.GlyphProvider
import com.mojang.blaze3d.font.TrueTypeGlyphProvider
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.logging.LogUtils
import moe.forpleuvoir.compose_minecraft.mixin.FontManagerAccessor
import moe.forpleuvoir.compose_minecraft.mixin.FontSetAccessor
import moe.forpleuvoir.compose_minecraft.mixin.MinecraftAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.font.FontOption
import net.minecraft.client.gui.font.FontSet
import net.minecraft.client.gui.font.GlyphStitcher
import net.minecraft.client.gui.font.providers.FreeTypeUtil
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FreeType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val LOGGER = LogUtils.getLogger()

/**
 * 自定义字体文件注册接入(T.32):把任意 ttf/otf/ttc 字体文件(系统目录、
 * 资源包旁挂载、业务自带等)注册为 MC 可渲染字体。
 *
 * MC 的字体渲染体系只能画「资源字体」(FontManager 按 font/<name>.json 装配的
 * [FontSet]),自定义字体文件不在资源体系内。这里把
 * 字体文件直接经 FreeType 构建 [TrueTypeGlyphProvider] → [FontSet],
 * 经 [FontManagerAccessor] 注入 FontManager.fontSets,业务方用
 * `FontDescription.Resource(id)` 或 [LocalDefaultFont] 选择即可渲染。
 *
 * - 字形渲染尺寸 size=9(与 MC 行高 9px 匹配,布局空间 9px),oversample=4(4x
 *   超采样,位图分辨率 36px;渲染尺寸 = 位图像素/oversample,故 36sp(4x)显示
 *   时位图 1:1 无放大糊,18sp 缩小 2x 抗锯齿更锐利,9sp 超采样最清晰;
 *   图集 256px 自动扩展新图集,容量无硬限);
 * - **缺字符回滚**:注册时把默认字体(minecraft:default)的 provider 链合并进
 *   自定义 FontSet(经 [FontSetAccessor] 读 allProviders),自定义字体缺字时
 *   回退到默认字体字形,而非 missing 方块;
 * - 懒加载:首次 [registerCustomFont] 才读文件建 face;
 * - 幂等自愈:资源重载(FontManager.apply 清空 fontSets)后,再次调用会重建
 *   ([ensureAlive] 由 [ComposeGuiRenderer] 每帧调用);
 * - 必须在渲染线程调用(纹理注册惯例)。
 *
 * 系统字体目录枚举([systemFontDir] / [listSystemFonts])只是辅助入口,
 * 注册本身接受任意路径的字体文件。
 */
object MinecraftCustomFonts {

    /** 字体度量(读 FreeType face,单位 = 字体设计单位 em)。 */
    data class FontMetricsInfo(
        val unitsPerEm: Int,
        val ascender: Int,
        val descender: Int,
    ) {
        /** 换算到 MC 9px 渲染行高(信息展示;MC 实际行高固定 9px,字形缩放不受影响)。 */
        val lineHeightPx9: Float
            get() = if (unitsPerEm > 0) (ascender - descender) / unitsPerEm.toFloat() * 9f else 0f
    }

    data class FontFileInfo(
        val fileName: String,
        val path: Path,
        val sizeBytes: Long,
        /** 字体族名(读 TTF/OTF name 表,name ID 1),读取失败为 null。 */
        val displayName: String? = null,
        /** 字体度量(unitsPerEm/ascender/descender),读取失败为 null。 */
        val metrics: FontMetricsInfo? = null,
    )

    private data class Entry(
        val identifier: Identifier,
        val path: Path,
        val provider: GlyphProvider,
        val fontSet: FontSet,
        val platform: moe.forpleuvoir.compose_minecraft.platform.render.text.CustomFreeTypeFont,
    )

    private val entries = ConcurrentHashMap<Identifier, Entry>()

    private val fontManagerAccessor: FontManagerAccessor?
        get() = runCatching {
            (mc as MinecraftAccessor).fontManager() as FontManagerAccessor
        }.getOrNull()

    /** 系统字体目录:Windows(C:\Windows\Fonts)优先,其次常见 Unix/macOS 路径。 */
    fun systemFontDir(): Path? {
        val sysRoot = System.getenv("SystemRoot")
        if (sysRoot != null) {
            val fonts = Path.of(sysRoot, "Fonts")
            if (Files.isDirectory(fonts)) return fonts
        }
        for (p in listOf("/usr/share/fonts", "/usr/local/share/fonts", "/Library/Fonts", "/System/Library/Fonts")) {
            val f = Path.of(p)
            if (Files.isDirectory(f)) return f
        }
        return null
    }

    /** 枚举系统字体目录中的字体文件(*.ttf / *.otf / *.ttc),按文件名排序;附带字体族名。 */
    fun listSystemFonts(): List<FontFileInfo> {
        val dir = systemFontDir() ?: return emptyList()
        return runCatching {
            Files.list(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter {
                        val n = it.fileName.toString().lowercase()
                        n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")
                    }
                    .sorted(Comparator.comparing { it.fileName.toString().lowercase() })
                    .map {
                        val info = readFontInfo(it)
                        FontFileInfo(
                            it.fileName.toString(),
                            it,
                            runCatching { Files.size(it) }.getOrDefault(0L),
                            info.familyName,
                            info.metrics,
                        )
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private data class FontInfo(val familyName: String?, val metrics: FontMetricsInfo?)

    /**
     * 读字体文件的族名(name ID 1)与度量(unitsPerEm/ascender/descender,经 FreeType face)。
     * .ttc 集合文件取第一个 face。失败返回 null(文件损坏/非字体)。
     */
    private fun readFontInfo(path: Path): FontInfo {
        val data = runCatching { TextureUtil.readResource(Files.newInputStream(path)) }.getOrNull()
            ?: return FontInfo(null, null)
        try {
            return synchronized(FreeTypeUtil.LIBRARY_LOCK) {
                MemoryStack.stackPush().use { stack ->
                    val faceBuffer = stack.mallocPointer(1)
                    if (FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), data, 0L, faceBuffer) != 0) {
                        return@use FontInfo(null, null)
                    }
                    val face = FT_Face.create(faceBuffer.get())
                    val info = try {
                        FontInfo(
                            face.family_nameString()?.takeIf { it.isNotBlank() },
                            FontMetricsInfo(
                                face.units_per_EM().toInt(),
                                face.ascender().toInt(),
                                face.descender().toInt(),
                            ),
                        )
                    } finally {
                        FreeType.FT_Done_Face(face)
                    }
                    info
                }
            }
        } finally {
            MemoryUtil.memFree(data)
        }
    }

    /** 由文件名生成稳定的字体 Identifier(compose_minecraft:custom/<slug>)。 */
    fun identifierFor(fileName: String): Identifier {
        val base = fileName.substringBeforeLast('.').lowercase()
        val slug = base.replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48).ifEmpty { "font" }
        return Identifier.fromNamespaceAndPath("compose_minecraft", "custom/$slug")
    }

    /**
     * 便捷重载:由文件名自动生成 [Identifier] 并注册。
     * @return 注册成功返回生成的 [Identifier],失败返回 null。
     */
    fun registerCustomFont(path: Path): Identifier? {
        val id = identifierFor(path.fileName.toString())
        return if (registerCustomFont(id, path)) id else null
    }

    /**
     * 注册(或重建)一个自定义字体文件为可渲染字体。
     *
     * 幂等:已注册且 FontManager 仍持有同一 [FontSet] 时直接返回 true;
     * 资源重载清空后再次调用会重建(先关旧 provider)。失败返回 false 并记日志。
     * 必须在渲染线程调用。
     */
    fun registerCustomFont(identifier: Identifier, path: Path): Boolean {
        val accessor = fontManagerAccessor
        if (accessor == null) {
            LOGGER.error("[ComposeMinecraft] cannot access FontManager, custom font registration skipped: {}", identifier)
            return false
        }
        // 已注册且仍有效 → 直接复用
        val current = accessor.fontSets()[identifier]
        entries[identifier]?.let { entry ->
            if (current === entry.fontSet) return true
            // 资源重载已清空:关旧 provider 后重建
            runCatching { entry.provider.close() }
            entries.remove(identifier)
        }
        var provider: GlyphProvider? = null
        return runCatching {
            val fontData = TextureUtil.readResource(Files.newInputStream(path))
            try {
                synchronized(FreeTypeUtil.LIBRARY_LOCK) {
                    val face: FT_Face = MemoryStack.stackPush().use { stack ->
                        val faceBuffer = stack.mallocPointer(1)
                        FreeTypeUtil.assertError(
                            FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), fontData, 0L, faceBuffer),
                            "Initializing font face",
                        )
                        FT_Face.create(faceBuffer.get())
                    }
                    val format = FreeType.FT_Get_Font_Format(face)
                    if (format != "TrueType" && format != "CFF") {
                        FreeType.FT_Done_Face(face)
                        MemoryUtil.memFree(fontData)
                        error("Font is not TTF/OTF, was $format")
                    }
                    FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE), "Find unicode charmap")
                    provider = TrueTypeGlyphProvider(fontData, face, 9f, 4f, 0f, 0f, "")
                }
                val fontSet = FontSet(GlyphStitcher(mc.textureManager, identifier))
                // 缺字符回滚:自定义 provider 在前,默认字体 provider 链在后(缺字 → 默认字形,而非 missing 方块)
                val defaultProviders: List<GlyphProvider.Conditional> = runCatching {
                    (accessor.fontSets()[Identifier.withDefaultNamespace("default")] as? FontSetAccessor)?.allProviders()
                        ?: emptyList()
                }.getOrDefault(emptyList())
                fontSet.reload(
                    listOf(GlyphProvider.Conditional(provider!!, FontOption.Filter.ALWAYS_PASS)) + defaultProviders,
                    setOf(),
                )
                accessor.fontSets()[identifier] = fontSet
                // P3:同步接入平台字体体系(度量=splitter 权威计宽,行盒=FreeType 自然值)
                val info = runCatching { readFontInfo(path).metrics }.getOrNull()
                val platform = (moe.forpleuvoir.compose_minecraft.platform.render.text.FontRegistry[net.minecraft.network.chat.FontDescription.Resource(identifier)]
                    as? moe.forpleuvoir.compose_minecraft.platform.render.text.CustomFreeTypeFont)
                    ?: moe.forpleuvoir.compose_minecraft.platform.render.text.CustomFreeTypeFont(
                        net.minecraft.network.chat.FontDescription.Resource(identifier),
                    ).also { moe.forpleuvoir.compose_minecraft.platform.render.text.FontRegistry.register(it) }
                info?.let { m ->
                    if (m.unitsPerEm > 0) {
                        val g = 9f / m.unitsPerEm
                        platform.naturalLineHeight = (m.ascender - m.descender) * g
                        platform.naturalBaseline = m.ascender * g
                    }
                }
                entries[identifier] = Entry(identifier, path, provider, fontSet, platform)
                true
            } catch (t: Throwable) {
                runCatching { provider?.close() }
                throw t
            }
        }.getOrElse { e ->
            LOGGER.error("[ComposeMinecraft] failed to register custom font {} ({})", identifier, path, e)
            false
        }
    }

    /**
     * 资源重载自愈:FontManager 重载会清空 fontSets,已注册字体随之失效。
     * 由 [ComposeGuiRenderer] 每帧调用(仅当有已注册字体且被清空时才重建,开销 O(1))。
     */
    fun ensureAlive() {
        val accessor = fontManagerAccessor ?: return
        for ((identifier, path, _, fontSet) in entries.values.toList()) {
            if (accessor.fontSets()[identifier] !== fontSet) {
                registerCustomFont(identifier, path)
            }
        }
    }

    /** 卸载已注册字体(从 FontManager 移除并关闭 provider)。 */
    fun unregisterCustomFont(identifier: Identifier) {
        fontManagerAccessor?.fontSets()?.remove(identifier)
        moe.forpleuvoir.compose_minecraft.platform.render.text.FontRegistry.unregister(net.minecraft.network.chat.FontDescription.Resource(identifier))
        entries.remove(identifier)?.let { runCatching { it.provider.close() } }
    }

    /** 已注册的自定义字体 id 列表。 */
    fun registeredFonts(): List<Identifier> = entries.keys.toList()
}
