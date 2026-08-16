/*
 * Compose Minecraft Platform Mod — MC 语言 → java.util.Locale 适配工具
 * Licensed under the Apache License, Version 2.0 (see LICENSE / NOTICE).
 */

package moe.forpleuvoir.compose_minecraft.platform.text

import net.minecraft.client.Minecraft
import net.minecraft.locale.Language

/**
 * MC 游戏语言代码 → [java.util.Locale]。
 *
 * MC 语言设置位于 `Minecraft.getInstance().options.languageCode`(如 "zh_cn"、"en_us"),
 * 玩家在游戏内更改语言后此处返回随之变化。Compose 的 Locale 链路
 * ([androidx.compose.ui.text.intl.LocaleList.current]) 以此为准,而不是 JVM 系统默认。
 */
fun mcLanguageCodeToLocale(): java.util.Locale {
    val code = Minecraft.getInstance().options.languageCode
    val parts = code.split('_', limit = 2)
    val builder = java.util.Locale.Builder().setLanguage(parts[0])
    parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { builder.setRegion(it.uppercase()) }
    return builder.build()
}

/**
 * MC RTL(从右到左)判定:以 MC 语言包元数据为准。
 *
 * 权威来源:`Language.getInstance().isDefaultRightToLeft()`(由语言包加载时的
 * defaultRightToLeft 决定,ClientLanguage 构造参数),不自行硬编码语言列表。
 */
fun isMcLanguageRtl(): Boolean = Language.getInstance().isDefaultRightToLeft()
