/*
 * Copyright 2025 The Android Open Source Project
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

import androidx.compose.ui.text.ExperimentalTextApi

/**
 * 运行平台枚举(Minecraft 平台 JVM 实现,替代 Skiko 的 Platform)。
 */
internal enum class Platform {
    Windows,
    Linux,
    MacOS,
    Android,
    IOS,
    TvOS,
    WatchOS,
    Unknown,
}

/**
 * 返回当前运行平台(JVM 属性检测)。
 */
internal fun currentPlatform(): Platform = when {
    System.getProperty("os.name")?.startsWith("Windows") == true -> Platform.Windows
    System.getProperty("os.name")?.startsWith("Mac") == true -> Platform.MacOS
    System.getProperty("os.name")?.startsWith("Linux") == true -> Platform.Linux
    else -> Platform.Unknown
}

/**
 * Whether edge pixels draw opaque or with partial transparency.
 */
@ExperimentalTextApi
enum class FontSmoothing {
    /**
     * no transparent pixels on glyph edges
     */
    None,

    /**
     * change transparency of the pixels to fit the pixel grid
     */
    AntiAlias,

    /**
     * change transparency and color of the pixels to fit the RGB subpixel grid
     */
    SubpixelAntiAlias,
}

/**
 * Level of glyph outline adjustment
 */
@ExperimentalTextApi
enum class FontHinting {
    /**
     * glyph outlines unchanged
     */
    None,

    /**
     * minimal modification to improve constrast
     */
    Slight,

    /**
     * glyph outlines modified to improve constrast
     */
    Normal,

    /**
     * modifies glyph outlines for maximum constrast
     */
    Full,
}

@ExperimentalTextApi
class FontRasterizationSettings(
    val smoothing: FontSmoothing,
    val hinting: FontHinting,
    val subpixelPositioning: Boolean,
    val autoHintingForced: Boolean,
) {
    companion object {
        val PlatformDefault by lazy {
            when (currentPlatform()) {
                Platform.Windows -> FontRasterizationSettings(
                    subpixelPositioning = true,
                    smoothing = FontSmoothing.AntiAlias,
                    hinting = FontHinting.Normal,
                    autoHintingForced = false,
                )

                Platform.Linux, Platform.Unknown -> FontRasterizationSettings(
                    subpixelPositioning = true,
                    smoothing = FontSmoothing.AntiAlias,
                    hinting = FontHinting.Slight,
                    autoHintingForced = false,
                )

                Platform.Android -> FontRasterizationSettings(
                    subpixelPositioning = true,
                    smoothing = FontSmoothing.AntiAlias,
                    hinting = FontHinting.Slight,
                    autoHintingForced = false,
                )

                Platform.MacOS, Platform.IOS, Platform.TvOS, Platform.WatchOS -> FontRasterizationSettings(
                    subpixelPositioning = true,
                    smoothing = FontSmoothing.AntiAlias,
                    hinting = FontHinting.Normal,
                    autoHintingForced = false,
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as FontRasterizationSettings

        if (smoothing != other.smoothing) return false
        if (hinting != other.hinting) return false
        if (subpixelPositioning != other.subpixelPositioning) return false
        if (autoHintingForced != other.autoHintingForced) return false

        return true
    }

    override fun hashCode(): Int {
        var result = smoothing.hashCode()
        result = 31 * result + hinting.hashCode()
        result = 31 * result + subpixelPositioning.hashCode()
        result = 31 * result + autoHintingForced.hashCode()
        return result
    }

    override fun toString(): String {
        return "FontRasterizationSettings(smoothing=$smoothing, hinting=$hinting, " +
            "subpixelPositioning=$subpixelPositioning, autoHintingForced=$autoHintingForced)"
    }
}
