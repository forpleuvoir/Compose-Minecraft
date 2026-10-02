@file:JvmName("StyleExtensions")
@file:Suppress("CAST_NEVER_SUCCEEDS")

package moe.forpleuvoir.compose_minecraft.platform.ui.text

import androidx.compose.ui.graphics.Color
import moe.forpleuvoir.compose_minecraft.mixin.StyleAccessor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt
import androidx.compose.foundation.text.BasicText

/**
 * MC [Style] 的 Kotlin 扩展:补齐 MC public getter <b>能力不足</b>的字段访问,并提供 Compose 侧
 * 互转便利方法,替代原 McTextStyle 包装。
 *
 * <h3>走 Mixin [StyleAccessor] 的字段(6 个)</h3>
 * 5 个布尔三态 + font 原始 null(见各属性 doc)。经 mixin 生成的 accessor 直接读私有字段。
 *
 * <h3>直接用 MC public getter 的字段(5 个,Kotlin 中即属性)</h3>
 * 下面的字段无需导入本扩展,直接以 [Style] 上的属性访问即可:
 * - [Style.color](MC `getColor(): TextColor?`,保留 null)
 * - [Style.shadowColor](MC `getShadowColor(): Integer?`,保留 null;Kotlin 中为 `Int?`)
 * - [Style.clickEvent](MC `getClickEvent()`,保留 null)
 * - [Style.hoverEvent](MC `getHoverEvent()`,保留 null)
 * - [Style.insertion](MC `getInsertion(): String?`,保留 null)
 *
 * <p>Style 不可变,写入请用 `Style.EMPTY.withXxx(...)` 或扩展 [withColor] / [withFont]。
 */

/** 加粗三态(null = 未设置,false = 显式不加粗)。MC `isBold()` 会把 null 压成 false,丢失三态。 */
val Style.boldRaw: Boolean?
    get() = (this as StyleAccessor).bold()

/** 斜体三态(null = 未设置)。 */
val Style.italicRaw: Boolean?
    get() = (this as StyleAccessor).italic()

/** 下划线三态(null = 未设置)。 */
val Style.underlinedRaw: Boolean?
    get() = (this as StyleAccessor).underlined()

/** 删除线三态(null = 未设置)。 */
val Style.strikethroughRaw: Boolean?
    get() = (this as StyleAccessor).strikethrough()

/** 乱码三态(null = 未设置)。 */
val Style.obfuscatedRaw: Boolean?
    get() = (this as StyleAccessor).obfuscated()

/**
 * 字体描述(原始 nullable;null = 未设置)。与 MC `Style.getFont()` 的差异:
 * `getFont()` 在字段为 null 时返回 `FontDescription.DEFAULT`,本扩展返回原始 null。
 */
val Style.fontOriginal: FontDescription?
    get() = (this as StyleAccessor).font()

/**
 * 构造带本样式的 MC [Component]。平台适配点:替代原 McTextStyle 的 toComponent。
 */
fun Style.toComponent(text: String): Component = Component.literal(text).withStyle(this)

/**
 * 用 Compose [Color] 构造颜色设置后的 [Style](颜色只取 RGB,MC `Style` 颜色不携带 alpha)。
 * 平台适配点:替代原 McTextStyle(color = ...) 链式构造的便捷入口。
 */
fun Style.withColor(color: Color): Style = withColor(TextColor.fromRgb(color.toRgb()))

/**
 * 用资源包字体 [Identifier] 构造字体设置后的 [Style]。null 表示清除字体(用 MC 默认)。
 */
fun Style.withFont(font: Identifier?): Style = withFont(font?.let { FontDescription.Resource(it) })

/**
 * 将当前的可变文本对象转换为一个扁平化的、不可变的文本列表。
 *
 * 平台适配点:递归展平 [MutableComponent] 的 siblings —— 每个子文本(含自身)
 * 都被视为独立的 [Component];样式按原版 `Component.visit` 语义逐层继承合并
 * (`Component.java:71-85`:`selfStyle = own.applyTo(parentEffective)`,自身已设置
 * 的属性优先,缺失属性取最近祖先),根段之外缺失的属性由调用方
 * ([BasicText] 的 defaultStyle)兜底补缺。
 *
 * @return 展平后的文本段列表,每段为带继承合并样式的不可变 [Component]。
 */
fun MutableComponent.flat(): List<Component> = flat(Style.EMPTY)

/** 展平实现:[parentStyle] = 祖先链已合并的有效样式,自身样式覆盖其上后向下传递。 */
private fun MutableComponent.flat(parentStyle: Style): List<Component> {
    val selfStyle = this@flat.style.applyTo(parentStyle)
    return buildList {
        // 首先添加当前文本对象的内容和继承合并后的样式作为一个新的不可变文本对象
        add(MutableComponent.create(this@flat.contents).setStyle(selfStyle))

        // 遍历当前文本对象的所有子文本,递归展平(继承链随调用下传)
        this@flat.siblings.forEach { text ->
            addAll(text.flatten(selfStyle))
        }
    }
}

/**
 * 平台适配点:把任意 [Component] 展平为带继承合并样式的段列表。
 * [MutableComponent] 走 [flat];不可变 [Component] 视为单段。
 */
fun Component.flatten(): List<Component> = flatten(Style.EMPTY)

/** [flatten] 的继承链实现;非 [MutableComponent] 无法递归,按单段合并样式包装。 */
private fun Component.flatten(parentStyle: Style): List<Component> =
    if (this is MutableComponent) flat(parentStyle)
    else listOf(MutableComponent.create(this.contents).setStyle(this.style.applyTo(parentStyle)))

/** MC [TextColor] → Compose [Color](补全 alpha 为不透明;TextColor 不携带 alpha)。 */
fun TextColor.toColor(): Color = Color(0xFF000000.toInt() or value)

/** Compose `Color`(Float 通道)→ 0xRRGGBB(MC `Style` 颜色不携带 alpha)。被 [withColor] 使用。 */
internal fun Color.toRgb(): Int =
    ((red * 255f).roundToInt() shl 16) or
        ((green * 255f).roundToInt() shl 8) or
        (blue * 255f).roundToInt()