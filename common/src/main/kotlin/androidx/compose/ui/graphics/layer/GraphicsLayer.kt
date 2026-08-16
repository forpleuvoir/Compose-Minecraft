/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.ui.graphics.layer

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.MinecraftImageBitmap
import androidx.compose.ui.graphics.MinecraftPath
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.isIdentity
import androidx.compose.ui.graphics.prepareTransformationMatrix
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * Draw the provided [GraphicsLayer] into the current [DrawScope]. The [GraphicsLayer] provided must
 * have [GraphicsLayer.record] invoked on it otherwise no visual output will be seen in the rendered
 * result.
 *
 * @sample androidx.compose.ui.graphics.samples.GraphicsLayerTopLeftSample
 * @sample androidx.compose.ui.graphics.samples.GraphicsLayerScaleAndPivotSample
 * @sample androidx.compose.ui.graphics.samples.GraphicsLayerColorFilterSample
 * @sample androidx.compose.ui.graphics.samples.GraphicsLayerRenderEffectSample
 * @sample androidx.compose.ui.graphics.samples.GraphicsLayerAlphaSample
 * @sample androidx.compose.ui.graphics.samples.GraphicsLayerRotationX
 * @sample androidx.compose.ui.graphics.samples.GraphicsLayerRotationYWithCameraDistance
 */
fun DrawScope.drawLayer(graphicsLayer: GraphicsLayer) {
    drawIntoCanvas { canvas -> graphicsLayer.draw(canvas, drawContext.graphicsLayer) }
}

/** Default camera distance for all layers */
const val DefaultCameraDistance = 8.0f

/**
 * Drawing layer used to record drawing commands in a displaylist as well as additional properties
 * that affect the rendering of the display list. This provides an isolation boundary to divide a
 * complex scene into smaller pieces that can be updated individually of one another without
 * recreating the entire scene. Transformations made to a [GraphicsLayer] can be done without
 * re-recording the display list.
 *
 * Usage of a [GraphicsLayer] requires a minimum of 2 steps.
 * 1) The [GraphicsLayer] must be built, which involves specifying the position alongside a list of
 *    drawing commands using [GraphicsLayer.record]
 * 2) The [GraphicsLayer] is then drawn into another destination [Canvas] using
 *    [GraphicsLayer.draw].
 *
 * Additionally the contents of the displaylist can be transformed when it is drawn into a
 * desintation [Canvas] by specifying either [scaleX], [scaleY], [translationX], [translationY],
 * [rotationX], [rotationY], or [rotationZ].
 *
 * The rendered result of the displaylist can also be modified by configuring the
 * [GraphicsLayer.blendMode], [GraphicsLayer.colorFilter], [GraphicsLayer.alpha] or
 * [GraphicsLayer.renderEffect]
 */
class GraphicsLayer internal constructor() {

    private val canvasDrawScope = CanvasDrawScope()

    /** 记录阶段(record)使用的画布,draw/toImageBitmap 阶段回放 */
    private var recordingCanvas: MinecraftCanvas? = null

    /** 由 set*Outline 设置的轮廓;null 时 [outline] 回退为图层尺寸矩形 */
    private var layerOutline: Outline? = null

    /** 阴影光源方向(平台扩展 T.14):归一化向量,屏幕 y 向下,默认右上角 */
    var shadowLightDirectionX: Float = 1f
    var shadowLightDirectionY: Float = -1f

    /**
     * [CompositingStrategy] determines whether or not the contents of this layer are rendered into
     * an offscreen buffer. This is useful in order to optimize alpha usages with
     * [CompositingStrategy.ModulateAlpha] which will skip the overhead of an offscreen buffer but
     * can generate different rendering results depending on whether or not the contents of the
     * layer are overlapping. Similarly leveraging [CompositingStrategy.Offscreen] is useful in
     * situations where creating an offscreen buffer is preferred usually in conjunction with
     * [BlendMode] usage.
     *
     * When [blendMode] is anything other than [BlendMode.SrcOver] or [colorFilter] is non-null,
     * [compositingStrategy]'s value will be overridden and is forced to
     * [CompositingStrategy.Offscreen].
     */
    var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto

    /**
     * Offset in pixels where this [GraphicsLayer] will render within a provided canvas when
     * [drawLayer] is called.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerTopLeftSample
     */
    var topLeft: IntOffset = IntOffset.Zero

    /**
     * Size in pixels of the [GraphicsLayer]. By default [GraphicsLayer] contents can draw outside
     * of the bounds specified by [topLeft] and [size], however, rasterization of this layer into an
     * offscreen buffer will be sized according to the specified size. This is configured by calling
     * [record]
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerSizeSample
     */
    var size: IntSize = IntSize.Zero
        private set

    /**
     * [Offset] in pixels used as the center for any rotation or scale transformation. If this value
     * is [Offset.Unspecified], then the center of the [GraphicsLayer] is used relative to [topLeft]
     * and [size]
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerScaleAndPivotSample
     */
    var pivotOffset: Offset = Offset.Unspecified

    /**
     * Alpha of the content of the [GraphicsLayer] between 0f and 1f. Any value between 0f and 1f
     * will be translucent, where 0f will cause the layer to be completely invisible and 1f will be
     * entirely opaque.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerAlphaSample
     */
    var alpha: Float = 1f

    /**
     * The horizontal scale of the drawn area. Default value is `1`.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerScaleAndPivotSample
     */
    var scaleX: Float = 1f

    /**
     * The vertical scale of the drawn area. Default value is `1`.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerScaleAndPivotSample
     */
    var scaleY: Float = 1f

    /**
     * Horizontal pixel offset of the layer relative to [topLeft].x. Default value is `0`.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerTranslateSample
     */
    var translationX: Float = 0f

    /**
     * Vertical pixel offset of the layer relative to [topLeft].y. Default value is `0`
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerTranslateSample
     */
    var translationY: Float = 0f

    /**
     * Sets the elevation for the shadow in pixels. With the [shadowElevation] > 0f and [Outline]
     * set, a shadow is produced. Default value is `0` and the value must not be negative.
     * Configuring a non-zero [shadowElevation] enables clipping of [GraphicsLayer] content.
     *
     * Note that if you provide a non-zero [shadowElevation] and if the passed [Outline] is concave
     * the shadow will not be drawn on Android versions less than 10.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerShadowSample
     */
    var shadowElevation: Float = 0f

    /**
     * Sets the color of the ambient shadow that is drawn when [shadowElevation] > 0f.
     *
     * By default the shadow color is black. Generally, this color will be opaque so the intensity
     * of the shadow is consistent between different graphics layers with different colors.
     *
     * The opacity of the final ambient shadow is a function of the shadow caster height, the alpha
     * channel of the [ambientShadowColor] (typically opaque), and the
     * [android.R.attr.ambientShadowAlpha] theme attribute.
     *
     * Note that this parameter is only supported on Android 9 (Pie) and above. On older versions,
     * this property always returns [Color.Black] and setting new values is ignored.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerShadowSample
     */
    var ambientShadowColor: Color = Color.Black

    /**
     * Sets the color of the spot shadow that is drawn when [shadowElevation] > 0f.
     *
     * By default the shadow color is black. Generally, this color will be opaque so the intensity
     * of the shadow is consistent between different graphics layers with different colors.
     *
     * The opacity of the final spot shadow is a function of the shadow caster height, the alpha
     * channel of the [spotShadowColor] (typically opaque), and the [android.R.attr.spotShadowAlpha]
     * theme attribute.
     *
     * Note that this parameter is only supported on Android 9 (Pie) and above. On older versions,
     * this property always returns [Color.Black] and setting new values is ignored.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerShadowSample
     */
    var spotShadowColor: Color = Color.Black

    /**
     * BlendMode to use when drawing this layer to the destination in [drawLayer]. The default is
     * [BlendMode.SrcOver]. Any value other than [BlendMode.SrcOver] will force this [GraphicsLayer]
     * to use an offscreen compositing layer for rendering and is equivalent to using
     * [CompositingStrategy.Offscreen].
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerBlendModeSample
     */
    var blendMode: BlendMode = BlendMode.SrcOver

    /**
     * ColorFilter applied when drawing this layer to the destination in [drawLayer]. Setting of
     * this to any non-null will force this [GraphicsLayer] to use an offscreen compositing layer
     * for rendering and is equivalent to using [CompositingStrategy.Offscreen]
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerColorFilterSample
     */
    var colorFilter: ColorFilter? = null

    /**
     * Returns the outline specified by either [setPathOutline] or [setRoundRectOutline]. By default
     * this will return [Outline.Rectangle] with the size of the [GraphicsLayer] specified by
     * [record] or [IntSize.Zero] if [record] was not previously invoked.
     */
    val outline: Outline
        get() = layerOutline
            ?: Outline.Rectangle(Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()))

    /**
     * Specifies the given path to be configured as the outline for this [GraphicsLayer]. When
     * [shadowElevation] is non-zero a shadow is produced with an [Outline] created from the
     * provided [path]. Additionally if [clip] is true, the contents of this [GraphicsLayer] will be
     * clipped to this geometry.
     *
     * @param path Path to be used as the Outline for the [GraphicsLayer]
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerOutlineSample
     */
    fun setPathOutline(path: Path) {
        layerOutline = Outline.Generic(path)
    }

    /**
     * Configures a rounded rect outline for this [GraphicsLayer]. By default, [topLeft] is set to
     * [Size.Zero] and [size] is set to [Size.Unspecified] indicating that the outline should match
     * the size of the [GraphicsLayer]. When [shadowElevation] is non-zero a shadow is produced
     * using an [Outline] created from the round rect parameters provided. Additionally if [clip] is
     * true, the contents of this [GraphicsLayer] will be clipped to this geometry.
     *
     * @param topLeft The top left of the rounded rect outline
     * @param size The size of the rounded rect outline
     * @param cornerRadius The corner radius of the rounded rect outline
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerRoundRectOutline
     */
    fun setRoundRectOutline(
        topLeft: Offset = Offset.Zero,
        size: Size = Size.Unspecified,
        cornerRadius: Float = 0f,
    ) {
        val w = if (size != Size.Unspecified) size.width else this.size.width.toFloat()
        val h = if (size != Size.Unspecified) size.height else this.size.height.toFloat()
        layerOutline = Outline.Rounded(
            RoundRect(
                topLeft.x, topLeft.y,
                topLeft.x + w, topLeft.y + h,
                CornerRadius(cornerRadius),
            )
        )
    }

    /**
     * Configures a rectangular outline for this [GraphicsLayer]. By default, [topLeft] is set to
     * [Size.Zero] and [size] is set to [Size.Unspecified] indicating that the outline should match
     * the size of the [GraphicsLayer]. When [shadowElevation] is non-zero a shadow is produced
     * using an [Outline] created from the round rect parameters provided. Additionally if [clip] is
     * true, the contents of this [GraphicsLayer] will be clipped to this geometry.
     *
     * @param topLeft The top left of the rounded rect outline
     * @param size The size of the rounded rect outline
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerRectOutline
     */
    fun setRectOutline(topLeft: Offset = Offset.Zero, size: Size = Size.Unspecified) {
        val w = if (size != Size.Unspecified) size.width else this.size.width.toFloat()
        val h = if (size != Size.Unspecified) size.height else this.size.height.toFloat()
        layerOutline = Outline.Rectangle(
            Rect(topLeft.x, topLeft.y, topLeft.x + w, topLeft.y + h)
        )
    }

    /**
     * The rotation, in degrees, of the contents around the horizontal axis in degrees. Default
     * value is `0`.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerRotationX
     */
    var rotationX: Float = 0f

    /**
     * The rotation, in degrees, of the contents around the vertical axis in degrees. Default value
     * is `0`.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerRotationYWithCameraDistance
     */
    var rotationY: Float = 0f

    /**
     * The rotation, in degrees, of the contents around the Z axis in degrees. Default value is `0`.
     */
    var rotationZ: Float = 0f

    /**
     * Sets the distance along the Z axis (orthogonal to the X/Y plane on which layers are drawn)
     * from the camera to this layer. The camera's distance affects 3D transformations, for instance
     * rotations around the X and Y axis. If the rotationX or rotationY properties are changed and
     * this view is large (more than half the size of the screen), it is recommended to always use a
     * camera distance that's greater than the height (X axis rotation) or the width (Y axis
     * rotation) of this view.
     *
     * The distance of the camera from the drawing plane can have an affect on the perspective
     * distortion of the layer when it is rotated around the x or y axis. For example, a large
     * distance will result in a large viewing angle, and there will not be much perspective
     * distortion of the view as it rotates. A short distance may cause much more perspective
     * distortion upon rotation, and can also result in some drawing artifacts if the rotated view
     * ends up partially behind the camera (which is why the recommendation is to use a distance at
     * least as far as the size of the view, if the view is to be rotated.)
     *
     * The distance is expressed in pixels and must always be positive. Default value is
     * [DefaultCameraDistance]
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerRotationYWithCameraDistance
     */
    var cameraDistance: Float = DefaultCameraDistance

    /**
     * Determines if the [GraphicsLayer] should be clipped to the rectangular bounds specified by
     * [topLeft] and [size]. The default is false, however, contents will always be clipped to their
     * bounds when the GraphicsLayer is promoted off an offscreen rendering buffer (i.e.
     * CompositingStrategy.Offscreen is used, a non-null ColorFilter, RenderEffect is applied or if
     * the BlendMode is not equivalent to BlendMode.SrcOver
     */
    @Suppress("GetterSetterNames") @get:Suppress("GetterSetterNames") var clip: Boolean = false

    /**
     * Configure the [RenderEffect] to apply to this [GraphicsLayer]. This will apply a visual
     * effect to the results of the [GraphicsLayer] before it is drawn. For example if [BlurEffect]
     * is provided, the contents will be drawn in a separate layer, then this layer will be blurred
     * when this [GraphicsLayer] is drawn.
     *
     * Note this parameter is only supported on Android 12 and above. Attempts to use this Modifier
     * on older Android versions will be ignored.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerRenderEffectSample
     */
    var renderEffect: RenderEffect? = null

    /**
     * Determines if this [GraphicsLayer] has been released. Any attempts to use a [GraphicsLayer]
     * after it has been released is an error.
     */
    var isReleased: Boolean = false
        private set

    /**
     * Constructs the display list of drawing commands into this layer that will be rendered when
     * this [GraphicsLayer] is drawn elsewhere with [drawLayer].
     *
     * @param density [Density] used to assist in conversions of density independent pixels to raw
     *   pixels to draw.
     * @param layoutDirection [LayoutDirection] of the layout being drawn in.
     * @param size [Size] of the [GraphicsLayer]
     * @param block lambda that is called to issue drawing commands on this [DrawScope]
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerTopLeftSample
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerBlendModeSample
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerTranslateSample
     */
    fun record(
        density: Density,
        layoutDirection: LayoutDirection,
        size: IntSize,
        block: DrawScope.() -> Unit,
    ) {
        this.size = size
        val image = MinecraftImageBitmap(size.width, size.height)
        val canvas = MinecraftCanvas(image)
        recordingCanvas = canvas
        canvasDrawScope.draw(
            density = density,
            layoutDirection = layoutDirection,
            canvas = canvas,
            size = Size(size.width.toFloat(), size.height.toFloat()),
            block = block,
        )
    }

    /**
     * Create an [ImageBitmap] with the contents of this [GraphicsLayer] instance. Note that
     * [GraphicsLayer.record] must be invoked first to record drawing operations before invoking
     * this method.
     *
     * @sample androidx.compose.ui.graphics.samples.GraphicsLayerToImageBitmap
     */
    suspend fun toImageBitmap(): ImageBitmap {
        val canvas = recordingCanvas
            ?: error("GraphicsLayer.record must be invoked before calling toImageBitmap")
        return canvas.image
            ?: error("GraphicsLayer has no backing image")
    }

    /**
     * Marks this [GraphicsLayer] as released. Any attempts to use a [GraphicsLayer]
     * after it has been released is an error.
     */
    fun release() {
        isReleased = true
        recordingCanvas = null
    }

    /** Draw the contents of this [GraphicsLayer] into the specified [Canvas] */
    internal fun draw(canvas: Canvas, parentLayer: GraphicsLayer?) {
        if (canvas !is MinecraftCanvas) {
            throw UnsupportedOperationException(
                "GraphicsLayer.draw 仅支持 MinecraftCanvas,实际: ${canvas::class.simpleName}"
            )
        }
        val recording = recordingCanvas ?: return
        // 阶段 C:应用图层变换后回放录制阶段记录的绘制命令。
        // 支持 translate/scale/rotationZ/alpha/clip/pivot(transformOrigin);
        // rotationX/rotationY(3D 透视)T.15 补齐:命中测试端早已用
        // prepareTransformationMatrix(含透视)计算,此处绘制端同语义实现。
        // 本平台 Canvas 变换为 post-concat(右乘),调用顺序即矩阵相乘顺序,
        // 对齐官方 Skia 绘制语义 T(topLeft+translation) * T(pivot) * R * S * T(-pivot),
        // 即 scale/rotationZ 绕 pivot 进行;pivot 未指定时默认图层中心。
        val pivotX = if (pivotOffset.isUnspecified) size.width / 2f else pivotOffset.x
        val pivotY = if (pivotOffset.isUnspecified) size.height / 2f else pivotOffset.y
        // T.15:rotationX/rotationY 非零 → 3D 透视路径(行主序 4x4,含透视分量)。
        // 3D 变换无法用画布 2D 矩阵表达,全部收进 layer3D 矩阵:
        // 渲染端 map3D 的链为「局部 → 命令矩阵 → layer3D → 屏幕」,
        // 因此 layer3D 必须包含完整「图层局部 → 屏幕」变换(含 topLeft+translation)。
        val has3D = !rotationX.isNearZero() || !rotationY.isNearZero()
        canvas.save()
        if (has3D) {
            // T.15 修复(旋转中心):layer3D **直接复用 prepareTransformationMatrix**
            // (与命中测试 GraphicsLayerOwnerLayer.updateMatrix 完全同一矩阵语义),
            // 保证「渲染的旋转中心 == 点击命中的区域」。此前手写构建顺序与官方
            // 不一致,导致渲染旋转中心偏移而命中正确(AGENTS.md T.13 同类问题)。
            // 官方构建(Matrices.kt):
            //   T(-pivot) * Rz·Ry·Rx·S * [P] * T(pivot+translation)
            // 再附加 topLeft(图层在父坐标系的位置,命中测试的坐标已含 topLeft,
            // 绘制端需单独平移)。
            val layer3D = Matrix().apply {
                prepareTransformationMatrix(
                    matrix = this,
                    pivotX = pivotX,
                    pivotY = pivotY,
                    translationX = translationX,
                    translationY = translationY,
                    rotationX = rotationX,
                    rotationY = rotationY,
                    rotationZ = rotationZ,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    cameraDistance = cameraDistance,
                )
                // 附加 topLeft:图层位置(与 2D 路径 canvas.translate(topLeft) 对齐)
                translate(topLeft.x.toFloat(), topLeft.y.toFloat())
            }
            // T.15 修复:父画布矩阵(场景变换,列主序 2D)必须参与合成。
            // 2D 路径经 replayFrom 的 concat 叠加;3D 路径的 map3D 链是
            // 「局部 → 命令矩阵 → layer3D → 屏幕」,因此把父画布矩阵行主序化
            // 右乘进 layer3D(点先图层变换,再场景变换)。
            val parent = canvas.currentMatrix
            if (!parent.isIdentity()) {
                val parentRow = Matrix().apply {
                    // 列主序 2D values:[m00,m10,m01,m11,m20,m21](0,1,4,5,12,13)
                    // → 行主序 4x4 values:[m00,m01,m10,m11,1,m20,m21,1](0,1,4,5,10,12,13,15)
                    values[0] = parent.values[0]
                    values[1] = parent.values[4]
                    values[4] = parent.values[1]
                    values[5] = parent.values[5]
                    values[10] = 1f
                    values[12] = parent.values[12]
                    values[13] = parent.values[13]
                    values[15] = 1f
                }
                layer3D.timesAssign(parentRow)
            }
            // 3D 下 clip:渲染端 render3D 忽略 scissor(透视四边形无法轴对齐裁剪),
            // 此处不设画布裁剪;记录命令自带的 clip 在渲染端同样不使用。
            drawShadow(canvas)
            // T.15 修复(文本近似缩放):文本的 2D 压缩**不能**取 layer3D 的 2x2
            // 对角(layer3D[0]/layer3D[5])—— 该对角被「透视列 × 平移」耦合污染
            // (prepareTransformationMatrix 的 T(p+t) 右乘 + 嵌套透传 parentRow
            // 右乘都会把第 3 列的透视分量混入 2x2),污染量与方块屏幕位置相关:
            // X 方块(屏幕左侧)污染小 → 文本"看起来对";Y 方块(更右侧)污染大
            // → rotationY=45° 时 m00=1.10(反向放大)、60° 时 0.98(几乎不压缩)、
            // -45° 时 0.31(严重压缩),均与 cosθ(0.707/0.5/0.707)明显不符。
            // 文本近似的正确缩放 = 绕各轴的 cos(角度) × scale:
            //   rotationX → y 方向压缩 cos(rotationX) × scaleY
            //   rotationY → x 方向压缩 cos(rotationY) × scaleX
            // 位置仍由 with3D 的 layer3D 透视映射计算(贴住矩形),不受影响。
            val degToRad = (kotlin.math.PI / 180.0).toFloat()
            val textScaleX = scaleX * kotlin.math.cos(rotationY * degToRad)
            val textScaleY = scaleY * kotlin.math.cos(rotationX * degToRad)
            canvas.replayFrom3D(recording, layer3D.values, textScaleX, textScaleY, alphaMultiplier = alpha)
        } else {
            canvas.translate(topLeft.x.toFloat() + translationX, topLeft.y.toFloat() + translationY)
            canvas.translate(pivotX, pivotY)
            canvas.rotate(rotationZ)
            canvas.scale(scaleX, scaleY)
            canvas.translate(-pivotX, -pivotY)
            if (clip) {
                // 平台适配点(T.9 修复):translate 之后画布已处于图层局部坐标系,
                // 裁剪矩形必须是局部 (0, 0, size);此前误用 topLeft 绝对坐标,
                // 导致 clipToBounds 图层(如 BasicTextField 文本/光标)整体被裁剪消失。
                canvas.clipRect(
                    0f,
                    0f,
                    size.width.toFloat(),
                    size.height.toFloat(),
                )
            }
            // 平台适配点(T.14):阴影 —— 基于 outline 的多层伪模糊(无离屏/无 Skia 模糊)。
            // 在内容之前绘制,随图层变换;偏移向下,内层深外层浅。
            // Path outline(Outline.Generic)阴影后续阶段补齐。
            drawShadow(canvas)
            canvas.replayFrom(recording, alphaMultiplier = alpha)
        }
        canvas.restore()
    }

    /**
     * 绘制软阴影(T.14,CPU 离屏真模糊 + 方向性投影)。
     * 记录一条 [DrawShadowCommand]:内容矩形 + 扩散距离 + 投影偏移 + 圆角半径。
     * 投影偏移 = 光源反方向 × elevation × 0.5(光源方向默认右上角,
     * 经 [moe.forpleuvoir.compose_minecraft.platform.LocalShadowLight]
     * CompositionLocal 可配置,由业务方在 graphicsLayer block 赋值)。
     * Path outline 阴影后续阶段补齐。
     */
    private fun drawShadow(canvas: MinecraftCanvas) {
        val elevation = shadowElevation
        if (elevation <= 0f) return
        val outline = layerOutline ?: return
        // 轮廓 bounds(spot 阴影偏移的 center 基准,Skia:offset = -(lightXY - center) * zRatio)
        val bounds = when (outline) {
            is Outline.Rectangle -> outline.rect
            is Outline.Rounded -> Rect(outline.roundRect.left, outline.roundRect.top, outline.roundRect.right, outline.roundRect.bottom)
            is Outline.Generic -> outline.path.getBounds()
        }
        // Skia SkShadowUtils 公式(完全参照官方):
        //   zRatio = elevation / (lightHeight - elevation),钳制 [0, 0.95]
        //   lightXY = 归一化光源方向 × lightHeight(600),光源在 600 高度平面
        //   spot offset = -(lightXY - center) * zRatio(阴影朝光源反方向投影)
        val zRatio = (elevation / (600f - elevation)).coerceIn(0f, 0.95f)
        val cx = (bounds.left + bounds.right) * 0.5f
        val cy = (bounds.top + bounds.bottom) * 0.5f
        val lx = shadowLightDirectionX
        val ly = shadowLightDirectionY
        val len = kotlin.math.sqrt(lx * lx + ly * ly)
        val offsetX = if (len > 0f) -(lx / len * 600f - cx) * zRatio else -(-cx) * zRatio
        val offsetY = if (len > 0f) -(ly / len * 600f - cy) * zRatio else -(-cy) * zRatio
        when (outline) {
            is Outline.Rectangle -> {
                val r = outline.rect
                canvas.recordShadow(
                    r.left, r.top, r.right, r.bottom, elevation, offsetX, offsetY, 0f,
                )
            }
            is Outline.Rounded -> {
                // 圆角矩形:阴影本体 = 同圆角的圆角矩形投影(距离场带圆角)
                val rr = outline.roundRect
                canvas.recordShadow(
                    rr.left, rr.top, rr.right, rr.bottom, elevation, offsetX, offsetY,
                    rr.topLeftCornerRadius.x,
                )
            }
            is Outline.Generic -> {
                // Path 阴影:阴影形状 = 真实 Path 轮廓(渲染端按 Path 距离场生成)。
                // 注意:此分支必须处理而非 return 跳过,否则 GenericShape 阴影全部丢失。
                val path = outline.path
                if (path is MinecraftPath) {
                    canvas.recordShadow(
                        0f, 0f, 0f, 0f, elevation, offsetX, offsetY, 0f,
                        pathSegments = path.segments(),
                    )
                }
            }
        }
    }

    private companion object {
        /** 阴影最内层 alpha 占阴影色的比例(渐变保底方案用) */
        const val SHADOW_MAX_ALPHA_FACTOR = 0.5f

        /** 渐变中段 alpha 占最内层的比例(渐变保底方案用) */
        const val SHADOW_MID_ALPHA_FACTOR = 0.35f

        /** 与官方 Matrices.kt 一致的近零判定(rotationX/rotationY 3D 门限) */
        private const val NON_ZERO_EPSILON = 0.001f
    }

    private fun Float.isNearZero(): Boolean = kotlin.math.abs(this) <= NON_ZERO_EPSILON
}

/**
 * Configures an outline for this [GraphicsLayer] based on the provided [Outline] object.
 *
 * When [GraphicsLayer.shadowElevation] is non-zero a shadow is produced using a provided [Outline].
 * Additionally if [GraphicsLayer.clip] is true, the contents of this [GraphicsLayer] will be
 * clipped to this geometry.
 *
 * @param outline an [Outline] to apply for the layer.
 */
fun GraphicsLayer.setOutline(outline: Outline) {
    when (outline) {
        is Outline.Rectangle ->
            setRectOutline(
                Offset(outline.rect.left, outline.rect.top),
                Size(outline.rect.width, outline.rect.height),
            )
        is Outline.Generic -> setPathOutline(outline.path)
        is Outline.Rounded -> {
            // If the rounded rect has a path, then the corner radii are not the same across
            // each of the corners, so we set the outline as a Path.
            // If there is no path available, then the corner radii are identical so we can
            // use setRoundRectOutline directly.
            if (outline.roundRectPath != null) {
                setPathOutline(outline.roundRectPath)
            } else {
                val rr = outline.roundRect
                setRoundRectOutline(
                    Offset(rr.left, rr.top),
                    Size(rr.width, rr.height),
                    rr.bottomLeftCornerRadius.x,
                )
            }
        }
    }
}
