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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalGraphicsContext
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
import androidx.compose.ui.unit.Density
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
 *  为 true 时入场 / 退场分别按 [enterTransition] / [exitTransition] 播放；为 false 时入场不做动画、
 *  退场直接关闭。
 * @property enterTransition 入场过渡参数（进度 0 → 1），null 表示入场不做动画
 * @property exitTransition 退场过渡参数（进度 1 → 0），null 表示退场不做动画
 */
@Immutable
class DialogProperties(
    val dismissOnBackPress: Boolean = true,
    val dismissOnClickOutside: Boolean = true,
    val usePlatformDefaultWidth: Boolean = true,
    val usePlatformInsets: Boolean = true,
    val useSoftwareKeyboardInset: Boolean = true,
    val scrimColor: Color = DefaultScrimColor,
    val animateTransition: Boolean = true,
    val enterTransition: DialogTransition? = DialogTransition(),
    val exitTransition: DialogTransition? = DialogTransition(),
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
        if (enterTransition != other.enterTransition) return false
        if (exitTransition != other.exitTransition) return false

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
        result = 31 * result + (enterTransition?.hashCode() ?: 0)
        result = 31 * result + (exitTransition?.hashCode() ?: 0)
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
    // 过渡由 [DialogAppearanceController] 承担：它把内容录进 GraphicsLayer 再按进度回放，因此调用方
    // 把 Dialog 移出组合后，退场动画仍能在"重托管"的图层内容里播完。scrim 也由它按进度更新
    // （图层 scrim 由 AttachedComposeSceneLayer 画在图层内容之下、主场景之上）。
    val graphicsContext = LocalGraphicsContext.current
    val appearance = remember(layer, graphicsContext) {
        DialogAppearanceController(layer, graphicsContext)
    }
    appearance.properties = properties

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
        LaunchedEffect(Unit) { appearance.show() }

        val containerSize = LocalWindowInfo.current.containerSize
        val platformInsets = properties.platformInsets
        val measurePolicy = rememberDialogMeasurePolicy(
            layer = layer,
            properties = properties,
            platformInsets = platformInsets,
            containerSize = containerSize
        )
        // 退场重托管时这两个组合已经离开，读不到 CompositionLocal，故缓存给控制器用
        appearance.updateLayout(platformInsets, containerSize)

        LocalPlatformWindowInsets.current.exclude(
            safeInsets = properties.usePlatformInsets,
            ime = properties.useSoftwareKeyboardInset
        ) {
            Layout(
                content = currentContent,
                modifier = appearance.modifier.then(modifier),
                measurePolicy = measurePolicy
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 不直接 close：交给控制器在退场动画播完后释放并关闭图层
            appearance.hide()
        }
    }
}

@Composable
private fun rememberDialogMeasurePolicy(
    layer: ComposeSceneLayer,
    properties: DialogProperties,
    platformInsets: PlatformInsets,
    containerSize: IntSize
): MeasurePolicy = remember(layer, properties, platformInsets, containerSize) {
    dialogMeasurePolicy(layer, properties.usePlatformDefaultWidth, platformInsets, containerSize)
}

/**
 * 弹层内容的测量与定位：内容按平台约束测量、按 [positionWithInsets] 居中摆放，并把结果写回图层
 * （[ComposeSceneLayer.boundsInWindow] 与 [ComposeSceneLayer.calculateLocalPosition]）。
 *
 * 做成非组合函数是为了让退场重托管复用：那一刻原组合已离开，读不到 [PlatformInsets] 相关的
 * CompositionLocal，只能按 [DialogAppearanceController] 缓存的输入复现同一套定位。
 */
private fun dialogMeasurePolicy(
    layer: ComposeSceneLayer,
    usePlatformDefaultWidth: Boolean,
    platformInsets: PlatformInsets,
    containerSize: IntSize,
): MeasurePolicy = ComposeSceneLayerMeasurePolicy(
    platformInsets = platformInsets,
    usePlatformDefaultWidth = usePlatformDefaultWidth
) { contentSize ->
    val positionWithInsets =
        positionWithInsets(platformInsets, containerSize) { sizeWithoutInsets ->
            sizeWithoutInsets.center - contentSize.center
        }
    layer.boundsInWindow = IntRect(positionWithInsets, contentSize)
    layer.calculateLocalPosition(positionWithInsets)
}

/**
 * 弹层出现 / 消失过渡的执行器。
 *
 * 背景：平台 [Dialog] 的图层内容由调用方组合提供，调用方一旦把 Dialog 移出组合，图层本该立即关闭。
 * 为了让退场动画仍能播完，这里在内容之上叠一层 [GraphicsLayer]：内容每帧被**录制**进该层再回放，
 * 于是"这张弹层长什么样"变成一份自包含的绘制命令快照；[hide] 时把图层内容**重新托管**为一个
 * 只回放该快照的轻量组合，等进度归零才释放图层并 [ComposeSceneLayer.close]。
 *
 * 进度语义：`0` = 完全隐藏、`1` = 完全显示；每段的时长 / 位移 / 透明度 / 缩放 / 缓动见 [DialogTransition]。
 * 未启用动画（[DialogProperties.animateTransition] 为 false，或对应段为 null）时退化为旧行为：
 * 入场不做过渡、退场直接关闭。
 */
private class DialogAppearanceController(
    private val layer: ComposeSceneLayer,
    private val graphicsContext: GraphicsContext,
) {

    private val graphicsLayer = graphicsContext.createGraphicsLayer()
    private val progress = Animatable(1f)
    private var entering = true
    private var closing = false
    private var platformInsets: PlatformInsets = PlatformInsets.Zero
    private var containerSize: IntSize = IntSize.Zero

    var properties: DialogProperties = DialogProperties()

    /**
     * 缓存布局输入。退场重托管发生在原组合销毁之后，那时读不到 CompositionLocal，
     * 只能沿用这里存下来的值来复现弹层原来的位置。
     */
    fun updateLayout(platformInsets: PlatformInsets, containerSize: IntSize) {
        this.platformInsets = platformInsets
        this.containerSize = containerSize
    }

    /** 内容修饰器：录进图层 → 按进度施加透明度 / 位移 / 缩放 → 回放；并同步 scrim 不透明度。 */
    val modifier: Modifier get() = Modifier.drawWithContent {
        val transition = activeTransition()
        if (transition == null) {
            layer.scrimColor = properties.scrimColor
            drawContent()
            return@drawWithContent
        }
        graphicsLayer.record { this@drawWithContent.drawContent() }
        graphicsLayer.applyProgress(transition, progress.value, this)
        layer.scrimColor = properties.scrimColor.scaleAlpha(progress.value)
        drawLayer(graphicsLayer)
    }

    /** 入场：进度 0 → 1。 */
    suspend fun show() {
        entering = true
        val transition = activeTransition()
        if (transition == null) {
            progress.snapTo(1f)
            layer.scrimColor = properties.scrimColor
            return
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(transition.durationMillis, easing = transition.easing))
        layer.scrimColor = properties.scrimColor
    }

    /**
     * 退场：把图层内容重新托管为"只回放录制快照"的轻量组合，进度 1 → 0，播完释放并关闭图层。
     *
     * 未启用动画、或从未显示过（进度已是 0）时直接关闭，不进入重托管。
     */
    fun hide() {
        if (closing) return
        closing = true
        entering = false
        val transition = activeTransition()
        if (transition == null || progress.value <= 0f) {
            close()
            return
        }
        val recordedSize = graphicsLayer.size
        layer.setContent {
            Layout(
                content = {
                    // 占位节点：撑出录制尺寸，好让 measurePolicy 把它摆回原位置（居中 / 限宽逻辑不变）
                    Layout(
                        modifier = Modifier.drawBehind {
                            graphicsLayer.applyProgress(transition, progress.value, this)
                            layer.scrimColor = properties.scrimColor.scaleAlpha(progress.value)
                            drawLayer(graphicsLayer)
                        },
                        measurePolicy = { _, _ -> layout(recordedSize.width, recordedSize.height) {} },
                    )
                },
                measurePolicy = dialogMeasurePolicy(
                    layer = layer,
                    usePlatformDefaultWidth = properties.usePlatformDefaultWidth,
                    platformInsets = platformInsets,
                    containerSize = containerSize,
                ),
            )
            LaunchedEffect(Unit) {
                progress.animateTo(0f, tween(transition.durationMillis, easing = transition.easing))
                close()
            }
        }
    }

    private fun activeTransition(): DialogTransition? {
        if (!properties.animateTransition) return null
        return if (entering) properties.enterTransition else properties.exitTransition
    }

    private fun close() {
        graphicsContext.releaseGraphicsLayer(graphicsLayer)
        layer.close()
    }
}

/**
 * 按进度把 [DialogTransition] 施加到图层：进度 1 时是不透明、无位移、无缩放的正位。
 */
private fun GraphicsLayer.applyProgress(
    transition: DialogTransition,
    progress: Float,
    density: Density,
) {
    val p = progress.coerceIn(0f, 1f)
    alpha = (transition.alpha + (1f - transition.alpha) * p).coerceIn(0f, 1f)
    val scale = transition.scale + (1f - transition.scale) * p
    scaleX = scale
    scaleY = scale
    translationY = (1f - p) * with(density) { transition.offset.toPx() }
}

/** 按系数缩放不透明度（0 透到 1 原样）。 */
private fun Color.scaleAlpha(factor: Float): Color =
    copy(alpha = alpha * factor.coerceIn(0f, 1f))

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