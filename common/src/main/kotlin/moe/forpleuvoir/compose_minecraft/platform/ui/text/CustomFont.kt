/*
 * Copyright 2026 The Compose-Minecraft Project
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

package moe.forpleuvoir.compose_minecraft.platform.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier
import java.nio.file.Path
import moe.forpleuvoir.compose_minecraft.platform.screen.MinecraftComposeScene

/**
 * Compose 便捷接口:加载并注册一个自定义字体文件(ttf/otf/ttc,
 * 系统目录、资源包旁挂载、业务自带均可),返回可直接使用的 [FontDescription]:
 *
 * ```
 * val font = rememberCustomFont(Path.of("C:/Windows/Fonts/arial.ttf"))
 * CompositionLocalProvider(LocalDefaultFont provides font) { ... }
 * // 或指定单段:
 * BasicText(
 *     "Hello",
 *     style = TextStyle(platformStyle = PlatformTextStyle(PlatformSpanStyle(font = font), null)),
 * )
 * ```
 *
 * 组合退出时自动注销(DisposableEffect)。注册在组合期执行 ——
 * [MinecraftComposeScene] 的组合运行于渲染线程,满足
 * [MinecraftCustomFonts.registerCustomFont] 的渲染线程要求。
 *
 * @param path 字体文件绝对路径(*.ttf / *.otf / *.ttc)
 * @param identifier 可指定 [Identifier];缺省由文件名自动生成(compose_minecraft:custom/<slug>,
 *   与 [MinecraftCustomFonts.identifierFor] 一致)
 * @return 注册成功返回 [FontDescription.Resource];失败(文件损坏/无法访问 FontManager)返回 null
 */
@Composable
fun rememberCustomFont(
    path: Path,
    identifier: Identifier = MinecraftCustomFonts.identifierFor(path.fileName.toString()),
): FontDescription? {
    val ok = remember(path, identifier) { MinecraftCustomFonts.registerCustomFont(identifier, path) }
    DisposableEffect(identifier, ok) {
        onDispose {
            if (ok) MinecraftCustomFonts.unregisterCustomFont(identifier)
        }
    }
    return if (ok) FontDescription.Resource(identifier) else null
}