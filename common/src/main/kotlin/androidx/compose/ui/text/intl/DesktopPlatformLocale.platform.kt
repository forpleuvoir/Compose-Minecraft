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

package androidx.compose.ui.text.intl

internal fun createPlatformLocaleDelegate() = object : PlatformLocaleDelegate {
    override val current: LocaleList
        get() = LocaleList(listOf(Locale(java.util.Locale.getDefault())))
}

/** 平台底层 Locale 对象(Minecraft 平台为 JVM java.util.Locale) */
internal val Locale.platformLocale: java.util.Locale
    get() = javaLocale

/**
 * 判断该 Locale 是否为 RTL(从右到左)书写方向。
 *
 * 替代原 AWT ComponentOrientation 判断:直接按标准 RTL 语言列表匹配,
 * 不引入任何 AWT/desktop 依赖。
 */
internal fun Locale.isRtl(): Boolean = platformLocale.language in RtlLanguages

/** 标准 RTL 语言代码(ISO 639-1) */
private val RtlLanguages = setOf(
    "ar", // 阿拉伯语
    "dv", // 迪维希语
    "fa", // 波斯语
    "he", // 希伯来语
    "ps", // 普什图语
    "ur", // 乌尔都语
    "yi", // 意第绪语
)
