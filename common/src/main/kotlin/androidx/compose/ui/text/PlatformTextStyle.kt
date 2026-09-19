/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.compose.ui.text

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.HoverEvent

/**
 * Platform specific configuration for [SpanStyle] on Desktop.
 *
 * 平台适配点:TextStyle 已恢复移植,此处为其补回 PlatformTextStyle 外壳
 * 与 createPlatformTextStyle;官方 desktop 版含 textDecorationLineStyle/
 * fontRasterizationSettings(ExperimentalTextApi),项目无这些类型,保持简化
 * (spanStyle/paragraphStyle 引用项目既有 PlatformSpanStyle(blendRadius)/
 * PlatformParagraphStyle(emojiSupportMatch))。
 * 同时承载 MC 原版 [net.minecraft.network.chat.Style] 的**全部渲染/交互特性**
 * (obfuscated/shadowColor/clickEvent/hoverEvent/insertion/font),保证
 * TextStyle 化后语义不丢失(经 [moe.forpleuvoir.compose_minecraft.platform.ui.text.toPlatformData]
 * 映射回 MC Style)。
 *
 * @param blendRadius Fractional distance from the font baseline to the original platform text
 *   bounds top. The platform text bounds are used to calculate the text blending in a large font
 *   radius gradient. Default value is null which represents that the value is unspecified, and the
 *   text will be blended based on the font size.
 */
@Immutable
class PlatformSpanStyle(
    val blendRadius: Float? = null,

    /** MC 乱码(obfuscated)效果;null = 未设置。平台适配点。 */
    val obfuscated: Boolean? = null,

    /** MC 文本阴影色(ARGB);null = 未设置(MC 默认关阴影)。平台适配点。 */
    val shadowColor: Color? = null,

    /** MC 点击事件;null = 未设置。平台适配点。 */
    val clickEvent: ClickEvent? = null,

    /** MC 悬停事件;null = 未设置。平台适配点。 */
    val hoverEvent: HoverEvent? = null,

    /** MC 插入文本(shift+点击插入聊天);null = 未设置。平台适配点。 */
    val insertion: String? = null,

    /** MC 资源包字体;null = MC 默认字体。平台适配点。 */
    val font: FontDescription? = null,

    /** @suppress */
    val apiVersion: Int = DefaultApiVersion,
) {
    fun merge(other: PlatformSpanStyle?): PlatformSpanStyle {
        if (other == null) return this
        return PlatformSpanStyle(
            blendRadius = other.blendRadius ?: this.blendRadius,
            obfuscated = other.obfuscated ?: this.obfuscated,
            shadowColor = other.shadowColor ?: this.shadowColor,
            clickEvent = other.clickEvent ?: this.clickEvent,
            hoverEvent = other.hoverEvent ?: this.hoverEvent,
            insertion = other.insertion ?: this.insertion,
            font = other.font ?: this.font,
            apiVersion = if (apiVersion < other.apiVersion) other.apiVersion else apiVersion,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlatformSpanStyle) return false

        if (blendRadius != other.blendRadius) return false
        if (obfuscated != other.obfuscated) return false
        if (shadowColor != other.shadowColor) return false
        if (clickEvent != other.clickEvent) return false
        if (hoverEvent != other.hoverEvent) return false
        if (insertion != other.insertion) return false
        if (font != other.font) return false
        if (apiVersion != other.apiVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = blendRadius?.hashCode() ?: 0
        result = 31 * result + (obfuscated?.hashCode() ?: 0)
        result = 31 * result + (shadowColor?.hashCode() ?: 0)
        result = 31 * result + (clickEvent?.hashCode() ?: 0)
        result = 31 * result + (hoverEvent?.hashCode() ?: 0)
        result = 31 * result + (insertion?.hashCode() ?: 0)
        result = 31 * result + (font?.hashCode() ?: 0)
        result = 31 * result + apiVersion
        return result
    }

    override fun toString(): String {
        return "PlatformSpanStyle(blendRadius=$blendRadius, obfuscated=$obfuscated, " +
            "shadowColor=$shadowColor, clickEvent=$clickEvent, hoverEvent=$hoverEvent, " +
            "insertion=$insertion, font=$font)"
    }

    companion object {
        /** @suppress */
        const val DefaultApiVersion = 1

        /** @suppress */
        internal val Default = PlatformSpanStyle()
    }
}

/**
 * Platform specific configuration for [ParagraphStyle] on Desktop.
 *
 * @param emojiSupportMatch Controls how emojis are resolved. This needs to be set in conjunction
 *   with the `EmojiSupportMatch` that's defined in `EmojiCompat` if it is used.
 */
@Immutable
class PlatformParagraphStyle(
    /** @suppress */
    val emojiSupportMatch: EmojiSupportMatch = EmojiSupportMatch.Default,

    /** @suppress */
    val apiVersion: Int = DefaultApiVersion,
) {
    fun merge(other: PlatformParagraphStyle?): PlatformParagraphStyle {
        if (other == null) return this
        return PlatformParagraphStyle(
            emojiSupportMatch =
                if (emojiSupportMatch == EmojiSupportMatch.Default) {
                    other.emojiSupportMatch
                } else {
                    emojiSupportMatch
                },
            apiVersion = if (apiVersion < other.apiVersion) other.apiVersion else apiVersion,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlatformParagraphStyle) return false

        if (emojiSupportMatch != other.emojiSupportMatch) return false
        if (apiVersion != other.apiVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = emojiSupportMatch.hashCode()
        result = 31 * result + apiVersion
        return result
    }

    override fun toString(): String {
        return "PlatformParagraphStyle(emojiSupportMatch=$emojiSupportMatch)"
    }

    companion object {
        /** @suppress */
        const val DefaultApiVersion = 1

        /** @suppress */
        internal val Default = PlatformParagraphStyle()
    }
}

/**
 * Platform specific configuration for [TextStyle] on Desktop.
 *
 * 平台适配点:TextStyle 恢复移植后补回的外壳(官方 desktop 简化版,
 * 去掉 ExperimentalTextApi 的 textDecorationLineStyle 构造)。
 */
@Immutable
class PlatformTextStyle(
    val spanStyle: PlatformSpanStyle?,
    val paragraphStyle: PlatformParagraphStyle?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlatformTextStyle) return false
        if (paragraphStyle != other.paragraphStyle) return false
        if (spanStyle != other.spanStyle) return false
        return true
    }

    override fun hashCode(): Int {
        var result = spanStyle?.hashCode() ?: 0
        result = 31 * result + (paragraphStyle?.hashCode() ?: 0)
        return result
    }
}

internal fun createPlatformTextStyle(
    spanStyle: PlatformSpanStyle?,
    paragraphStyle: PlatformParagraphStyle?,
): PlatformTextStyle {
    return PlatformTextStyle(spanStyle, paragraphStyle)
}

internal fun lerp(
    start: PlatformSpanStyle,
    stop: PlatformSpanStyle,
    fraction: Float,
): PlatformSpanStyle {
    if (start == stop) return start
    return when {
        fraction >= 1f -> stop
        fraction <= 0f -> start
        else -> {
            val fractionBlendRadius = lerp(start.blendRadius, stop.blendRadius, fraction)
            when {
                start.apiVersion == PlatformSpanStyle.DefaultApiVersion &&
                    stop.apiVersion == PlatformSpanStyle.DefaultApiVersion ->
                    PlatformSpanStyle(
                        blendRadius = fractionBlendRadius,
                        // MC 渲染特性为离散值(布尔/对象),取 fraction < 0.5 的一侧
                        obfuscated = lerpDiscrete(start.obfuscated, stop.obfuscated, fraction),
                        shadowColor = lerpDiscrete(start.shadowColor, stop.shadowColor, fraction),
                        clickEvent = lerpDiscrete(start.clickEvent, stop.clickEvent, fraction),
                        hoverEvent = lerpDiscrete(start.hoverEvent, stop.hoverEvent, fraction),
                        insertion = lerpDiscrete(start.insertion, stop.insertion, fraction),
                        font = lerpDiscrete(start.font, stop.font, fraction),
                        apiVersion = PlatformSpanStyle.DefaultApiVersion,
                    )
                start.apiVersion > stop.apiVersion -> start
                else -> stop
            }
        }
    }
}

internal fun lerp(
    start: PlatformParagraphStyle,
    stop: PlatformParagraphStyle,
    fraction: Float,
): PlatformParagraphStyle {
    if (start == stop) return start
    return when {
        fraction >= 1f -> stop
        fraction <= 0f -> start
        else ->
            when {
                start.apiVersion == PlatformParagraphStyle.DefaultApiVersion &&
                    stop.apiVersion == PlatformParagraphStyle.DefaultApiVersion ->
                    PlatformParagraphStyle(
                        emojiSupportMatch = lerpDiscrete(start.emojiSupportMatch, stop.emojiSupportMatch, fraction),
                        apiVersion = PlatformParagraphStyle.DefaultApiVersion,
                    )
                start.apiVersion > stop.apiVersion -> start
                else -> stop
            }
    }
}

private fun lerp(start: Float?, stop: Float?, fraction: Float): Float? {
    return when {
        start == null && stop == null -> null
        start == null -> stop
        stop == null -> start
        else -> start + (stop - start) * fraction
    }
}

enum class EmojiSupportMatch {
    /**
     * Default behavior. Emojis are not pre-fetched from the system, and their resolution is
     * triggered lazily when they are first drawn.
     */
    Default,

    /** No emoji support. Emojis will be rendered using fallback fonts. */
    None,

    /** All emojis are supported, and their resolution is eager. */
    All
}
