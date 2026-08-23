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

import moe.forpleuvoir.compose_minecraft.platform.render.text.TextRenderConfig

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier

/**
 * 默认文本样式(T.30):`BasicText` 等文本组件**未显式传 [TextStyle]**时使用的默认样式。
 * 默认 [TextStyle.Default];业务可用
 * [androidx.compose.runtime.CompositionLocalProvider] 覆盖(如全局字号/颜色)。
 *
 * 平台适配点:官方 BasicText 的 style 默认值是编译期常量 [TextStyle.Default],
 * 无 CompositionLocal 兜底;本平台改为可空 + 本 Local 兜底,便于全局换肤。
 */
val LocalDefaultTextStyle = staticCompositionLocalOf<TextStyle> { TextStyle.Default }

/**
 * 默认字体(T.30 → P3 像素化):文本**未显式指定字体**(`SpanStyle.platformStyle.font`)时使用的
 * MC 字体。默认 [MinecraftFonts.FusionPixel](compose_minecraft:fusion_pixel,平台内置
 * Fusion Pixel 12px 像素字体,**仅作用于 Compose 渲染的文本** —— 经原版 FreeType
 * 管线按设计网格渲染,锐利;原版自身渲染如 tooltip/HUD 不受影响,仍用 minecraft:default)。
 * 业务可 Provider 覆盖为 [MinecraftFonts] 其它字体或 [rememberCustomFont] 加载的自定义字体。
 */
val LocalDefaultFont = staticCompositionLocalOf<FontDescription> { MinecraftFonts.FusionPixel }

/**
 * 默认字号(T.32 → P3 像素化):`BasicText` 等文本组件的 [TextStyle]**未显式指定
 * `fontSize`**(`TextStyle.Default` 语义)时使用的默认字号。
 * 默认 = [TextRenderConfig.pixelFontEmSp](12sp):像素字体设计网格,字形与屏幕
 * 像素 1:1(锐利);放大建议取 12 的整数倍。业务可用
 * [androidx.compose.runtime.CompositionLocalProvider] 覆盖(显式传 `fontSize`
 * 的样式优先于本 Local)。
 */
// 默认字号 = 像素字体 em 基准(原「原版行高 ×2 = 18sp / TTF 16sp」双分支已随
// 全局默认字体切换为 Fusion Pixel 而统一,见 [TextRenderConfig.pixelFontEmSp])
// ⚠️ 默认值语义(P3 修正):staticCompositionLocalOf 的默认 lambda **首次访问后
// 全局冻结** —— 不能承载「随模式切换的动态默认」。因此本 Local 的默认值改为
// [TextUnit.Unspecified](= 未提供,消费端经 [resolveDefaultFontSize] 跟随当前
// 模式实时解析);显式 provide 非 Unspecified 值仍是业务覆盖通道。
val LocalDefaultFontSize = staticCompositionLocalOf<TextUnit> { TextUnit.Unspecified }

/**
 * 解析生效默认字体(P3):像素模式下 LocalDefaultFont 的冻结默认(fusion_pixel)
 * 生效;stb 系统字体模式([TextRenderConfig.usePixelDefaultFont] = false)下,
 * 该哨兵值映射回 minecraft:default(自研 stb 渲染器的输入)。业务显式
 * provide 的字体始终优先。
 */
@Composable
fun resolveDefaultFont(): FontDescription {
    // P2-B5:哨兵映射随双模式开关消亡 —— 业务 Provider 即最终语义,
    // 「默认字体是谁」由 FontResolver.defaultFontId 唯一决定。
    return LocalDefaultFont.current
}

/** 解析生效默认字号(sp):跟随 [TextRenderConfig.effectiveDefaultFontSizeSp] 实时值 */
@Composable
fun resolveDefaultFontSize(): TextUnit =
    moe.forpleuvoir.compose_minecraft.platform.render.text.FontResolver.defaultFont().defaultSizeSp.sp

/**
 * 文本渲染后端定向选择(T.TT P2):子树级强制原版位图渲染。
 *
 * - 默认 [TextRenderBackend.DEFAULT]:跟随全局开关(TextRenderConfig.enabled);
 * - 提供 [TextRenderBackend.VANILLA]:该子树内文本强制原版位图字形渲染;
 * - 无「强制启用」档:启用是平台级决策,不暴露给子树。
 *
 * 组合期读取(BasicText/BasicTextField 内),经绘制节点盖章进 DrawTextCommand。
 */
val LocalTextRenderBackend =
    staticCompositionLocalOf { moe.forpleuvoir.compose_minecraft.platform.render.text.TextRenderBackend.DEFAULT }

/**
 * MC 资源字体清单(T.30):Minecraft 渲染体系可用的内置字体
 * (字体定义资源 `font/<name>.json`,经 [FontDescription.Resource] 引用)。
 *
 * - [Default]:minecraft:default,原版默认字体(**仅原版渲染使用** ——
 *   tooltip/HUD 等;Compose 文本默认已切至 [FusionPixel]);
 * - [FusionPixel]:compose_minecraft:fusion_pixel,平台内置 Fusion Pixel
 *   12px 像素字体(P3 全局默认,定义见 `assets/compose_minecraft/font/fusion_pixel.json`);
 * - [Alt]:minecraft:alt,备选字体(更紧凑的字形集);
 * - [UniFont]:minecraft:unifont,Unicode 全量字体(启用「强制 Unicode 字体」时使用);
 * - [IllagerAlt]:minecraft:illageralt,灾厄村民文字(替换字形);
 * - [Missing]:minecraft:missing,缺字字体(占位方格)。
 *
 * 注:资源包自定义字体(id 由资源包 `font/<name>.json` 定义)可直接构造
 * `FontDescription.Resource(Identifier)` 引用,运行时按 id 解析。
 */
object MinecraftFonts {
    val Default: FontDescription = FontDescription.DEFAULT

    /** 平台内置 Fusion Pixel 像素字体(compose_minecraft:fusion_pixel,P3 全局默认) */
    val FusionPixel: FontDescription =
        FontDescription.Resource(Identifier.fromNamespaceAndPath("compose_minecraft", "fusion_pixel"))

    /**
     * Fusion Pixel 等宽变体(compose_minecraft:fusion_pixel_mono)。
     * 与 [FusionPixel] 同家族同参数(size=12),特殊用途保留 —— 代码/坐标/
     * 表格等需要等宽对齐的场景;业务可经 [LocalDefaultFont] 子树指定。
     */
    val FusionPixelMono: FontDescription =
        FontDescription.Resource(Identifier.fromNamespaceAndPath("compose_minecraft", "fusion_pixel_mono"))

    val Alt: FontDescription = FontDescription.Resource(Identifier.withDefaultNamespace("alt"))
    val UniFont: FontDescription = FontDescription.Resource(Identifier.withDefaultNamespace("unifont"))
    val IllagerAlt: FontDescription = FontDescription.Resource(Identifier.withDefaultNamespace("illageralt"))
    val Missing: FontDescription = FontDescription.Resource(Identifier.withDefaultNamespace("missing"))
}
