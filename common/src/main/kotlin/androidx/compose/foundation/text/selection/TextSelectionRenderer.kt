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

package androidx.compose.foundation.text.selection

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange

/**
 * 平台适配点:文本选择区域的渲染器 —— 允许外部覆盖 BasicTextField 默认的选区高亮绘制。
 *
 * 默认实现见 [LocalTextSelectionRenderer] 的注释;外部可通过
 * `CompositionLocalProvider(LocalTextSelectionRenderer provides renderer) { ... }`
 * 提供自定义绘制(例如:不同的高亮形状、动画、或平台特有效果)。
 *
 * 平台定位(Basic 层级,不做风格化):本接口只是"渲染能力"的扩展点,
 * 默认行为与官方一致(整体选区 Path 一次绘制)。
 *
 * @see LocalTextSelectionRenderer
 */
fun interface TextSelectionRenderer {
    /**
     * 绘制 [selection] 覆盖的文本选区。
     *
     * @param scope 绘制作用域([DrawScope],用于 drawRect/drawPath 等)。
     * @param selection 选中的文本范围([TextRange.min] 到 [TextRange.max])。
     * @param textLayoutResult 布局结果,用于把选区映射到画布坐标
     *   (如 [TextLayoutResult.getPathForRange] / [TextLayoutResult.getCursorRect])。
     */
    fun drawSelection(scope: DrawScope, selection: TextRange, textLayoutResult: TextLayoutResult)
}

/**
 * 平台适配点:控制文本选择区域渲染的 [androidx.compose.runtime.CompositionLocal]。
 *
 * 默认值为 `null` —— 使用 BasicTextField 内置的默认选区渲染
 * (与官方一致:`getPathForRange` 生成整体选区 Path 一次绘制)。
 *
 * 外部覆盖示例(替换接口实例即可):
 * ```
 * CompositionLocalProvider(
 *     LocalTextSelectionRenderer provides TextSelectionRenderer { scope, selection, layoutResult ->
 *         // 自定义选区绘制(如自定义高亮颜色/形状)
 *     }
 * ) {
 *     BasicTextField(...)
 * }
 * ```
 */
val LocalTextSelectionRenderer = staticCompositionLocalOf<TextSelectionRenderer?> { null }
