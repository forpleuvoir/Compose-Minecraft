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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.platform.StyleSegment
import androidx.compose.ui.text.style.TextAlign
import moe.forpleuvoir.compose_minecraft.platform.render.text.TextRenderBackend
import net.minecraft.network.chat.Style

/**
 * 平台文本载荷:一段文本进入布局/绘制所需的**全部非文本输入**的唯一载体。
 *
 * 为什么要立这个类型(而不是继续加形参):平台把 Compose 的文本语义压缩成
 * 「MC `Style` + 若干渲染参数」,这些参数原本以 6~7 个独立形参在
 * `BasicText` → element → node 之间手抄,已经连续出现过两次「某条路径漏抄
 * 某字段」的静默失效:
 * - `BasicText(component)` 的 `onTextLayout` 声明了却从未接线;
 * - 富文本路径(AnnotatedString / Selectable)`alpha` / `brush` / `backend` 全部丢失。
 *
 * 收敛成一个对象后,element 只接一个载荷参数、字段集固定,**漏抄在结构上
 * 不可能发生**;后续新增平台字段(如段落对齐)只需改这一处定义 + 各产出点,
 * 不再逐个 element 改签名。
 *
 * 字段语义:
 * - [mcStyle]:映射后的 MC 样式(字符级:color/bold/italic/decoration/字体/原版特性);
 * - [segments]:富文本段列表(全覆盖;空 = 单样式),`fontSize` 段级字号随段携带;
 * - [scale]:字号渲染缩放(18sp = 2x 平台基准字号,`fontSizeToEmPx` 唯一入口产出);
 * - [textAlign]:段落水平对齐(布局端逐行计算起点偏移;`Justify` 平台不支持,按 Start 降级);
 * - [alpha]:文本透明度(合成进绘制色,MC `TextColor` 无 alpha 通道);
 * - [brush]:渐变画刷(非 `SolidColor` 才有值),绘制端逐字形取色;
 * - [backend]:子树级渲染后端定向(`LocalTextRenderBackend`)。
 *
 * 与官方分层的对应关系:官方 `TextStyle` 拆 `SpanStyle`(字符级)+
 * `ParagraphStyle`(段落级),本载荷是二者在 MC 渲染能力上的投影结果。
 *
 * ⚠️ 所有字段**无默认值**:载荷必须在产出点写全,依赖默认值就等于允许漏写。
 * 请经 [toPayload](TextStyle 通路)或 [mcTextPayload](MC Component 通路)构造。
 */
@Immutable
data class PlatformTextPayload(
    /** 映射后的 MC 样式(字符级渲染属性)。 */
    val mcStyle: Style,
    /** 富文本段列表(全覆盖;空 = 单样式)。 */
    val segments: List<StyleSegment>,
    /** 字号渲染缩放(18sp = 2x 平台基准)。 */
    val scale: Float,
    /** 段落水平对齐(MC `Style` 无此属性,故独立成字段;`Unspecified` = 未设置)。 */
    val textAlign: TextAlign,
    /** 文本透明度(合成进绘制色)。 */
    val alpha: Float,
    /** 渐变画刷(非 SolidColor 才有值)。 */
    val brush: Brush?,
    /** 子树级渲染后端定向。 */
    val backend: TextRenderBackend,
)

/**
 * **TextStyle 通路**的载荷产出点(`BasicText(text)` / `BasicText(AnnotatedString)`)。
 *
 * 输入是 `TextStyle.toPlatformData` 的映射结果(唯一映射表,`TextStyleMapper.kt`),
 * 因此本函数**不做任何语义映射**,只负责把「映射结果 + 段列表 + 渲染后端」装配成载荷
 * —— 保证「映射」与「装配」各只有一处。段列表与渲染后端由调用方(组合期)给定:
 * 段列表由文本内容决定,渲染后端来自 `LocalTextRenderBackend`。
 *
 * @param segments 富文本段列表(全覆盖;空 = 单样式)
 * @param backend 子树级渲染后端定向
 */
fun PlatformTextData.toPayload(
    segments: List<StyleSegment>,
    backend: TextRenderBackend,
): PlatformTextPayload = PlatformTextPayload(
    mcStyle = mcStyle,
    segments = segments,
    scale = scale,
    textAlign = textAlign,
    alpha = alpha,
    brush = brush,
    backend = backend,
)

/**
 * **MC `Component` 通路**的载荷产出点(`BasicText(component)`)。
 *
 * Component 通路直接以 MC `Style` 为输入(无 `TextStyle` 映射阶段),故
 * alpha/brush 无来源 —— MC `Style` 既无透明度也无画刷通道,这里固定为
 * `1f` / `null`(即"不透明、无渐变"),不假装支持。
 *
 * 段落对齐同样不在 MC `Style` 里,因此由调用方(`BasicText(component, textAlign = …)`)
 * 显式传入;不传即 `Unspecified`(布局端按 Start 处理)。
 */
fun mcTextPayload(
    mcStyle: Style,
    segments: List<StyleSegment>,
    scale: Float,
    textAlign: TextAlign,
    backend: TextRenderBackend,
): PlatformTextPayload = PlatformTextPayload(
    mcStyle = mcStyle,
    segments = segments,
    scale = scale,
    textAlign = textAlign,
    alpha = 1f,
    brush = null,
    backend = backend,
)
