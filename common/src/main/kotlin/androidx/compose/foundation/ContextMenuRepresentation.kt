/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.compose.foundation

import androidx.compose.foundation.contextmenu.ContextMenuPopup
import androidx.compose.foundation.contextmenu.ContextMenuPopupPositionProvider
import androidx.compose.foundation.contextmenu.ContextMenuScope
import androidx.compose.foundation.contextmenu.ContextMenuState
import androidx.compose.foundation.contextmenu.ContextMenuState.Status
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.round

/**
 * 平台适配点(文本右键菜单):菜单的呈现层抽象 —— 决定右键菜单"长什么样、怎么弹出",
 * 与条目内容(由字段经 [androidx.compose.foundation.contextmenu.appendTextContextMenuComponents]
 * 系列构建)解耦。
 *
 * 默认实现 [DefaultContextMenuRepresentation] 在指针位置弹出 MC 风格下拉菜单;
 * 业务可经 `CompositionLocalProvider(LocalContextMenuRepresentation provides …)` 全局或
 * 子树覆盖为自己的实现(如带图标、不同定位策略的版本)。
 */
@Stable
interface ContextMenuRepresentation {

    /**
     * 渲染当前打开的上下文菜单。
     *
     * @param state 菜单状态([Status.Open] 携带弹出锚点偏移;[Status.Closed] 时不渲染)
     * @param items 菜单条目构建块(经 [ContextMenuScope.item] / separator 声明)
     */
    @Composable
    fun Representation(state: ContextMenuState, items: ContextMenuScope.() -> Unit)
}

/**
 * 当前场景的上下文菜单呈现层;默认 [DefaultContextMenuRepresentation]。
 *
 * 未显式 Provider 时使用默认实现 —— 即"默认空实现可被具体风格实现自动覆盖"的机制本体:
 * 风格实现只需在更上层 Provide 本 Local,所有文本字段的右键菜单立即切换。
 */
val LocalContextMenuRepresentation: ProvidableCompositionLocal<ContextMenuRepresentation> =
    staticCompositionLocalOf { DefaultContextMenuRepresentation }

/** 默认呈现层:在右键位置弹出 MC 风格下拉面板(条目按声明序渲染,外点/Escape 关闭)。 */
internal object DefaultContextMenuRepresentation : ContextMenuRepresentation {

    @Composable
    override fun Representation(state: ContextMenuState, items: ContextMenuScope.() -> Unit) {
        val status = state.status
        if (status !is Status.Open) return

        val popupPositionProvider =
            remember(status) { ContextMenuPopupPositionProvider(status.offset.round()) }

        ContextMenuPopup(
            popupPositionProvider = popupPositionProvider,
            onDismiss = { state.status = Status.Closed },
            contextMenuBuilderBlock = items,
        )
    }
}
