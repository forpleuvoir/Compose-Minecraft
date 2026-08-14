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
package androidx.compose.foundation.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import moe.forpleuvoir.compose_minecraft.minecraft.McTextStyle
import net.minecraft.client.Minecraft

// ─────────────────────────────────────────────────────────────────────────────
// 平台适配点:TextField 最小尺寸计算不再依赖 fontSize/字体解析(TextStyle 已移除)。
// MC 文本行高固定 9px(MC Font.lineHeight),字符宽取 MC 默认字体的参考字形宽度;
// 最小尺寸 = (maxLines 行高 + 参考字符宽),与官方 textFieldMinSize 的语义一致,
// 仅以 MC 固定度量替代 fontSize 计算。
// ─────────────────────────────────────────────────────────────────────────────

private val MinWidthCharCount = 10 // 官方 min width 为 10 字符宽

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.textFieldMinSize(style: McTextStyle) = composed {
    // 平台适配点:MC 度量恒定,无需响应字体解析状态/density 变化
    val font = Minecraft.getInstance().font
    val lineHeightPx = font.lineHeight.toFloat()
    val charWidthPx = font.width(EmptyTextReplacement).toFloat() / MinWidthCharCount

    Modifier.layout { measurable, constraints ->
        val minSize = IntSize(charWidthPx.roundToInt(), lineHeightPx.roundToInt())
        val childConstraints =
            constraints.copy(
                minWidth = minSize.width.coerceIn(constraints.minWidth, constraints.maxWidth),
                minHeight = minSize.height.coerceIn(constraints.minHeight, constraints.maxHeight),
            )
        val measured = measurable.measure(childConstraints)
        layout(measured.width, measured.height) { measured.placeRelative(0, 0) }
    }
}
