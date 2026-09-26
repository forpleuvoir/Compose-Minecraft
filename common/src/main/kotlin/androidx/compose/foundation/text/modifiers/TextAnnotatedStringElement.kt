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

package androidx.compose.foundation.text.modifiers

import androidx.compose.foundation.text.DefaultMinLines
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import moe.forpleuvoir.compose_minecraft.platform.ui.text.PlatformTextPayload

/**
 * Modifier element for any Text with [AnnotatedString] or [onTextLayout] parameters
 *
 * This is slower than [TextStringSimpleElement]
 *
 * 平台适配点:平台文本输入收敛为 [PlatformTextPayload](单一对象,见其 KDoc)。
 * 此前本 element 缺少 `alpha` / `brush` 形参、`backend` 虽有形参但无人转发,
 * 导致「传了 `onTextLayout` 或放进 `SelectionContainer` 就丢了透明度/渐变/
 * 后端定向」;现在与 [TextStringSimpleElement] 消费同一个载荷,字段集完全一致。
 */
internal class TextAnnotatedStringElement(
    private val text: AnnotatedString,
    private val payload: PlatformTextPayload,
    private val fontFamilyResolver: FontFamily.Resolver,
    private val onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    private val overflow: TextOverflow = TextOverflow.Clip,
    private val softWrap: Boolean = true,
    private val maxLines: Int = Int.MAX_VALUE,
    private val minLines: Int = DefaultMinLines,
    private val placeholders: List<AnnotatedString.Range<Placeholder>>? = null,
    private val onPlaceholderLayout: ((List<Rect?>) -> Unit)? = null,
    private val selectionController: SelectionController? = null,
    private val color: ColorProducer? = null,
    private val autoSize: TextAutoSize? = null,
    private val onShowTranslation: ((TextAnnotatedStringNode.TextSubstitutionValue) -> Unit)? = null,
) : ModifierNodeElement<TextAnnotatedStringNode>() {

    override fun create(): TextAnnotatedStringNode =
        TextAnnotatedStringNode(
            text = text,
            style = payload.mcStyle,
            fontFamilyResolver = fontFamilyResolver,
            onTextLayout = onTextLayout,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            placeholders = placeholders,
            onPlaceholderLayout = onPlaceholderLayout,
            selectionController = selectionController,
            overrideColor = color,
            autoSize = autoSize,
            onShowTranslation = onShowTranslation,
            segments = payload.segments,
            scale = payload.scale,
            textAlign = payload.textAlign,
            textAlpha = payload.alpha,
            textBrush = payload.brush,
            textBackend = payload.backend,
        )

    override fun update(node: TextAnnotatedStringNode) {
        node.doInvalidations(
            drawChanged =
                node.updateDraw(
                    color = color,
                    style = payload.mcStyle,
                    alpha = payload.alpha,
                    brush = payload.brush,
                    backend = payload.backend,
                ),
            textChanged = node.updateText(text = text),
            layoutChanged =
                node.updateLayoutRelatedArgs(
                    style = payload.mcStyle,
                    placeholders = placeholders,
                    minLines = minLines,
                    maxLines = maxLines,
                    softWrap = softWrap,
                    fontFamilyResolver = fontFamilyResolver,
                    overflow = overflow,
                    autoSize = autoSize,
                    segments = payload.segments,
                    scale = payload.scale,
                    textAlign = payload.textAlign,
                ),
            callbacksChanged =
                node.updateCallbacks(
                    onTextLayout = onTextLayout,
                    onPlaceholderLayout = onPlaceholderLayout,
                    selectionController = selectionController,
                    onShowTranslation = onShowTranslation,
                ),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        if (other !is TextAnnotatedStringElement) return false

        // these three are most likely to actually change
        if (color != other.color) return false
        if (text != other.text) return false /* expensive to check, do it after color */
        if (payload != other.payload) return false
        if (placeholders != other.placeholders) return false

        // these are equally unlikely to change
        if (fontFamilyResolver != other.fontFamilyResolver) return false
        if (onTextLayout !== other.onTextLayout) return false
        if (onShowTranslation !== other.onShowTranslation) return false
        if (overflow != other.overflow) return false
        if (softWrap != other.softWrap) return false
        if (maxLines != other.maxLines) return false
        if (minLines != other.minLines) return false

        // these never change, but check anyway for correctness
        if (onPlaceholderLayout !== other.onPlaceholderLayout) return false
        if (selectionController !== other.selectionController) return false

        return true
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + payload.hashCode()
        result = 31 * result + fontFamilyResolver.hashCode()
        result = 31 * result + (onTextLayout?.hashCode() ?: 0)
        result = 31 * result + overflow.hashCode()
        result = 31 * result + softWrap.hashCode()
        result = 31 * result + maxLines
        result = 31 * result + minLines
        result = 31 * result + (placeholders?.hashCode() ?: 0)
        result = 31 * result + (onPlaceholderLayout?.hashCode() ?: 0)
        result = 31 * result + (selectionController?.hashCode() ?: 0)
        result = 31 * result + (color?.hashCode() ?: 0)
        result = 31 * result + (onShowTranslation?.hashCode() ?: 0)
        return result
    }

    override fun InspectorInfo.inspectableProperties() {
        // Show nothing in the inspector.
    }
}
