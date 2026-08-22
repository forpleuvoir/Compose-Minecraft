/*
 * Copyright 2021 The Android Open Source Project
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

import androidx.compose.foundation.text.input.internal.selection.TextFieldSelectionState
import androidx.compose.foundation.text.selection.SelectionManager
import androidx.compose.foundation.text.selection.TextFieldSelectionManager
import androidx.compose.runtime.Composable

@Composable
internal fun ContextMenuArea(
    manager: TextFieldSelectionManager,
    content: @Composable () -> Unit,
) {
    // 平台适配点(文本右键菜单):原为空壳占位(content() only),导致文本框右键
    // 完全无响应 —— 恢复官方语义,委托到 CommonContextMenuArea(新旧双路径分发)
    CommonContextMenuArea(manager = manager, content = content)
}

@Composable
internal fun ContextMenuArea(
    selectionState: TextFieldSelectionState,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    // 平台适配点(文本右键菜单):同上 —— 委托到 CommonContextMenuArea
    CommonContextMenuArea(selectionState = selectionState, enabled = enabled, content = content)
}

@Composable
internal fun ContextMenuArea(manager: SelectionManager, content: @Composable () -> Unit) {
    CommonContextMenuArea(manager = manager, content = content)
}
