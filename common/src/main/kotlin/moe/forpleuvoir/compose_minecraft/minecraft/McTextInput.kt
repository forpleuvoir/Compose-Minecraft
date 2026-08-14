package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 字符过滤开关(文本输入计划 §5,平台只提供开关、不做具体过滤策略):
 *
 * `charTyped` 的 codepoint 在进入 TextField **之前**经过此过滤器;默认 `{ true }` 全放行,
 * 业务方可用 [androidx.compose.runtime.CompositionLocalProvider] 覆盖,例如屏蔽节号 §:
 *
 * ```
 * CompositionLocalProvider(LocalCharFilter provides { codepoint ->
 *     codepoint != 0x00A7
 * }) {
 *     // TextField 内容
 * }
 * ```
 *
 * 过滤器在输入分发链上读取(场景组合阶段把当前值写入场景侧引用,
 * [MinecraftComposeScene.isCharAccepted] 在 charTyped 转发前调用)。
 */
val LocalCharFilter: ProvidableCompositionLocal<(Int) -> Boolean> =
    staticCompositionLocalOf { { true } }
