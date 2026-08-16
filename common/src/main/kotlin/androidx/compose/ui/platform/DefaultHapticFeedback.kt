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

package androidx.compose.ui.platform

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * MC 平台 HapticFeedback 实现:无触觉硬件,空实现(平台适配点)。
 *
 * 注意:该链路是**活的**(Clickable 长按、文本选区手柄等官方行为会调用
 * [performHapticFeedback]),但 MC 没有震动硬件,调用不会产生任何物理反馈。
 * 保留空实现以维持官方语义 —— 不要移除,移除会破坏移植源码与官方的对应性。
 * (原注释 TODO(demin) 已由平台适配点说明替代。)
 */
internal class DefaultHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        // 无触觉硬件:反馈调用按官方语义发生,但没有任何物理效果
    }
}
