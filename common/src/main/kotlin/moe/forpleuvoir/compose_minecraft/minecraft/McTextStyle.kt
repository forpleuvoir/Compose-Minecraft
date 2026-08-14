package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * MC 文本样式:MC [Style] 能力字段 1:1 的基础封装(文本系统 MC 化的基准样式类型,T.0)。
 *
 * 平台定位(Basic 层级,不做风格化):
 * - 这是对 MC `net.minecraft.network.chat.Style` 的**能力封装**,不是风格化预设;
 * - 与 Compose `TextStyle` 的差异是本平台的既定决策(TextStyle 已被完全替换):
 *   - 没有 fontSize / lineHeight / letterSpacing 等排版参数 —— MC 文本行高固定 9px,
 *     字号第一版忽略(AGENTS.md 既定约束);
 *   - 没有 fontFamily / FontWeight 抽象 —— 字体直接以资源包字体 [font] 表达
 *     ([Style.withFont]);
 *   - [shadow] / [background] 对应 `GuiTextRenderState` 的 dropShadow / backgroundColor;
 *   - [obfuscated] 即 MC 乱码效果([Style.withObfuscated],字形逐帧随机替换)。
 *
 * @param color 文本颜色,对应 [Style.withColor](MC 默认文本为白色)。
 * @param bold 加粗,对应 [Style.withBold]。
 * @param italic 斜体,对应 [Style.withItalic]。
 * @param underlined 下划线,对应 [Style.withUnderlined]。
 * @param strikethrough 删除线,对应 [Style.withStrikethrough]。
 * @param obfuscated 乱码,对应 [Style.withObfuscated]。
 * @param font 资源包字体,对应 [Style.withFont];null = MC 默认字体。
 * @param shadow 是否绘制文本阴影(GuiTextRenderState.dropShadow)。
 * @param background 文本背景色(GuiTextRenderState.backgroundColor;Transparent = 无背景)。
 */
@Immutable
data class McTextStyle(
    val color: Color = Color.White,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underlined: Boolean = false,
    val strikethrough: Boolean = false,
    val obfuscated: Boolean = false,
    val font: Identifier? = null,
    val shadow: Boolean = false,
    val background: Color = Color.Transparent,
) {

    /** 构造 MC [Style](渲染/度量时使用;颜色只取 RGB,alpha 由渲染层单独携带)。 */
    fun toMcStyle(): Style {
        var style = Style.EMPTY.withColor(TextColor.fromRgb(color.toRgb()))
        if (bold) style = style.withBold(true)
        if (italic) style = style.withItalic(true)
        if (underlined) style = style.withUnderlined(true)
        if (strikethrough) style = style.withStrikethrough(true)
        if (obfuscated) style = style.withObfuscated(true)
        font?.let { style = style.withFont(FontDescription.Resource(it)) }
        return style
    }

    /** 构造带本样式的 MC [Component]。 */
    fun toComponent(text: String): Component = Component.literal(text).withStyle(toMcStyle())

    companion object {
        /** MC 默认样式:白色文本、无任何装饰、无阴影。 */
        val Default = McTextStyle()
    }
}

/** Compose Color(Float 通道)→ 0xRRGGBB(MC Style 颜色不携带 alpha)。 */
internal fun Color.toRgb(): Int =
    ((red * 255f).roundToInt() shl 16) or
        ((green * 255f).roundToInt() shl 8) or
        (blue * 255f).roundToInt()
