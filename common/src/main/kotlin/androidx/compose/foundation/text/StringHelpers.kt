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

package androidx.compose.foundation.text

import androidx.compose.ui.text.TextRange

/** StringBuilder.appendCodePoint is already defined on JVM so it's called appendCodePointX. */
internal fun StringBuilder.appendCodePointX(codePoint: Int): StringBuilder = appendCodePoint(codePoint)

/**
 * Returns the index of the character break preceding [index].
 *
 * 平台适配点(修复):恢复**字符级**断点 —— 原实现误写成"找空白/换行断点"
 * (跳词/跳行语义),导致方向键一次跳一个词、Delete 删到行首。官方 desktop 用
 * ICU BreakIterator(字符级);此处用 JVM Character 码点边界(正确处理 emoji/surrogate)。
 */
internal fun String.findPrecedingBreak(index: Int): Int {
    if (index <= 0) return -1
    return Character.codePointBefore(this, index).let { index - Character.charCount(it) }
}

/**
 * Returns the index of the character break following [index]. Returns -1 if there are no more
 * breaks before the end of the string.
 *
 * 平台适配点(修复):恢复**字符级**断点(同 [findPrecedingBreak])。
 */
internal fun String.findFollowingBreak(index: Int): Int {
    if (index >= length) return -1
    val codePoint = Character.codePointAt(this, index)
    val next = index + Character.charCount(codePoint)
    return if (next <= length) next else -1
}

/**
 * @return If the index is within an emoji, returns the index of the start of the emoji. If the
 *   index is not an emoji, returns the code point before the given [index], or [ifNotFound] if
 *   there is no code point before [index].
 */
internal fun String.findCodePointOrEmojiStartBefore(index: Int, ifNotFound: Int): Int =
    if (index <= 0) ifNotFound else Character.codePointBefore(this, index).let { index - Character.charCount(it) }

internal fun CharSequence.findParagraphStart(startIndex: Int): Int {
    for (index in startIndex downTo 1) {
        if (this[index - 1] == '\n') {
            return index
        }
    }
    return 0
}

internal fun CharSequence.findParagraphEnd(startIndex: Int): Int {
    for (index in startIndex until this.length) {
        if (this[index] == '\n') {
            return index
        }
    }
    return this.length
}

/**
 * Returns the text range of the paragraph at the given character offset.
 *
 * Paragraphs are separated by Line Feed character (\n).
 */
internal fun CharSequence.getParagraphBoundary(index: Int): TextRange {
    return TextRange(findParagraphStart(index), findParagraphEnd(index))
}
