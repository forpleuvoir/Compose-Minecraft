/*
 * Copyright 2023 The Android Open Source Project
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

import kotlin.math.absoluteValue
import kotlin.math.sign

/**
 * Returns true when [high] is a Unicode high-surrogate code unit and [low] is a Unicode
 * low-surrogate code unit.
 */
private fun isSurrogatePair(high: Char, low: Char): Boolean =
    high.isHighSurrogate() && low.isLowSurrogate()

/**
 * Returns the index, in characters, of the code point at distance [offset] from [index].
 *
 * If there aren't enough codepoints in the correct direction, returns 0 (if [offset] is negative)
 * or the length of the char sequence (if [offset] is positive).
 */
internal fun CharSequence.offsetByCodePoints(index: Int, offset: Int): Int {
    val sign = offset.sign
    val distance = offset.absoluteValue

    var currentOffset = index
    for (i in 0 until distance) {
        currentOffset += sign
        if (currentOffset <= 0) return 0
        else if (currentOffset >= length) return length

        val lead = this[currentOffset - 1]
        val trail = this[currentOffset]

        if (isSurrogatePair(lead, trail)) {
            currentOffset += sign
        }
    }

    return currentOffset
}
