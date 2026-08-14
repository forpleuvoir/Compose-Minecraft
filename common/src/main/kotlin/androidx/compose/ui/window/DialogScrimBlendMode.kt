/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.ui.window

import androidx.compose.ui.graphics.BlendMode

// 移植自 CMP ui-desktop 的 Dialog.skiko.kt(仅取该函数)。

/**
 * 弹窗遮罩(scrim)使用的混合模式。
 *
 * Minecraft 窗口不透明,[isWindowTransparent] 恒为 false,
 * 因此第一版始终返回 SrcOver;保留参数以与上游调用点对齐。
 */
internal fun getDialogScrimBlendMode(isWindowTransparent: Boolean) =
    if (isWindowTransparent) {
        // Use background alpha channel to respect transparent window shape.
        BlendMode.SrcAtop
    } else {
        BlendMode.SrcOver
    }
