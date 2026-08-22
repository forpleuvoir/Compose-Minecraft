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

package androidx.compose.foundation.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import kotlin.jvm.JvmInline
import net.minecraft.locale.Language

@Immutable
@JvmInline
internal value class ContextMenuStrings(val value: Int) {
    companion object {
        val Cut: ContextMenuStrings = ContextMenuStrings(1)
        val Copy: ContextMenuStrings = ContextMenuStrings(2)
        val Paste: ContextMenuStrings = ContextMenuStrings(3)
        val SelectAll: ContextMenuStrings = ContextMenuStrings(4)
    }
}

/**
 * 平台适配点(MC 本地化):标签经 MC 语言表解析(assets/compose_minecraft/lang 下的
 * en_us / zh_cn 资源),跟随玩家游戏内语言设置;缺失键回退为键名。
 */
@Composable
@ReadOnlyComposable
internal fun getString(string: ContextMenuStrings): String = resolveString(string)

internal fun getLocalizedString(string: ContextMenuStrings): String = resolveString(string)

private fun resolveString(string: ContextMenuStrings): String {
    val key = when (string) {
        ContextMenuStrings.Cut -> "compose_minecraft.text_context_menu.cut"
        ContextMenuStrings.Copy -> "compose_minecraft.text_context_menu.copy"
        ContextMenuStrings.Paste -> "compose_minecraft.text_context_menu.paste"
        ContextMenuStrings.SelectAll -> "compose_minecraft.text_context_menu.select_all"
        else -> return "Unknown"
    }
    // MC 文本菜单仅在主线程组合,Language 主线程读取安全
    return Language.getInstance().getOrDefault(key)
}
