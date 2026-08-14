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

package androidx.compose.ui.text.style

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlin.jvm.JvmInline

@Immutable
@JvmInline
value class LineBreak private constructor(
    internal val mask: Int
) {
    companion object {
        @Stable val Simple: LineBreak = LineBreak(1)

        @Stable val Heading: LineBreak = LineBreak(2)

        @Stable val Paragraph: LineBreak = LineBreak(3)

        @Stable val Unspecified: LineBreak = LineBreak(4)
    }
}

/** `true` when this [LineBreak] is not [LineBreak.Unspecified]. */
inline val LineBreak.isSpecified: Boolean
    get() = this != LineBreak.Unspecified