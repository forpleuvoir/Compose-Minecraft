/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.ui.text.platform

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver

/**
 * 弃用兼容 FontLoader(上游 Skia 版本为 SkiaFontLoader)。
 *
 * Minecraft 平台第一版不加载系统字体:文字渲染在阶段 E 接入 Minecraft 字体渲染器,
 * 因此 [load] 直接抛出不支持;字体解析统一走 [fontFamilyResolver]。
 */
@Suppress("DEPRECATION", "OverridingDeprecatedMember")
@Deprecated(
    "Replaced with PlatformFontLoader during the introduction of async fonts, all usages" +
        " should be replaced",
    ReplaceWith("PlatformFontLoader"),
)
class FontLoader : Font.ResourceLoader {

    internal val fontFamilyResolver: FontFamily.Resolver by lazy {
        createFontFamilyResolver()
    }

    @Deprecated(
        "Replaced by FontFamily.Resolver, this method should not be called",
        ReplaceWith("FontFamily.Resolver.resolve(font, )"),
    )
    override fun load(font: Font): Any {
        throw UnsupportedOperationException("FontLoader.load 阶段 E 实现(Minecraft 字体渲染器)")
    }
}
