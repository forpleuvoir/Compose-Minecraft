/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.compose.ui.input.pointer

import androidx.compose.ui.geometry.Offset


/**
 * Data that describes a particular pointer
 *
 * @param positionOnScreen The position of the event relative to the device screen.
 * @param position The position of the event relative to the owner.
 */
internal data class PointerInputEventData(
    val id: PointerId,
    val uptime: Long,
    val positionOnScreen: Offset,
    val position: Offset,
    val down: Boolean,
    val pressure: Float,
    val type: PointerType,
    val activeHover: Boolean = false,
    val historical: List<HistoricalChange> = mutableListOf(),
    val scrollDelta: Offset = Offset.Zero,
    val scaleGestureFactor: Float,
    val panGestureOffset: Offset,
    val originalEventPosition: Offset = Offset.Zero,
)

