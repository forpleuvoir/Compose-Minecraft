/*
 * Copyright 2026 forpleuvoir
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

package androidx.compose.ui.text.font

import androidx.compose.ui.text.ExperimentalTextApi
import kotlin.coroutines.CoroutineContext

/**
 * Minecraft 平台字体加载器(第一版):不加载任何字体文件,所有字体请求返回 null。
 * 后续阶段接入 Minecraft 字体资源。
 */
internal class MinecraftFontLoader : PlatformFontLoader {
    override fun loadBlocking(font: Font): Any? {
        if (font.loadingStrategy != FontLoadingStrategy.Companion.OptionalLocal) {
            throw IllegalArgumentException("Unsupported font type: $font")
        }
        return null
    }

    override suspend fun awaitLoad(font: Font): Any? = loadBlocking(font)

    override val cacheKey: Any = "minecraft-font-loader"
}

/**
 * Create a new fontFamilyResolver for use outside of composition context
 *
 * Example usages:
 * - Before starting compose to preload fonts
 * - Creating Paragraph objects on background thread
 *
 * Usages inside of Composition should use LocalFontFamilyResolver.current
 */
fun createFontFamilyResolver(): FontFamily.Resolver {
    return FontFamilyResolverImpl(
        MinecraftFontLoader(),
        createPlatformResolveInterceptor(),
    )
}

/**
 * Create a new fontFamilyResolver for use outside of composition context with a coroutine context.
 *
 * Example usages:
 * - Before starting compose to preload fonts
 * - Creating Paragraph objects on background thread
 * - Configuring LocalFontFamilyResolver with a different CoroutineScope
 *
 * Usages inside of Composition should use LocalFontFamilyResolver.current
 */
@ExperimentalTextApi
fun createFontFamilyResolver(
    coroutineContext: CoroutineContext,
): FontFamily.Resolver {
    return FontFamilyResolverImpl(
        MinecraftFontLoader(),
        createPlatformResolveInterceptor(),
        GlobalTypefaceRequestCache,
        FontListFontFamilyTypefaceAdapter(
            GlobalAsyncTypefaceCache,
            coroutineContext,
        ),
    )
}

internal fun createPlatformResolveInterceptor(): PlatformResolveInterceptor =
    PlatformResolveInterceptor.Default
