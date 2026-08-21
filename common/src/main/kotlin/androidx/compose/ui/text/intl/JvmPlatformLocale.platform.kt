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

import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.text.isMcLanguageRtl
import moe.forpleuvoir.compose_minecraft.platform.text.mcLanguageCodeToLocale

/**
 * 平台 Locale 适配点(Minecraft):语言跟随 MC 游戏设置
 * (`mc.options.languageCode`),不是 JVM 系统默认。
 * 玩家在游戏内更改语言后,`LocaleList.current` 随之返回新语言。
 */
internal fun createPlatformLocaleDelegate() = object : PlatformLocaleDelegate {
    override val current: LocaleList
        get() = LocaleList(listOf(Locale(mcLanguageCodeToLocale())))
}

/** 平台底层 Locale 对象(Minecraft 平台为按 MC 游戏语言映射的 java.util.Locale) */
internal val Locale.platformLocale: java.util.Locale
    get() = javaLocale

/**
 * 判断该 Locale 是否为 RTL(从右到左)书写方向。
 *
 * 平台适配点:以 MC 语言包元数据为准(`Language.isDefaultRightToLeft`),
 * 替代原 AWT ComponentOrientation / 硬编码语言列表,不引入任何 AWT/desktop 依赖。
 */
internal fun Locale.isRtl(): Boolean = isMcLanguageRtl()
