/*
 * Copyright 2021 The Android Open Source Project
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

/**
 * Minecraft 平台指针图标实现(替代原 AWT Cursor 实现)。
 *
 * 平台当前不提供光标切换能力(MC 未暴露原版光标 API),所有图标共用同一
 * 占位实现:语义仅用于内部状态区分,不触发任何系统光标变化。
 */
internal class MinecraftPointerIcon : PointerIcon {
    override fun equals(other: Any?): Boolean = other is MinecraftPointerIcon

    override fun hashCode(): Int = 0

    override fun toString(): String = "MinecraftPointerIcon"
}
