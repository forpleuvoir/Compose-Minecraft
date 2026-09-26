/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.compose.ui.text

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.internal.requirePrecondition
import androidx.compose.ui.text.platform.StyleSegment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilteredMap
import androidx.compose.ui.util.fastMaxBy
import net.minecraft.network.chat.Style

/**
 * Calculates and provides the intrinsic width and height of text that contains [ParagraphStyle].
 *
 * @param annotatedString the text to be laid out
 * @param style the [Style] to be applied to the whole text
 * @param placeholders a list of [Placeholder]s that specify ranges of text which will be skipped
 *   during layout and replaced with [Placeholder]. It's required that the range of each
 *   [Placeholder] doesn't cross paragraph boundary, otherwise [IllegalArgumentException] is thrown.
 * @param density density of the device
 * @param fontFamilyResolver [Font.ResourceLoader] to be used to load the font given in [SpanStyle]s
 * @throws IllegalArgumentException if [ParagraphStyle.textDirection] is not set, or any of the
 *   [placeholders] crosses paragraph boundary.
 * @see MultiParagraph
 * @see Placeholder
 */
class MultiParagraphIntrinsics(
    val annotatedString: AnnotatedString,
    style: Style,
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    // 平台适配点:字号由渲染缩放(scale)驱动,16sp = 1f;
    // scale 必须在 intrinsics 构造时编码进 MinecraftParagraphIntrinsics(cast 透传)
    scale: Float = 1f,
    // 平台适配点(富文本):spanStyles 切分后的段列表(全覆盖,渲染端逐段绘制)
    segments: List<StyleSegment> = emptyList(),
    // 平台适配点:段落水平对齐(段落级;MC Style 无此属性,独立字段下沉)
    textAlign: TextAlign = TextAlign.Unspecified,
) : ParagraphIntrinsics {

    @Suppress("DEPRECATION")
    @Deprecated(
        "Font.ResourceLoader is deprecated, call with fontFamilyResolver",
        replaceWith =
            ReplaceWith(
                "MultiParagraphIntrinsics(annotatedString, style, " +
                    "placeholders, density, fontFamilyResolver)"
            ),
    )
    constructor(
        annotatedString: AnnotatedString,
        style: Style,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
        density: Density,
        resourceLoader: Font.ResourceLoader,
    ) : this(
        annotatedString,
        style,
        placeholders,
        density,
        createFontFamilyResolver(resourceLoader),
    )

    // NOTE(text-perf-review): why are we using lazy here? Are there cases where these
    // calculations aren't executed?
    override val minIntrinsicWidth: Float by
        lazy(LazyThreadSafetyMode.NONE) {
            infoList.fastMaxBy { it.intrinsics.minIntrinsicWidth }?.intrinsics?.minIntrinsicWidth
                ?: 0f
        }

    override val maxIntrinsicWidth: Float by
        lazy(LazyThreadSafetyMode.NONE) {
            infoList.fastMaxBy { it.intrinsics.maxIntrinsicWidth }?.intrinsics?.maxIntrinsicWidth
                ?: 0f
        }

    /**
     * [ParagraphIntrinsics] for each paragraph included in the [buildAnnotatedString]. For empty
     * string there will be a single empty paragraph intrinsics info.
     */
    internal val infoList: List<ParagraphIntrinsicInfo>

    init {
        // 平台适配点:段级 ParagraphStyle 注解(AnnotatedString.paragraphStyles)第一版不参与布局,
        // 统一用默认 ParagraphStyle 兜底;全局段落对齐不走这里,而是经 [textAlign] 字段
        // 直接下沉到每个 ParagraphIntrinsics(MC Style 无段落属性,无法承载)。
        val paragraphStyle = ParagraphStyle()
        infoList =
            annotatedString.mapEachParagraphStyle(paragraphStyle) {
                annotatedString,
                paragraphStyleItem ->
                val currentParagraphStyle =
                    resolveTextDirection(paragraphStyleItem.item, paragraphStyle)

                ParagraphIntrinsicInfo(
                    intrinsics =
                        ParagraphIntrinsics(
                            text = annotatedString.text,
                            style = style,
                            annotations = annotatedString.annotations ?: emptyList(),
                            placeholders =
                                placeholders.getLocalPlaceholders(
                                    paragraphStyleItem.start,
                                    paragraphStyleItem.end,
                                ),
                            density = density,
                            fontFamilyResolver = fontFamilyResolver,
                            scale = scale,
                            // 平台适配点(富文本):段列表透传到 Paragraph → 渲染端
                            segments = segments,
                            textAlign = textAlign,
                        ),
                    startIndex = paragraphStyleItem.start,
                    endIndex = paragraphStyleItem.end,
                )
            }
    }

    override val hasStaleResolvedFonts: Boolean
        get() = infoList.fastAny { it.intrinsics.hasStaleResolvedFonts }

    /**
     * if the [style] does `not` have [TextDirection] set, it will return a new [ParagraphStyle]
     * where [TextDirection] is set using the [defaultStyle]. Otherwise returns the same [style]
     * object.
     *
     * @param style ParagraphStyle to be checked for [TextDirection]
     * @param defaultStyle [ParagraphStyle] passed to [MultiParagraphIntrinsics] as the main style
     */
    private fun resolveTextDirection(
        style: ParagraphStyle,
        defaultStyle: ParagraphStyle,
    ): ParagraphStyle {
        return if (style.textDirection != TextDirection.Unspecified) style
        else style.copy(textDirection = defaultStyle.textDirection)
    }
}

private fun List<AnnotatedString.Range<Placeholder>>.getLocalPlaceholders(start: Int, end: Int) =
    fastFilteredMap({ intersect(start, end, it.start, it.end) }) {
        requirePrecondition(start <= it.start && it.end <= end) {
            "placeholder can not overlap with paragraph."
        }
        AnnotatedString.Range(it.item, it.start - start, it.end - start)
    }

internal data class ParagraphIntrinsicInfo(
    val intrinsics: ParagraphIntrinsics,
    val startIndex: Int,
    val endIndex: Int,
)
