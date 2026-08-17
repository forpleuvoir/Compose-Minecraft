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

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.EmptyLayout
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalPlatformWindowInsets
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.exclude
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.scene.Content
import androidx.compose.ui.scene.rememberComposeSceneLayer
import androidx.compose.ui.semantics.popup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round

/**
 * Properties used to customize the behavior of a [Popup].
 *
 * @property focusable Whether the popup is focusable. When true, the popup will receive IME events
 *   and key presses, such as when the back button is pressed.
 * @property dismissOnBackPress Whether the popup can be dismissed by pressing the back or escape
 *   buttons on Android or the escape key on desktop. If true, pressing the back button will call
 *   onDismissRequest. Note that [focusable] must be set to true in order to receive key events such
 *   as the back button - if the popup is not focusable then this property does nothing.
 * @property dismissOnClickOutside Whether the popup can be dismissed by clicking outside the
 *   popup's bounds. If true, clicking outside the popup will call onDismissRequest.
 * @property clippingEnabled Whether to allow the popup window to extend beyond the bounds of the
 *   screen. By default the window is clipped to the screen boundaries. Setting this to false will
 *   allow windows to be accurately positioned. The default value is true.
 * @property usePlatformDefaultWidth Whether the width of the popup's content should be limited to
 *   the platform default, which is smaller than the screen width.
 * @property usePlatformInsets Whether the width of the popup's content should be limited by
 *   platform insets.
 *  Minecraft 平台适配点:本平台为全屏游戏窗口,无系统栏/输入法 insets(恒为 0),该字段
 *  保留以对齐官方 API,行为与官方一致。
 */
@Immutable
class PopupProperties(
    val focusable: Boolean = false,
    val dismissOnBackPress: Boolean = true,
    val dismissOnClickOutside: Boolean = true,
    val clippingEnabled: Boolean = true,
    val usePlatformDefaultWidth: Boolean = false,
    val usePlatformInsets: Boolean = true,
) {
    @Deprecated("Maintained for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(
        focusable: Boolean = false,
        dismissOnBackPress: Boolean = true,
        dismissOnClickOutside: Boolean = true,
        clippingEnabled: Boolean = true,
    ) : this(
        focusable = focusable,
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        clippingEnabled = clippingEnabled,
    )

    constructor(
        focusable: Boolean,
        dismissOnBackPress: Boolean,
        dismissOnClickOutside: Boolean,
        clippingEnabled: Boolean,
        usePlatformDefaultWidth: Boolean,
    ) : this(
        focusable = focusable,
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        clippingEnabled = clippingEnabled,
        usePlatformDefaultWidth = usePlatformDefaultWidth,
        usePlatformInsets = true,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PopupProperties) return false

        if (focusable != other.focusable) return false
        if (dismissOnBackPress != other.dismissOnBackPress) return false
        if (dismissOnClickOutside != other.dismissOnClickOutside) return false
        if (clippingEnabled != other.clippingEnabled) return false
        if (usePlatformDefaultWidth != other.usePlatformDefaultWidth) return false
        if (usePlatformInsets != other.usePlatformInsets) return false

        return true
    }

    override fun hashCode(): Int {
        var result = focusable.hashCode()
        result = 31 * result + dismissOnBackPress.hashCode()
        result = 31 * result + dismissOnClickOutside.hashCode()
        result = 31 * result + clippingEnabled.hashCode()
        result = 31 * result + usePlatformDefaultWidth.hashCode()
        result = 31 * result + usePlatformInsets.hashCode()
        return result
    }
}

/** Calculates the position of a [Popup] on screen. */
@Immutable
interface PopupPositionProvider {
    /**
     * Calculates the position of a [Popup] on screen.
     *
     * The window size is useful in cases where the popup is meant to be positioned next to its
     * anchor instead of inside of it. The size can be used to calculate available space around the
     * parent to find a spot with enough clearance (e.g. when implementing a dropdown). Note that
     * positioning the popup outside of the window bounds might prevent it from being visible.
     *
     * @param anchorBounds The window relative bounds of the layout which this popup is anchored to.
     * @param windowSize The size of the window containing the anchor layout.
     * @param layoutDirection The layout direction of the anchor layout.
     * @param popupContentSize The size of the popup's content.
     * @return The window relative position where the popup should be positioned.
     */
    fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset
}

internal class AlignmentOffsetPositionProvider(val alignment: Alignment, val offset: IntOffset) :
    PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorAlignmentPoint = alignment.align(IntSize.Zero, anchorBounds.size, layoutDirection)
        // Note the negative sign. Popup alignment point contributes negative offset.
        val popupAlignmentPoint = -alignment.align(IntSize.Zero, popupContentSize, layoutDirection)
        val resolvedUserOffset =
            IntOffset(offset.x * (if (layoutDirection == LayoutDirection.Ltr) 1 else -1), offset.y)

        return anchorBounds.topLeft +
            anchorAlignmentPoint +
            popupAlignmentPoint +
            resolvedUserOffset
    }
}

/**
 * Opens a popup with the given content.
 *
 * A popup is a floating container that appears on top of the current activity. It is especially
 * useful for non-modal UI surfaces that remain hidden until they are needed, for example floating
 * menus like Cut/Copy/Paste.
 *
 * The popup is positioned relative to its parent, using the [alignment] and [offset]. The popup is
 * visible as long as it is part of the composition hierarchy.
 *
 * @sample androidx.compose.ui.samples.PopupSample
 * @param alignment The alignment relative to the parent.
 * @param offset An offset from the original aligned position of the popup. Offset respects the
 *   Ltr/Rtl context, thus in Ltr it will be added to the original aligned position and in Rtl it
 *   will be subtracted from it.
 * @param onDismissRequest Executes when the user clicks outside of the popup.
 * @param properties [PopupProperties] for further customization of this popup's behavior.
 * @param content The content to be displayed inside the popup.
 */
@Composable
fun Popup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val popupPositioner = remember(alignment, offset) {
        AlignmentOffsetPositionProvider(alignment, offset)
    }
    Popup(
        popupPositionProvider = popupPositioner,
        onDismissRequest = onDismissRequest,
        properties = properties,
        onPreviewKeyEvent = null,
        onKeyEvent = null,
        content = content,
    )
}

/**
 * Opens a popup with the given content.
 *
 * The popup is positioned using a custom [popupPositionProvider].
 *
 * @sample androidx.compose.ui.samples.PopupWithPositionProviderSample
 * @param popupPositionProvider Provides the screen position of the popup.
 * @param onDismissRequest Executes when the user clicks outside of the popup.
 * @param properties [PopupProperties] for further customization of this popup's behavior.
 * @param content The content to be displayed inside the popup.
 */
@Composable
fun Popup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
        onPreviewKeyEvent = null,
        onKeyEvent = null,
        content = content,
    )
}

/**
 * Opens a popup with the given content.
 *
 * The popup is positioned relative to its parent, using the [alignment] and [offset].
 * The popup is visible as long as it is part of the composition hierarchy.
 *
 * @sample androidx.compose.ui.samples.PopupSample
 *
 * @param alignment The alignment relative to the parent.
 * @param offset An offset from the original aligned position of the popup. Offset respects the
 *   Ltr/Rtl context, thus in Ltr it will be added to the original aligned position and in Rtl it
 *   will be subtracted from it.
 * @param onDismissRequest Executes when the user clicks outside the popup.
 * @param properties [PopupProperties] for further customization of this popup's behavior.
 * @param onPreviewKeyEvent This callback is invoked when the user interacts with the hardware
 *   keyboard. It gives ancestors of a focused component the chance to intercept a [KeyEvent].
 *   Return true to stop propagation of this event. If you return false, the key event will be
 *   sent to this [onPreviewKeyEvent]'s child. If none of the children consume the event,
 *   it will be sent back up to the root using the onKeyEvent callback.
 * @param onKeyEvent This callback is invoked when the user interacts with the hardware
 *   keyboard. While implementing this callback, return true to stop propagation of this event.
 *   If you return false, the key event will be sent to this [onKeyEvent]'s parent.
 * @param content The content to be displayed inside the popup.
 */
@Composable
fun Popup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    val popupPositioner = remember(alignment, offset) {
        AlignmentOffsetPositionProvider(alignment, offset)
    }
    Popup(
        popupPositionProvider = popupPositioner,
        onDismissRequest = onDismissRequest,
        properties = properties,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        content = content,
    )
}

/**
 * Opens a popup with the given content.
 *
 * The popup is positioned using a custom [popupPositionProvider].
 *
 * @sample androidx.compose.ui.samples.PopupSample
 *
 * @param popupPositionProvider Provides the screen position of the popup.
 * @param onDismissRequest Executes when the user clicks outside the popup.
 * @param properties [PopupProperties] for further customization of this popup's behavior.
 * @param onPreviewKeyEvent This callback is invoked when the user interacts with the hardware
 *   keyboard. It gives ancestors of a focused component the chance to intercept a [KeyEvent].
 *   Return true to stop propagation of this event. If you return false, the key event will be
 *   sent to this [onPreviewKeyEvent]'s child. If none of the children consume the event,
 *   it will be sent back up to the root using the onKeyEvent callback.
 * @param onKeyEvent This callback is invoked when the user interacts with the hardware
 *   keyboard. While implementing this callback, return true to stop propagation of this event.
 *   If you return false, the key event will be sent to this [onKeyEvent]'s parent.
 * @param content The content to be displayed inside the popup.
 */
@Composable
fun Popup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnKeyEvent by rememberUpdatedState(onKeyEvent)

    // Minecraft 平台适配点:官方实现中 focusable Popup 经 OnBackClickEventHandler 注册到
    // NavigationEventDispatcher 消费 back 键;本移植未提供该机制,改为在图层 key 监听中
    // 检测 Escape 键(桌面语义,与官方文档「back on Android / escape on desktop」一致)。
    val escapeOnKeyEvent: ((KeyEvent) -> Boolean)? =
        if (properties.focusable && properties.dismissOnBackPress && onDismissRequest != null) {
            { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    currentOnDismissRequest?.invoke()
                    true
                } else {
                    currentOnKeyEvent?.invoke(event) == true
                }
            }
        } else {
            null
        }

    val onOutsidePointerEvent = if (properties.dismissOnClickOutside && onDismissRequest != null) {
        { eventType: PointerEventType, _: PointerButton? ->
            if (eventType == PointerEventType.Press) {
                currentOnDismissRequest?.invoke()
            }
        }
    } else {
        null
    }
    PopupLayout(
        popupPositionProvider = popupPositionProvider,
        properties = properties,
        modifier = Modifier.semantics { popup() },
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = escapeOnKeyEvent ?: currentOnKeyEvent,
        onOutsidePointerEvent = onOutsidePointerEvent,
        content = content,
    )
}

@Composable
private fun PopupLayout(
    popupPositionProvider: PopupPositionProvider,
    properties: PopupProperties,
    modifier: Modifier,
    onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onOutsidePointerEvent: ((eventType: PointerEventType, button: PointerButton?) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // Use a MutableState directly to avoid recomposing when the value changes
    val parentBoundsInWindow: MutableState<IntRect> = remember { mutableStateOf(IntRect.Zero) }
    EmptyLayout(Modifier.onPlaced { childCoordinates ->
        // This will be called before the popup measure policy is actually asked to calculate
        // the popup's position, so it will never see the initial value of IntRect.Zero
        childCoordinates.parentCoordinates?.let {
            // Nodes which read layout coordinates (including, e.g., positionInWindow) in
            // layout/placement get invalidated when these coordinates change
            val layoutPosition = it.positionInWindow().round()
            val layoutSize = it.size
            parentBoundsInWindow.value = IntRect(layoutPosition, layoutSize)
        }
    })

    val currentContent by rememberUpdatedState(content)
    val layer = rememberComposeSceneLayer(
        focusable = properties.focusable
        // Minecraft 平台适配点:官方桌面实现另有 consumePointerInputOutside 参数,
        // 本移植的图层尺寸即窗口尺寸、且 focusable 层经 CanvasLayersComposeScene 的
        // focusedLayer 机制天然阻断下层交互,语义等价,不再单独提供。
    )
    layer.setKeyEventListener(onPreviewKeyEvent, onKeyEvent)
    layer.setOutsidePointerEventListener(onOutsidePointerEvent)
    layer.Content {
        val platformInsets = properties.platformInsets
        val containerSize = LocalWindowInfo.current.containerSize
        val layoutDirection = LocalLayoutDirection.current
        val measurePolicy = rememberPopupMeasurePolicy(
            layer = layer,
            popupPositionProvider = popupPositionProvider,
            properties = properties,
            containerSize = containerSize,
            platformInsets = platformInsets,
            layoutDirection = layoutDirection,
            parentBoundsInWindow = parentBoundsInWindow
        )

        LocalPlatformWindowInsets.current.exclude(
            properties.usePlatformInsets,
            false
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

private val PopupProperties.platformInsets
    @Composable get(): androidx.compose.ui.platform.PlatformInsets {
        return if (usePlatformInsets) {
            LocalPlatformWindowInsets.current.systemBars
        } else {
            androidx.compose.ui.platform.PlatformInsets.Zero
        }
    }

@Composable
private fun rememberPopupMeasurePolicy(
    layer: ComposeSceneLayer,
    popupPositionProvider: PopupPositionProvider,
    properties: PopupProperties,
    containerSize: IntSize,
    platformInsets: androidx.compose.ui.platform.PlatformInsets,
    layoutDirection: LayoutDirection,
    parentBoundsInWindow: MutableState<IntRect>
) = remember(
    layer,
    popupPositionProvider,
    properties,
    containerSize,
    platformInsets,
    layoutDirection,
    parentBoundsInWindow
) {
    ComposeSceneLayerMeasurePolicy(
        platformInsets = platformInsets,
        usePlatformDefaultWidth = properties.usePlatformDefaultWidth
    ) { contentSize ->
        val parentRectInWindow = parentBoundsInWindow.value
        val positionWithInsets =
            positionWithInsets(platformInsets, containerSize) { sizeWithoutInsets ->
                // Position provider works in coordinates without insets.
                val boundsWithoutInsets = parentRectInWindow.translate(
                    -platformInsets.left,
                    -platformInsets.top
                )
                val positionInWindow = popupPositionProvider.calculatePosition(
                    anchorBounds = boundsWithoutInsets,
                    windowSize = sizeWithoutInsets,
                    layoutDirection = layoutDirection,
                    popupContentSize = contentSize
                )
                if (properties.clippingEnabled) {
                    clipPosition(positionInWindow, contentSize, sizeWithoutInsets)
                } else {
                    positionInWindow
                }
            }
        layer.boundsInWindow = IntRect(positionWithInsets, contentSize)
        layer.calculateLocalPosition(positionWithInsets)
    }
}

internal fun clipPosition(position: IntOffset, contentSize: IntSize, containerSize: IntSize) =
    IntOffset(
        x = if (contentSize.width < containerSize.width) {
            position.x.coerceIn(0, containerSize.width - contentSize.width)
        } else 0,
        y = if (contentSize.height < containerSize.height) {
            position.y.coerceIn(0, containerSize.height - contentSize.height)
        } else 0
    )