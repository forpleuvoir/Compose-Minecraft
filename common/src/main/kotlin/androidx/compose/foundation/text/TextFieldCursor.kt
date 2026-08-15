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

import androidx.compose.foundation.text.input.internal.CursorAnimationState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.round

internal fun Modifier.cursor(
    state: LegacyTextFieldState,
    value: TextFieldValue,
    offsetMapping: OffsetMapping,
    cursorBrush: Brush,
    enabled: Boolean,
) =
    if (enabled)
        composed {
            val animateCursor = LocalCursorBlinkEnabled.current
            val cursorAnimation = remember(animateCursor) { CursorAnimationState(animateCursor) }
            // Don't bother animating the cursor if it wouldn't draw any pixels.
            val isBrushSpecified = !(cursorBrush is SolidColor && cursorBrush.value.isUnspecified)
            // Only animate the cursor when its window is actually focused. This also disables the
            // cursor
            // animation when the screen is off.
            // TODO confirm screen-off behavior.
            val isWindowFocused = LocalWindowInfo.current.isWindowFocused
            if (
                isWindowFocused && state.hasFocus && value.selection.collapsed && isBrushSpecified
            ) {
                LaunchedEffect(value.annotatedString, value.selection) {
                    cursorAnimation.snapToVisibleAndAnimate()
                }
                drawWithContent {
                    this.drawContent()
                    val cursorAlphaValue = cursorAnimation.cursorAlpha
                    if (cursorAlphaValue != 0f) {
                        val transformedOffset =
                            offsetMapping.originalToTransformed(value.selection.start)
                        val cursorRect =
                            state.layoutResult?.value?.getCursorRect(transformedOffset)
                                ?: Rect(0f, 0f, 0f, 0f)
                        // 平台适配点(T.7):MC EditBox 风格竖条光标 —— 1px 宽,
                        // y = 行顶 - 1 到 行底 + 1(TextCursorUtils.extractInsertCursor 同源);
                        // 颜色 = cursorBrush 纯色,未指定时退回布局样式色(同 EditBox 光标取文本色)
                        val cursorColor =
                            (cursorBrush as? SolidColor)
                                ?.value
                                ?.takeUnless { it.isUnspecified }
                                ?: state.layoutResult?.value?.layoutInput?.style?.color?.toColor()
                                ?: Color.Black
                        drawRect(
                            color = cursorColor,
                            topLeft = Offset(cursorRect.left - 1f, cursorRect.top - 1f),
                            size = Size(1f, (cursorRect.bottom - cursorRect.top) + 2f),
                            alpha = cursorAlphaValue,
                        )
                    }
                }
            } else {
                Modifier
            }
        }
    else this

internal val DefaultCursorThickness: Dp = 2.dp
