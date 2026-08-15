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

/**
 * Platform specific configuration for [SpanStyle] on Desktop.
 *
 * 平台适配点:TextStyle 已完全替换为 Style,原 PlatformTextStyle 外壳与
 * createPlatformTextStyle 已移除;此处仅保留 SpanStyle/ParagraphStyle 仍引用的
 * 平台参数类型。
 *
 * @param blendRadius Fractional distance from the font baseline to the original platform text
 *   bounds top. The platform text bounds are used to calculate the text blending in a large font
 *   radius gradient. Default value is null which represents that the value is unspecified, and the
 *   text will be blended based on the font size.
 */
@Immutable
class PlatformSpanStyle(
    val blendRadius: Float? = null,

    /** @suppress */
    val apiVersion: Int = DefaultApiVersion,
) {
    fun merge(other: PlatformSpanStyle?): PlatformSpanStyle {
        if (other == null) return this
        return PlatformSpanStyle(
            blendRadius = other.blendRadius ?: this.blendRadius,
            apiVersion = if (apiVersion < other.apiVersion) other.apiVersion else apiVersion,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlatformSpanStyle) return false

        if (blendRadius != other.blendRadius) return false
        if (apiVersion != other.apiVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = blendRadius?.hashCode() ?: 0
        result = 31 * result + apiVersion
        return result
    }

    override fun toString(): String {
        return "PlatformSpanStyle(blendRadius=$blendRadius)"
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
