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

package androidx.compose.ui.window

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 弹层出现 / 消失的过渡参数。
 *
 * 过渡由一条进度驱动：`0` = 完全隐藏，`1` = 完全显示。各属性都表达"进度 0 时的样子"，
 * 进度 1 时统一为不透明、无位移、无缩放的正位：
 *
 * - 不透明度 = `alpha` → 1 线性插值；
 * - 纵向位移 = `(1 - 进度) × offset`（**正数即向屏幕下方**，所以"从下方滑入"用正 offset 表达）；
 * - 缩放 = `scale` → 1 线性插值（`1` 表示不缩放）。
 *
 * [easing] 只作用于进度曲线本身：入场进度 0 → 1 用 `out` 类曲线表示"减速抵达"，
 * 退场进度 1 → 0 用 `in` 类曲线表示"加速离场"（方向由调用方按段选择）。
 *
 * 本类同时是 [DialogProperties.enterTransition] / [DialogProperties.exitTransition] 的参数类型；
 * 不参与动画时传 `null`。
 *
 * @param durationMillis 该段动画时长（毫秒）
 * @param offset 进度 0 时相对终位的纵向偏移（正数 = 位于终位下方）
 * @param alpha 进度 0 时的不透明度（**默认 0** = 完全透明地淡入淡出；设成 0 以外的值会让
 *   动画末尾仍可见，紧接着图层关闭，视觉上会"顿一下"）
 * @param scale 进度 0 时的缩放（**默认 1** = 不做缩放；像素风素材非整数缩放会让边缘抖动）
 * @param easing 进度曲线
 */
@Immutable
data class DialogTransition(
    val durationMillis: Int = 200,
    val offset: Dp = 10.dp,
    val alpha: Float = 0f,
    val scale: Float = 1f,
    val easing: Easing = FastOutSlowInEasing,
)
