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
 * 标准指针图标种类(平台适配点,I9 指针图标)。
 *
 * 四种标准图标([PointerIcon.Default]/[PointerIcon.Crosshair]/[PointerIcon.Text]/
 * [PointerIcon.Hand])以此区分;平台接入点
 * [moe.forpleuvoir.compose_minecraft.platform.screen.MinecraftComposeScene] 的
 * `PlatformContext.setPointerIcon` 据此映射到 MC 原版光标
 * (`com.mojang.blaze3d.platform.cursor.CursorTypes`)。
 */
internal enum class MinecraftPointerIconKind {
    Default,
    Crosshair,
    Text,
    Hand,
    ResizeNS,
    ResizeEW,
    ResizeNWSE,
    ResizeNESW,
    ResizeAll,
    NotAllowed
}

/**
 * Minecraft 平台指针图标实现(替代原 AWT Cursor 实现)。
 *
 * 以 [MinecraftPointerIconKind] 携带语义种类(原占位实现所有图标 equals 恒等,
 * 平台无法区分),平台接入点据此触发对应原版光标切换。
 */
internal class MinecraftPointerIcon(
    val kind: MinecraftPointerIconKind,
) : PointerIcon {
    override fun equals(other: Any?): Boolean =
        other is MinecraftPointerIcon && other.kind == kind

    override fun hashCode(): Int = kind.hashCode()

    override fun toString(): String = "MinecraftPointerIcon($kind)"
}
