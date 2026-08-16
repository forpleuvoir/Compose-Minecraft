/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.ui.graphics

import androidx.compose.ui.InternalComposeUiApi
import kotlin.math.abs

// 移植自 CMP ui-desktop 的 Matrices.skiko.kt:
// 仅保留 prepareTransformationMatrix(纯 Compose Matrix 运算),
// Matrix33(org.jetbrains.skia)相关辅助函数不适用本平台。

@InternalComposeUiApi
fun prepareTransformationMatrix(
    matrix: Matrix,
    pivotX: Float,
    pivotY: Float,
    translationX: Float,
    translationY: Float,
    rotationX: Float,
    rotationY: Float,
    rotationZ: Float,
    scaleX: Float,
    scaleY: Float,
    cameraDistance: Float,
) {
    matrix.reset()
    matrix.translate(x = -pivotX, y = -pivotY)
    // T.15 修复(双轴旋转方向):旋转组合改为**内旋 XYZ**(点先绕 X、再绕 Y、
    // 再绕 Z,与 Android HWUI RenderNode 语义一致)。
    // 此前顺序为 S·Rz·Ry·Rx(点先 Z 再 Y 再 X 的外旋):先设 rotationX 再设
    // rotationY 时,点实际先被 Ry 旋转再被 Rx 旋转 —— 用户先转的轴(X)反而
    // 最后生效,第二个轴(Y)看起来方向反转(用户实测反馈)。
    // 内旋后点应用顺序 = 属性设置直觉:先 rotationX 先生效,rotationY 次之。
    //
    // 构建方式说明:Matrix.rotateX/rotateY 为右乘(M·Rx / M·Ry),
    // Matrix.rotateZ 为左乘(Rz·M),因此 Rz 无法直接加入右乘链,
    // 用显式 timesAssign(右乘 Rz 矩阵)收尾。
    matrix *= Matrix().apply {
        reset()
        scale(scaleX, scaleY)   // S
        rotateX(rotationX)      // S·Rx
        rotateY(rotationY)      // S·Rx·Ry
        timesAssign(Matrix().apply { rotateZ(rotationZ) })  // S·Rx·Ry·Rz
    }
    // Perspective transform should be applied only in case of rotations to avoid
    // multiply application in hierarchies.
    // See Android's frameworks/base/libs/hwui/RenderProperties.cpp for reference
    if (!rotationX.isZero() || !rotationY.isZero()) {
        matrix *= Matrix().apply {
            // The camera location is passed in inches, set in pt
            val depth = cameraDistance * 72f
            this[2, 3] = -1f / depth
        }
    }
    matrix *= Matrix().apply {
        translate(x = pivotX + translationX, y = pivotY + translationY)
    }

    // Third column and row are irrelevant for 2D space.
    // Zeroing required to get correct inverse transformation matrix.
    matrix[2, 0] = 0f
    matrix[2, 1] = 0f
    matrix[2, 3] = 0f
    matrix[0, 2] = 0f
    matrix[1, 2] = 0f
    matrix[3, 2] = 0f
}

// Copy from Android's frameworks/base/libs/hwui/utils/MathUtils.h
private const val NON_ZERO_EPSILON = 0.001f

@Suppress("NOTHING_TO_INLINE")
private inline fun Float.isZero(): Boolean = abs(this) <= NON_ZERO_EPSILON
