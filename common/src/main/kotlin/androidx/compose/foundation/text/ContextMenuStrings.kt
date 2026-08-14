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

@Immutable
@JvmInline
internal value class ContextMenuStrings(val value: Int) {
    companion object {
        val Cut: ContextMenuStrings = ContextMenuStrings(1)
        val Copy: ContextMenuStrings = ContextMenuStrings(2)
        val Paste: ContextMenuStrings = ContextMenuStrings(3)
        val SelectAll: ContextMenuStrings = ContextMenuStrings(4)
        val Autofill: ContextMenuStrings = ContextMenuStrings(5)
    }
}

@Composable
@ReadOnlyComposable
internal fun getString(string: ContextMenuStrings): String = when (string) {
    ContextMenuStrings.Cut -> "Cut"
    ContextMenuStrings.Copy -> "Copy"
    ContextMenuStrings.Paste -> "Paste"
    ContextMenuStrings.SelectAll -> "Select All"
    ContextMenuStrings.Autofill -> "Autofill"
    else -> "Unknown"
}

/** Minecraft 平台第一版:固定英文文案,不加载多语言翻译表 */
internal fun getLocalizedString(string: ContextMenuStrings): String = when (string) {
    ContextMenuStrings.Cut -> "Cut"
    ContextMenuStrings.Copy -> "Copy"
    ContextMenuStrings.Paste -> "Paste"
    ContextMenuStrings.SelectAll -> "Select All"
    ContextMenuStrings.Autofill -> "Autofill"
    else -> "Unknown"
}
