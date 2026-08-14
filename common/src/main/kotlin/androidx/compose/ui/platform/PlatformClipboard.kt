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

import androidx.compose.ui.text.AnnotatedString
import net.minecraft.client.Minecraft

/**
 * Minecraft 平台剪贴板(文本输入计划 I.2):
 * 经 MC `KeyboardHandler.getClipboard()/setClipboard()` 访问系统剪贴板
 * (MC 内部封装 GLFW 剪贴板,不直接依赖 LWJGL,也不引入 AWT/Skiko/Desktop)。
 *
 * 注意:MC 剪贴板调用需在主线程;Compose 场景协程为 Dispatchers.Unconfined,
 * 复制/粘贴调用链保持在主线程。
 */
internal object MinecraftClipboard {
    fun readText(): String? =
        runCatching {
            Minecraft.getInstance().keyboardHandler.getClipboard().ifEmpty { null }
        }.getOrNull()

    fun writeText(text: String) {
        // KeyboardHandler.setClipboard 仅写入非空文本(空串由 MC 侧忽略)
        if (text.isNotEmpty()) {
            runCatching { Minecraft.getInstance().keyboardHandler.setClipboard(text) }
        }
    }
}

@Suppress("DEPRECATION")
internal fun createPlatformClipboardManager(): ClipboardManager = object : ClipboardManager {
    override fun setText(annotatedString: AnnotatedString) {
        MinecraftClipboard.writeText(annotatedString.text)
    }

    override fun getText(): AnnotatedString? =
        MinecraftClipboard.readText()?.let { AnnotatedString(it) }
}

internal fun createPlatformClipboard(): Clipboard = object : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? =
        MinecraftClipboard.readText()?.let { ClipEntry(it) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (clipEntry != null) {
            MinecraftClipboard.writeText(clipEntry.text.orEmpty())
        }
    }

    override val nativeClipboard: NativeClipboard
        get() = throw UnsupportedOperationException(
            "Minecraft 平台不提供 NativeClipboard(仅纯文本,经 MC KeyboardHandler)"
        )
}
