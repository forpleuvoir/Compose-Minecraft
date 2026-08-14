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

package androidx.compose.foundation.text

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * Key combiner which buffers dead keys and combines them with subsequent keys as necessary.
 *
 * 平台适配点(Minecraft 平台):字符输入经 GLFW char 回调 → MC `charTyped` → 合成 typed
 * KeyEvent(codePoint),**死键组合已由操作系统在 char 回调阶段完成**,平台侧无需再做
 * 死键表组合,直接返回事件携带的 codePoint 即可(Skia 原版在此查死键映射表)。
 *
 * It is NOT thread safe.
 */
internal class DeadKeyCombiner() {

    /**
     * @param event the key event received by the combiner
     * @return a unicode code point to emit in response to the event, or null if no code point
     *   should be emitted
     */
    fun consume(event: KeyEvent): Int? = event.utf16CodePoint.takeIf { it != 0 }
}
