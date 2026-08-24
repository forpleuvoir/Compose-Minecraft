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
 * 平台默认 [Typeface] 哨兵实例(font-system 重构,T.RF-A)。
 *
 * Compose 层的 FontFamily 解析在 MC 上没有真实字形承载 —— 实际渲染由自有
 * 三通道体系(moe.forpleuvoir.compose_minecraft.platform.render.text.FontResolver)
 * 完成;但 resolver 的公开契约要求返回非空 [Typeface],本哨兵即所有具名族
 * 的统一终解。它同时是解析递归的**终结点**:[PlatformFontFamilyTypefaceAdapter]
 * 直返本实例,绝不经 createDefaultTypeface 回到 resolve 链(否则对
 * fontFamily=null 自指递归 → StackOverflowError;官方桌面由 Skia
 * loadPlatformTypes 终结解析,本层无对应终结点)。
 */
internal object MinecraftDefaultTypeface : Typeface {
    override val fontFamily: FontFamily? = null
}

/**
 * Minecraft 平台字体加载器(第一版):不加载任何字体文件,所有字体请求返回 null。
 * 后续阶段接入 Minecraft 字体资源。
 */
internal class MinecraftFontLoader : PlatformFontLoader {
    override fun loadBlocking(font: Font): Any? {
        if (font.loadingStrategy != FontLoadingStrategy.OptionalLocal) {
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
