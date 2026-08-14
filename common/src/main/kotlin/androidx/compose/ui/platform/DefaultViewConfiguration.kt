/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.compose.ui.platform

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Minecraft 平台默认 [ViewConfiguration](第一版取值与桌面平台一致) */
internal val MinecraftDefaultViewConfiguration = object : ViewConfiguration {
    override val longPressTimeoutMillis: Long = 500L
    override val doubleTapTimeoutMillis: Long = 300L
    override val doubleTapMinTimeMillis: Long = 40L
    override val touchSlop: Float = 18f
    override val handwritingSlop: Float = 18f
    override val minimumTouchTargetSize: DpSize = DpSize(48.dp, 48.dp)
    override val maximumFlingVelocity: Float = 8000f
    override val minimumFlingVelocity: Float = 50f
    override val handwritingGestureLineMargin: Float = 18f
}

@Deprecated(
    message = "Use LocalViewConfiguration.current instead",
    replaceWith = ReplaceWith("LocalViewConfiguration.current"),
    level = DeprecationLevel.ERROR
)
class DefaultViewConfiguration(private val density: Density) : ViewConfiguration {
    private val emptyViewConfiguration = MinecraftDefaultViewConfiguration

    override val longPressTimeoutMillis: Long
        get() = emptyViewConfiguration.longPressTimeoutMillis

    override val doubleTapTimeoutMillis: Long
        get() = emptyViewConfiguration.doubleTapTimeoutMillis

    override val doubleTapMinTimeMillis: Long
        get() = emptyViewConfiguration.doubleTapMinTimeMillis

    override val touchSlop: Float
        get() = with(density) { emptyViewConfiguration.touchSlop.dp.toPx() }
}
