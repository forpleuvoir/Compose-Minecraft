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

/**
 * Minecraft 平台第一版剪贴板:仅进程内内存实现,不访问系统剪贴板。
 * 后续阶段接入 Minecraft 屏幕/系统剪贴板。
 */
internal object MinecraftClipboard {
    var text: AnnotatedString? = null
}

@Suppress("DEPRECATION")
internal fun createPlatformClipboardManager(): ClipboardManager = object : ClipboardManager {
    override fun setText(annotatedString: AnnotatedString) {
        MinecraftClipboard.text = annotatedString
    }

    override fun getText(): AnnotatedString? = MinecraftClipboard.text
}

internal fun createPlatformClipboard(): Clipboard = object : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? =
        MinecraftClipboard.text?.let { ClipEntry(it.text) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        MinecraftClipboard.text = clipEntry?.text?.let { AnnotatedString(it) }
    }

    override val nativeClipboard: NativeClipboard
        get() = throw UnsupportedOperationException(
            "Minecraft 平台第一版不提供原生剪贴板"
        )
}
