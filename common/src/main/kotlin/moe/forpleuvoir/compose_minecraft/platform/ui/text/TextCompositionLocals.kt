/*
 * Copyright 2026 The Compose-Minecraft Project
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

package moe.forpleuvoir.compose_minecraft.platform.ui.text

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier

/**
 * 默认文本样式(T.30):`BasicText` 等文本组件**未显式传 [TextStyle]**时使用的默认样式。
 * 默认 [TextStyle.Default];业务可用
 * [androidx.compose.runtime.CompositionLocalProvider] 覆盖(如全局字号/颜色)。
 *
 * 平台适配点:官方 BasicText 的 style 默认值是编译期常量 [TextStyle.Default],
 * 无 CompositionLocal 兜底;本平台改为可空 + 本 Local 兜底,便于全局换肤。
 */
val LocalDefaultTextStyle = staticCompositionLocalOf<TextStyle> { TextStyle.Default }

/**
 * 默认字体(T.30):文本**未显式指定字体**(`SpanStyle.platformStyle.font`)时使用的
 * MC 字体。默认 [MinecraftFonts.Default](minecraft:default);业务可 Provider 覆盖为
 * [MinecraftFonts] 中的其它字体(alt/unifont/illageralt/资源包字体)或
 * [rememberCustomFont] 加载的自定义字体。
 */
val LocalDefaultFont = staticCompositionLocalOf<FontDescription> { MinecraftFonts.Default }

/**
 * 默认字号(T.32):`BasicText` 等文本组件的 [TextStyle]**未显式指定
 * `fontSize`**(`TextStyle.Default` 语义)时使用的默认字号。
 * 默认 18.sp(2x 平台基准字号,9sp = 1x 原生像素);业务可用
 * [androidx.compose.runtime.CompositionLocalProvider] 覆盖(显式传 `fontSize`
 * 的样式优先于本 Local)。
 */
val LocalDefaultFontSize = staticCompositionLocalOf<TextUnit> { 18.sp }

/**
 * MC 资源字体清单(T.30):Minecraft 渲染体系可用的内置字体
 * (字体定义资源 `font/<name>.json`,经 [FontDescription.Resource] 引用)。
 *
 * - [Default]:minecraft:default,默认字体(ASCII 8px、CJK 等宽位图);
 * - [Alt]:minecraft:alt,备选字体(更紧凑的字形集);
 * - [UniFont]:minecraft:unifont,Unicode 全量字体(启用「强制 Unicode 字体」时使用);
 * - [IllagerAlt]:minecraft:illageralt,灾厄村民文字(替换字形);
 * - [Missing]:minecraft:missing,缺字字体(占位方格)。
 *
 * 注:资源包自定义字体(id 由资源包 `font/<name>.json` 定义)可直接构造
 * `FontDescription.Resource(Identifier)` 引用,运行时按 id 解析。
 */
object MinecraftFonts {
    val Default: FontDescription = FontDescription.DEFAULT
    val Alt: FontDescription = FontDescription.Resource(Identifier.withDefaultNamespace("alt"))
    val UniFont: FontDescription = FontDescription.Resource(Identifier.withDefaultNamespace("unifont"))
    val IllagerAlt: FontDescription = FontDescription.Resource(Identifier.withDefaultNamespace("illageralt"))
    val Missing: FontDescription = FontDescription.Resource(Identifier.withDefaultNamespace("missing"))
}
