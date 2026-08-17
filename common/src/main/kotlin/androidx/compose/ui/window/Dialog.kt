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

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalPlatformWindowInsets
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.exclude
import androidx.compose.ui.platform.union
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.scene.Content
import androidx.compose.ui.scene.rememberComposeSceneLayer
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.center

/**
 * The default scrim opacity.
 */
private const val DefaultScrimOpacity = 0.6f
private val DefaultScrimColor = Color.Black.copy(alpha = DefaultScrimOpacity)

/**
 * Properties used to customize the behavior of a [Dialog].
 *
 * @property dismissOnBackPress whether the dialog can be dismissed by pressing the back button
 *  * on Android or escape key on desktop.
 * If true, pressing the back button will call onDismissRequest.
 * @property dismissOnClickOutside whether the dialog can be dismissed by clicking outside the
 * dialog's bounds. If true, clicking outside the dialog will call onDismissRequest.
 * @property usePlatformDefaultWidth Whether the width of the dialog's content should be limited to
 * the platform default, which is smaller than the screen width.
 * @property usePlatformInsets Whether the size of the dialog's content should be limited by
 * platform insets.
 * @property useSoftwareKeyboardInset Whether the size of the dialog's content should be limited by
 * software keyboard inset.
 * @property scrimColor Color of background fill.
 * @property animateTransition Whether to animate the appearance and disappearance of the dialog.
 *  Minecraft 平台适配点:当前不实现过渡动画(无 Skia GraphicsLayer 录制),该字段保留
 *  API 兼容但行为等同 false。
 */
@Immutable
class DialogProperties(
    val dismissOnBackPress: Boolean = true,
    val dismissOnClickOutside: Boolean = true,
    val usePlatformDefaultWidth: Boolean = true,
    val usePlatformInsets: Boolean = true,
    val useSoftwareKeyboardInset: Boolean = true,
    val scrimColor: Color = DefaultScrimColor,
    val animateTransition: Boolean = false,
) {
    constructor(
        dismissOnBackPress: Boolean,
        dismissOnClickOutside: Boolean,
        usePlatformDefaultWidth: Boolean,
    ) : this(
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        usePlatformDefaultWidth = usePlatformDefaultWidth,
        usePlatformInsets = true,
        useSoftwareKeyboardInset = true,
        scrimColor = DefaultScrimColor,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DialogProperties) return false

        if (dismissOnBackPress != other.dismissOnBackPress) return false
        if (dismissOnClickOutside != other.dismissOnClickOutside) return false
        if (usePlatformDefaultWidth != other.usePlatformDefaultWidth) return false
        if (usePlatformInsets != other.usePlatformInsets) return false
        if (useSoftwareKeyboardInset != other.useSoftwareKeyboardInset) return false
        if (scrimColor != other.scrimColor) return false
        if (animateTransition != other.animateTransition) return false

        return true
    }

    override fun hashCode(): Int {
        var result = dismissOnBackPress.hashCode()
        result = 31 * result + dismissOnClickOutside.hashCode()
        result = 31 * result + usePlatformDefaultWidth.hashCode()
        result = 31 * result + usePlatformInsets.hashCode()
        result = 31 * result + useSoftwareKeyboardInset.hashCode()
        result = 31 * result + scrimColor.hashCode()
        result = 31 * result + animateTransition.hashCode()
        return result
    }
}

/**
 * Opens a dialog with the given content.
 *
 * A dialog is a window fragment that does not fill the whole screen. It is useful for modal
 * UI surfaces. The dialog is visible as long as it is part of the composition hierarchy.
 *
 * The dialog is centered on the screen and, by default, limited to the platform default width.
 *
 * @sample androidx.compose.ui.samples.DialogSample
 *
 * @param onDismissRequest Executes when the user tries to dismiss the dialog by clicking outside
 * of it or by pressing the back button. This is not called when the user clicks the button that
 * is expected to dismiss the dialog.
 * @param properties [DialogProperties] for further customization of this dialog's behavior.
 * @param content The content to be displayed inside the dialog.
 */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    DialogLayout(
        modifier = Modifier.semantics { dialog() },
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content
    )
}

@Composable
private fun DialogLayout(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    content: @Composable () -> Unit
) {
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)

    val layer = rememberComposeSceneLayer(focusable = true)
    // Minecraft 平台适配点:过渡动画不实现(无 Skia GraphicsLayer 录制),scrim 直接置色;
    // scrim 由 AttachedComposeSceneLayer 绘制在图层内容之下、主场景之上。
    layer.scrimColor = properties.scrimColor

    // back 键:官方实现经 OnBackClickEventHandler + NavigationEventDispatcher,
    // 本移植未提供该机制,改为监听图层 Escape 键(桌面语义,与官方文档一致)。
    layer.setKeyEventListener(
        onKeyEvent = if (properties.dismissOnBackPress) {
            { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    currentOnDismissRequest()
                    true
                } else {
                    false
                }
            }
        } else {
            null
        }
    )

    // 点击 scrim(对话框外)关闭:匹配 [detectTapGestures] 的 Release + 主键语义。
    layer.setOutsidePointerEventListener(
        if (properties.dismissOnClickOutside) {
            { eventType: PointerEventType, button: PointerButton? ->
                if (eventType == PointerEventType.Release &&
                    (button == null || button == PointerButton.Primary)
                ) {
                    currentOnDismissRequest()
                }
            }
        } else {
            null
        }
    )

    val currentContent by rememberUpdatedState(content)
    layer.Content {
        val containerSize = LocalWindowInfo.current.containerSize
        val measurePolicy = rememberDialogMeasurePolicy(
            layer = layer,
            properties = properties,
            containerSize = containerSize
        )

        LocalPlatformWindowInsets.current.exclude(
            safeInsets = properties.usePlatformInsets,
            ime = properties.useSoftwareKeyboardInset
        ) {
            Layout(
                content = currentContent,
                modifier = modifier,
                measurePolicy = measurePolicy
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            layer.close()
        }
    }
}

@Composable
private fun rememberDialogMeasurePolicy(
    layer: ComposeSceneLayer,
    properties: DialogProperties,
    containerSize: IntSize
): MeasurePolicy {
    val platformInsets = properties.platformInsets
    return remember(layer, properties, containerSize, platformInsets) {
        ComposeSceneLayerMeasurePolicy(
            platformInsets = platformInsets,
            usePlatformDefaultWidth = properties.usePlatformDefaultWidth
        ) { contentSize ->
            val positionWithInsets =
                positionWithInsets(platformInsets, containerSize) { sizeWithoutInsets ->
                    sizeWithoutInsets.center - contentSize.center
                }
            layer.boundsInWindow = IntRect(positionWithInsets, contentSize)
            layer.calculateLocalPosition(positionWithInsets)
        }
    }
}

private val DialogProperties.platformInsets: PlatformInsets
    @Composable get() {
        val safeInsets = if (usePlatformInsets) {
            LocalPlatformWindowInsets.current.systemBars
        } else {
            PlatformInsets.Zero
        }

        val ime = if (useSoftwareKeyboardInset) {
            LocalPlatformWindowInsets.current.ime
        } else {
            PlatformInsets.Zero
        }

        return safeInsets.union(ime)
    }