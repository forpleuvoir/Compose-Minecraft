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
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.platform.StyleSegment
import net.minecraft.network.chat.Style

/**
 * Modifier element for String based text
 *
 * This is faster than [TextAnnotatedStringElement]
 *
 * 平台适配点:TextStyle → Style。
 */
internal class TextStringSimpleElement(
    private val text: String,
    private val style: Style,
    private val fontFamilyResolver: FontFamily.Resolver,
    private val overflow: TextOverflow = TextOverflow.Clip,
    private val softWrap: Boolean = true,
    private val maxLines: Int = Int.MAX_VALUE,
    private val minLines: Int = DefaultMinLines,
    private val color: ColorProducer? = null,
    /** 平台适配点(T.3):MC Component 展平后的多段样式;空 = 单样式(旧行为)。 */
    private val segments: List<StyleSegment> = emptyList(),
    /** 平台适配点(T.10):文本缩放;1f = 原样。 */
    private val scale: Float = 1f,
    /** 平台适配点(T.28):文本透明度(TextStyle.alpha,默认 1f),绘制时合成进颜色。 */
    private val alpha: Float = 1f,
) : ModifierNodeElement<TextStringSimpleNode>() {

    override fun create(): TextStringSimpleNode =
        TextStringSimpleNode(
            text,
            style,
            fontFamilyResolver,
            overflow,
            softWrap,
            maxLines,
            minLines,
            color,
            segments,
            scale,
            alpha,
        )

    override fun update(node: TextStringSimpleNode) {
        node.doInvalidations(
            drawChanged = node.updateDraw(color, style, alpha),
            textChanged = node.updateText(text = text),
            layoutChanged =
                node.updateLayoutRelatedArgs(
                    style = style,
                    minLines = minLines,
                    maxLines = maxLines,
                    softWrap = softWrap,
                    fontFamilyResolver = fontFamilyResolver,
                    overflow = overflow,
                    segments = segments,
                    scale = scale,
                ),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        if (other !is TextStringSimpleElement) return false

        // these three are most likely to actually change
        if (color != other.color) return false
        if (text != other.text) return false /* expensive to check, do after color */
        if (style != other.style) return false
        if (alpha != other.alpha) return false
        if (segments != other.segments) return false
        if (scale != other.scale) return false

        // these are equally unlikely to change
        if (fontFamilyResolver != other.fontFamilyResolver) return false
        if (overflow != other.overflow) return false
        if (softWrap != other.softWrap) return false
        if (maxLines != other.maxLines) return false
        if (minLines != other.minLines) return false

        return true
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + fontFamilyResolver.hashCode()
        result = 31 * result + overflow.hashCode()
        result = 31 * result + softWrap.hashCode()
        result = 31 * result + maxLines
        result = 31 * result + minLines
        result = 31 * result + (color?.hashCode() ?: 0)
        result = 31 * result + alpha.hashCode()
        result = 31 * result + segments.hashCode()
        result = 31 * result + scale.hashCode()
        return result
    }

    override fun InspectorInfo.inspectableProperties() {
        // Show nothing in the inspector.
    }
}
