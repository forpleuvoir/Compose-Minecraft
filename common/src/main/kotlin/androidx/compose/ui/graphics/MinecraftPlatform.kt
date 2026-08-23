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

package androidx.compose.ui.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import moe.forpleuvoir.compose_minecraft.platform.render.text.TextRenderBackend
import moe.forpleuvoir.compose_minecraft.platform.render.text.FontResolver
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.toPaintSnapshot
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Minecraft Compose UI Graphics 平台实现(第一版)
//
// 设计说明:
// - 不依赖 Skia/Skiko,所有类型为纯 JVM 具体实现。
// - Canvas 采用"命令记录"模式:第一版仅记录绘制命令,后续阶段由
//   MinecraftRenderContext 回放到 Minecraft 当前 GUI 渲染管线。
// - 第一版不支持的功能抛出带 API 名称的 UnsupportedOperationException。
// ─────────────────────────────────────────────────────────────────────────────

/** Minecraft 平台 Paint 实现(纯数据) */
class MinecraftPaint(
    override var color: Color = Color.Black,
    override var alpha: Float = 1f,
    override var colorFilter: ColorFilter? = null,
    override var blendMode: BlendMode = BlendMode.SrcOver,
    override var style: PaintingStyle = PaintingStyle.Fill,
    override var strokeWidth: Float = 0f,
    override var strokeCap: StrokeCap = StrokeCap.Butt,
    override var strokeJoin: StrokeJoin = StrokeJoin.Miter,
    override var strokeMiterLimit: Float = 4f,
    override var shader: Shader? = null,
    override var pathEffect: PathEffect? = null,
    override var isAntiAlias: Boolean = true,
    // 平台适配点(T.16):官方默认 Low(线性)。本平台默认 None(最近邻,MC 像素风);configurePaint 总会按调用参数覆盖
    override var filterQuality: FilterQuality = FilterQuality.None,
) : Paint {
    // T.21:内部通道 —— 图层级/命令级 NativeColorFilter 注入(compose colorFilter 是
    // public ColorFilter?,而渲染端需要 NativeColorFilter?;回放时经此字段透传,
    // snapshot() 优先取它,见 Paint.snapshot())。不能放构造参数(public 构造
    // 暴露 internal 类型)。
    internal var nativeColorFilter: NativeColorFilter? = null

    override fun equals(other: Any?): Boolean = other is Paint && hashCode() == other.hashCode()

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + alpha.hashCode()
        result = 31 * result + colorFilter.hashCode()
        result = 31 * result + blendMode.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + strokeWidth.hashCode()
        return result
    }

    override fun toString(): String =
        "MinecraftPaint(color=$color, alpha=$alpha, style=$style)"
}

/** Minecraft 平台 Path 实现(命令记录式) */
internal class MinecraftPath(
    override var fillType: PathFillType = PathFillType.NonZero,
) : Path {
    private val commands = ArrayList<PathCommand>()

    /**
     * 从扁平化段数据重建路径(供 [MinecraftCanvas.replayFrom] 回放命令时使用)。
     */
    internal constructor(segments: List<PathSegmentData>) : this() {
        for (segment in segments) {
            when (segment.type) {
                PathSegmentType.Move      -> moveTo(segment.points[0], segment.points[1])
                PathSegmentType.Line      -> lineTo(segment.points[0], segment.points[1])
                PathSegmentType.Quadratic -> {
                    quadraticTo(
                        segment.points[0], segment.points[1],
                        segment.points[2], segment.points[3]
                    )
                }

                PathSegmentType.Cubic     -> cubicTo(
                    segment.points[0], segment.points[1],
                    segment.points[2], segment.points[3],
                    segment.points[4], segment.points[5],
                )

                PathSegmentType.Close     -> close()
            }
        }
    }

    private sealed class PathCommand {
        class MoveTo(val x: Float, val y: Float) : PathCommand()
        class LineTo(val x: Float, val y: Float) : PathCommand()
        class QuadTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : PathCommand()
        class CubicTo(
            val x1: Float, val y1: Float,
            val x2: Float, val y2: Float,
            val x3: Float, val y3: Float,
        ) : PathCommand()

        class ArcTo(
            val left: Float, val top: Float, val right: Float, val bottom: Float,
            val startAngleDegrees: Float, val sweepAngleDegrees: Float, val forceMoveTo: Boolean,
        ) : PathCommand()

        data object Close : PathCommand()
    }

    override val isConvex: Boolean get() = false

    override val isEmpty: Boolean get() = commands.isEmpty()

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var startX: Float = 0f
    private var startY: Float = 0f

    override fun moveTo(x: Float, y: Float) {
        commands.add(PathCommand.MoveTo(x, y))
        lastX = x
        lastY = y
        startX = x
        startY = y
    }

    override fun relativeMoveTo(dx: Float, dy: Float) = moveTo(lastX + dx, lastY + dy)

    override fun lineTo(x: Float, y: Float) {
        commands.add(PathCommand.LineTo(x, y))
        lastX = x
        lastY = y
    }

    override fun relativeLineTo(dx: Float, dy: Float) = lineTo(lastX + dx, lastY + dy)

    @Deprecated("Use quadraticTo() for consistency with cubicTo()", replaceWith = ReplaceWith("quadraticTo(x1, y1, x2, y2)"), level = DeprecationLevel.WARNING)
    override fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        commands.add(PathCommand.QuadTo(x1, y1, x2, y2))
        lastX = x2
        lastY = y2
    }

    @Deprecated(
        "Use relativeQuadraticTo() for consistency with relativeCubicTo()",
        replaceWith = ReplaceWith("relativeQuadraticTo(dx1, dy1, dx2, dy2)"),
        level = DeprecationLevel.WARNING
    )
    override fun relativeQuadraticBezierTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float) {
        quadraticTo(lastX + dx1, lastY + dy1, lastX + dx2, lastY + dy2)
    }

    override fun cubicTo(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
    ) {
        commands.add(PathCommand.CubicTo(x1, y1, x2, y2, x3, y3))
        lastX = x3
        lastY = y3
    }

    override fun relativeCubicTo(
        dx1: Float, dy1: Float,
        dx2: Float, dy2: Float,
        dx3: Float, dy3: Float,
    ) = cubicTo(lastX + dx1, lastY + dy1, lastX + dx2, lastY + dy2, lastX + dx3, lastY + dy3)

    override fun arcTo(
        rect: Rect,
        startAngleDegrees: Float,
        sweepAngleDegrees: Float,
        forceMoveTo: Boolean,
    ) {
        if (forceMoveTo) {
            val cx = (rect.left + rect.right) / 2f
            val cy = (rect.top + rect.bottom) / 2f
            val rx = (rect.right - rect.left) / 2f
            val ry = (rect.bottom - rect.top) / 2f
            val startRad = startAngleDegrees * PI.toFloat() / 180f
            moveTo(cx + rx * cos(startRad), cy + ry * sin(startRad))
        }
        commands.add(
            PathCommand.ArcTo(
                rect.left, rect.top, rect.right, rect.bottom,
                startAngleDegrees, sweepAngleDegrees, false,
            )
        )
    }

    @Deprecated("Prefer usage of addRect() with a winding direction", replaceWith = ReplaceWith("addRect(rect)"), level = DeprecationLevel.HIDDEN)
    override fun addRect(rect: Rect) = addRect(rect, Path.Direction.CounterClockwise)

    override fun addRect(rect: Rect, direction: Path.Direction) {
        when (direction) {
            Path.Direction.CounterClockwise -> {
                moveTo(rect.left, rect.top)
                lineTo(rect.right, rect.top)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.left, rect.bottom)
            }

            Path.Direction.Clockwise        -> {
                moveTo(rect.left, rect.top)
                lineTo(rect.left, rect.bottom)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.right, rect.top)
            }
        }
        close()
    }

    @Deprecated("Prefer usage of addOval() with a winding direction", replaceWith = ReplaceWith("addOval(oval)"), level = DeprecationLevel.HIDDEN)
    override fun addOval(oval: Rect) = addOval(oval, Path.Direction.CounterClockwise)

    override fun addOval(oval: Rect, direction: Path.Direction) {
        val cx = (oval.left + oval.right) / 2f
        val cy = (oval.top + oval.bottom) / 2f
        val rx = (oval.right - oval.left) / 2f
        val ry = (oval.bottom - oval.top) / 2f
        val steps = 32
        var first = true
        for (i in 0..steps) {
            val angle = i * 2.0 * PI / steps
            val x = cx + rx * cos(angle)
            val y = cy + ry * sin(angle)
            if (first) {
                moveTo(x.toFloat(), y.toFloat())
                first = false
            } else {
                lineTo(x.toFloat(), y.toFloat())
            }
        }
        close()
    }

    @Deprecated(
        "Prefer usage of addRoundRect() with a winding direction",
        replaceWith = ReplaceWith("addRoundRect(roundRect)"),
        level = DeprecationLevel.HIDDEN
    )
    override fun addRoundRect(roundRect: RoundRect) =
        addRoundRect(roundRect, Path.Direction.CounterClockwise)

    override fun addRoundRect(roundRect: RoundRect, direction: Path.Direction) {
        val left = roundRect.left
        val top = roundRect.top
        val right = roundRect.right
        val bottom = roundRect.bottom
        val rx = roundRect.bottomLeftCornerRadius.x.coerceIn(0f, (right - left) / 2f)
        val ry = roundRect.bottomLeftCornerRadius.y.coerceIn(0f, (bottom - top) / 2f)
        moveTo(left + rx, top)
        lineTo(right - rx, top)
        quadraticTo(right, top, right, top + ry)
        lineTo(right, bottom - ry)
        quadraticTo(right, bottom, right - rx, bottom)
        lineTo(left + rx, bottom)
        quadraticTo(left, bottom, left, bottom - ry)
        lineTo(left, top + ry)
        quadraticTo(left, top, left + rx, top)
        close()
    }

    override fun addArcRad(
        oval: Rect, startAngleRadians: Float, sweepAngleRadians: Float,
    ) = addArc(
        oval, Math.toDegrees(startAngleRadians.toDouble()).toFloat(),
        Math.toDegrees(sweepAngleRadians.toDouble()).toFloat()
    )

    override fun addArc(oval: Rect, startAngleDegrees: Float, sweepAngleDegrees: Float) {
        arcTo(oval, startAngleDegrees, sweepAngleDegrees, true)
    }

    override fun addPath(path: Path, offset: Offset) {
        if (path !is MinecraftPath) {
            throw UnsupportedOperationException("addPath only supports MinecraftPath, actual: ${path::class.simpleName}")
        }
        for (command in path.commands) {
            when (command) {
                is PathCommand.MoveTo  -> moveTo(command.x + offset.x, command.y + offset.y)
                is PathCommand.LineTo  -> lineTo(command.x + offset.x, command.y + offset.y)
                is PathCommand.QuadTo  -> {
                    quadraticTo(command.x1 + offset.x, command.y1 + offset.y, command.x2 + offset.x, command.y2 + offset.y)
                }

                is PathCommand.CubicTo ->
                    cubicTo(
                        command.x1 + offset.x,
                        command.y1 + offset.y,
                        command.x2 + offset.x,
                        command.y2 + offset.y,
                        command.x3 + offset.x,
                        command.y3 + offset.y
                    )

                is PathCommand.ArcTo   -> arcTo(
                    Rect(
                        command.left + offset.x,
                        command.top + offset.y,
                        command.right + offset.x,
                        command.bottom + offset.y,
                    ),
                    command.startAngleDegrees,
                    command.sweepAngleDegrees,
                    false,
                )

                is PathCommand.Close   -> close()
            }
        }
    }

    override fun close() {
        commands.add(PathCommand.Close)
        lastX = startX
        lastY = startY
    }

    override fun reset() {
        commands.clear()
        lastX = 0f
        lastY = 0f
        startX = 0f
        startY = 0f
    }

    override fun translate(offset: Offset) {
        for (i in commands.indices) {
            when (val c = commands[i]) {
                is PathCommand.MoveTo  -> commands[i] = PathCommand.MoveTo(c.x + offset.x, c.y + offset.y)
                is PathCommand.LineTo  -> commands[i] = PathCommand.LineTo(c.x + offset.x, c.y + offset.y)
                is PathCommand.QuadTo  -> commands[i] =
                    PathCommand.QuadTo(c.x1 + offset.x, c.y1 + offset.y, c.x2 + offset.x, c.y2 + offset.y)

                is PathCommand.CubicTo -> commands[i] = PathCommand.CubicTo(
                    c.x1 + offset.x, c.y1 + offset.y,
                    c.x2 + offset.x, c.y2 + offset.y,
                    c.x3 + offset.x, c.y3 + offset.y,
                )

                else                   -> Unit
            }
        }
    }

    override fun getBounds(): Rect {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasPoint = false
        for (command in commands) {
            val pts = when (command) {
                is PathCommand.MoveTo  -> listOf(command.x to command.y)
                is PathCommand.LineTo  -> listOf(command.x to command.y)
                is PathCommand.QuadTo  -> listOf(command.x1 to command.y1, command.x2 to command.y2)
                is PathCommand.CubicTo -> listOf(command.x1 to command.y1, command.x2 to command.y2, command.x3 to command.y3)
                else                   -> emptyList()
            }
            for ((x, y) in pts) {
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                hasPoint = true
            }
        }
        return if (hasPoint) Rect(minX, minY, maxX, maxY) else Rect.Zero
    }

    override fun op(path1: Path, path2: Path, operation: PathOperation): Boolean {
        throw UnsupportedOperationException("Path.op($operation) is not supported in v1")
    }

    /** 供 [PathIterator] 遍历的扁平化点序列 */
    internal fun segments(): List<PathSegmentData> {
        val result = ArrayList<PathSegmentData>()
        for (command in commands) {
            when (command) {
                is PathCommand.MoveTo  -> result.add(PathSegmentData(Move, floatArrayOf(command.x, command.y)))
                is PathCommand.LineTo  -> result.add(PathSegmentData(Line, floatArrayOf(command.x, command.y)))
                is PathCommand.QuadTo  -> result.add(
                    PathSegmentData(Quadratic, floatArrayOf(command.x1, command.y1, command.x2, command.y2))
                )

                is PathCommand.CubicTo -> result.add(
                    PathSegmentData(Cubic, floatArrayOf(command.x1, command.y1, command.x2, command.y2, command.x3, command.y3))
                )

                is PathCommand.ArcTo   -> {
                    // 圆弧近似为线段
                    val cx = (command.left + command.right) / 2f
                    val cy = (command.top + command.bottom) / 2f
                    val rx = (command.right - command.left) / 2f
                    val ry = (command.bottom - command.top) / 2f
                    val startRad = command.startAngleDegrees * PI.toFloat() / 180f
                    val sweepRad = command.sweepAngleDegrees * PI.toFloat() / 180f
                    val steps = maxOf(2, (abs(sweepRad) / (PI / 8.0)).toInt())
                    for (i in 1..steps) {
                        val angle = startRad + sweepRad * i / steps
                        val px = cx + rx * cos(angle)
                        val py = cy + ry * sin(angle)
                        result.add(PathSegmentData(Line, floatArrayOf(px, py)))
                    }
                }

                is PathCommand.Close   -> result.add(PathSegmentData(Close, floatArrayOf()))
            }
        }
        return result
    }

    private fun abs(v: Float): Float = if (v < 0) -v else v

    internal class PathSegmentData(val type: PathSegmentType, val points: FloatArray)

    internal enum class PathSegmentType { Move, Line, Quadratic, Cubic, Close }
}

/** Minecraft 平台 PathIterator 实现 */
internal class MinecraftPathIterator(
    override val path: MinecraftPath,
) : PathIterator {
    private val segments: List<MinecraftPath.PathSegmentData> = path.segments()
    private var index = 0

    override val conicEvaluation: PathIterator.ConicEvaluation = PathIterator.ConicEvaluation.AsQuadratics

    override val tolerance: Float = 0.25f

    override fun calculateSize(includeConvertedConics: Boolean): Int = segments.size

    override fun hasNext(): Boolean = index < segments.size

    override fun next(): PathSegment {
        val segment = segments[index]
        index++
        val (type, weight) = when (segment.type) {
            MinecraftPath.PathSegmentType.Move      -> PathSegment.Type.Move to 0f
            MinecraftPath.PathSegmentType.Line      -> PathSegment.Type.Line to 0f
            MinecraftPath.PathSegmentType.Quadratic -> PathSegment.Type.Quadratic to 0f
            MinecraftPath.PathSegmentType.Cubic     -> PathSegment.Type.Cubic to 0f
            MinecraftPath.PathSegmentType.Close     -> PathSegment.Type.Close to 0f
        }
        return PathSegment(type, segment.points, weight)
    }

    override fun next(outPoints: FloatArray, offset: Int): PathSegment.Type {
        val segment = segments[index]
        index++
        for (i in segment.points.indices) {
            outPoints[offset + i] = segment.points[i]
        }
        return when (segment.type) {
            MinecraftPath.PathSegmentType.Move      -> PathSegment.Type.Move
            MinecraftPath.PathSegmentType.Line      -> PathSegment.Type.Line
            MinecraftPath.PathSegmentType.Quadratic -> PathSegment.Type.Quadratic
            MinecraftPath.PathSegmentType.Cubic     -> PathSegment.Type.Cubic
            MinecraftPath.PathSegmentType.Close     -> PathSegment.Type.Close
        }
    }
}

/** Minecraft 平台 PathMeasure 实现 */
internal class MinecraftPathMeasure : PathMeasure {
    private var path: MinecraftPath? = null
    private var cachedLength: Float = 0f

    override val length: Float get() = cachedLength

    override fun getSegment(
        startDistance: Float,
        stopDistance: Float,
        destination: Path,
        startWithMoveTo: Boolean,
    ): Boolean {
        throw UnsupportedOperationException("PathMeasure.getSegment is not supported in v1")
    }

    override fun setPath(path: Path?, forceClosed: Boolean) {
        this.path = path as? MinecraftPath
        cachedLength = calculateLength()
    }

    override fun getPosition(distance: Float): Offset {
        throw UnsupportedOperationException("PathMeasure.getPosition is not supported in v1")
    }

    override fun getTangent(distance: Float): Offset {
        throw UnsupportedOperationException("PathMeasure.getTangent is not supported in v1")
    }

    private fun calculateLength(): Float {
        val p = path ?: return 0f
        var total = 0f
        var lastX = 0f
        var lastY = 0f
        var hasLast = false
        for (segment in p.segments()) {
            if (segment.points.size >= 2) {
                val x = segment.points[segment.points.size - 2]
                val y = segment.points[segment.points.size - 1]
                if (hasLast) {
                    total += kotlin.math.sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY))
                }
                lastX = x
                lastY = y
                hasLast = true
            }
        }
        return total
    }
}

/**
 * Minecraft 平台 ImageBitmap(CPU 像素缓冲,0xAARRGGBB)。
 *
 * 平台适配点(T.16):绘制端由 [moe.forpleuvoir.compose_minecraft.platform.render.MinecraftImageTextureCache]
 * 在渲染线程按需上传为 GpuTexture(按位图身份缓存);本类只持 CPU 像素。
 */
internal class MinecraftImageBitmap(
    override val width: Int,
    override val height: Int,
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888,
    override val hasAlpha: Boolean = true,
    override val colorSpace: ColorSpace = ColorSpaces.Srgb,
    buffer: IntArray? = null,
) : ImageBitmap {
    val buffer: IntArray = buffer ?: IntArray(width * height)

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val srcIndex = (startY + y) * this.width + (startX + x)
                val dstIndex = bufferOffset + y * stride + x
                if (srcIndex in this.buffer.indices && dstIndex in buffer.indices) {
                    buffer[dstIndex] = this.buffer[srcIndex]
                }
            }
        }
    }

    /** 平台适配点(T.16):上传在渲染线程按需执行(见 MinecraftImageTextureCache),此处无操作 */
    override fun prepareToDraw() = Unit
}

/** Minecraft 平台 NativeColorFilter(记录色值/矩阵/相加色,绘制阶段应用) */
internal class NativeColorFilter internal constructor(
    /** 调制色(BlendModeColorFilter 的 tint / LightingColorFilter 的 multiply) */
    val color: Color? = null,
    val colorMatrix: ColorMatrix? = null,
    val blendMode: BlendMode = BlendMode.SrcIn,
    /** LightingColorFilter 的 add 分量(T.21):输出 = src × multiply + add */
    val add: Color? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is NativeColorFilter && color == other.color && colorMatrix == other.colorMatrix && add == other.add

    override fun hashCode(): Int = (color.hashCode() * 31 + colorMatrix.hashCode()) * 31 + add.hashCode()
}

/** Minecraft 平台 Canvas:命令记录式,后续阶段由 MinecraftRenderContext 回放 */
internal class MinecraftCanvas internal constructor(
    internal val image: ImageBitmap? = null,
) : Canvas {

    // ─────────────────────────────────────────────────────────────────────────
    // 绘制命令模型
    //
    // 每个命令记录绘制参数 + 当时的矩阵快照与裁剪快照;
    // MinecraftRenderContext(阶段 C)逐条回放到 Minecraft 的 GuiRenderState。
    // ─────────────────────────────────────────────────────────────────────────

    /** Paint 最小快照(回放所需属性) */
    class PaintSnapshot(
        val color: Color,
        val alpha: Float,
        val style: PaintingStyle,
        val strokeWidth: Float,
        val strokeCap: StrokeCap,
        /** 图片采样质量(T.16):[FilterQuality.None] → 最近邻(平台默认),[FilterQuality.Low] → 双线性 */
        val filterQuality: FilterQuality = FilterQuality.None,
        /**
         * 颜色滤镜(T.21):渲染端对最终色应用。
         * [NativeColorFilter.colorMatrix] → 颜色矩阵;[color] 为调制色
         * (BlendModeColorFilter 的 tint / LightingColorFilter 的 multiply),
         * [add] 为 LightingColorFilter 的 add 分量。
         */
        val colorFilter: NativeColorFilter? = null,
        /** 混合模式(T.21,draw 级):渲染端按 BlendMode 选择 blend;SrcOver = 默认 alpha 合成 */
        val blendMode: BlendMode = BlendMode.SrcOver,
        /** 渐变着色器(平台适配点):LinearGradient/RadialGradient/SweepGradient,非 null 时覆盖 color */
        val shader: Shader? = null,
    )

    /** 绘制命令基类 */
    interface DrawCommand {
        /** 记录时的矩阵快照(4x4,列主序,同 [Matrix.values]) */
        val matrix: FloatArray

        /** 记录时的裁剪矩形(屏幕坐标,null 表示不裁剪) */
        val clip: Rect?

        /** 绘制参数快照;文本命令为 null(文本使用独立字段) */
        val paint: PaintSnapshot?

        /**
         * 图层级 3D 变换矩阵(行主序 4x4,含透视分量;null = 普通 2D 命令)。
         *
         * 平台适配点(T.15):GraphicsLayer 的 rotationX/rotationY(3D 透视)无法用
         * 2D 画布矩阵表达,由 [GraphicsLayer.draw] 3D 分支构建行主序 4x4 矩阵后
         * 经 [MinecraftCanvas.replayFrom3D] 附加到纯色几何命令;
         * 渲染端 [moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderContext]
         * 检测到非 null 时走 CPU 顶点透视变换路径。
         * 文本/阴影/渐变命令在 [MinecraftCanvas.with3D] 中已降级为 2D 仿射近似,
         * 本字段恒为 null。
         */
        val layer3D: FloatArray? get() = null
    }

    class DrawRectCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        override val layer3D: FloatArray? = null,
    ) : DrawCommand

    class DrawRoundRectCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radiusX: Float,
        val radiusY: Float,
        override val layer3D: FloatArray? = null,
    ) : DrawCommand

    class DrawOvalCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        override val layer3D: FloatArray? = null,
    ) : DrawCommand

    class DrawCircleCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        override val layer3D: FloatArray? = null,
    ) : DrawCommand

    class DrawArcCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val startAngle: Float,
        val sweepAngle: Float,
        val useCenter: Boolean,
        override val layer3D: FloatArray? = null,
    ) : DrawCommand

    class DrawLineCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val p1x: Float,
        val p1y: Float,
        val p2x: Float,
        val p2y: Float,
        override val layer3D: FloatArray? = null,
    ) : DrawCommand

    class DrawPathCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        /** 路径的扁平化段数据(与 [MinecraftPath.segments] 一致) */
        val segments: List<MinecraftPath.PathSegmentData>,
        override val layer3D: FloatArray? = null,
    ) : DrawCommand

    class DrawPointsCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val pointMode: PointMode,
        val points: List<Offset>,
        override val layer3D: FloatArray? = null,
    ) : DrawCommand

    /**
     * 顶点网格绘制命令(T.23):`Canvas.drawVertices`。
     *
     * - [vertexMode]:Triangles / TriangleStrip / TriangleFan;
     * - [positions]:交错 x,y 平铺(顶点数 = size / 2);
     * - [colors]:每顶点 0xAARRGGBB,与顶点数等长(渲染端乘 Paint.alpha、
     *   应用 colorFilter 后逐顶点提交 → GPU 顶点色插值渐变);
     * - [indices]:可选,非空时按 [vertexMode] 解释索引(每 3 个一个三角形 /
     *   strip / fan),空时按顶点顺序展开;
     * - 纹理坐标忽略(平台 GUI shader 无纹理采样,与图片管线无关);
     * - [layer3D]:3D 图层下 CPU 透视变换(逐顶点),同纯色几何。
     */
    class DrawVerticesCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val vertexMode: VertexMode,
        val positions: FloatArray,
        val colors: IntArray,
        val indices: ShortArray,
        override val layer3D: FloatArray? = null,
    ) : DrawCommand

    class DrawImageRectCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val image: MinecraftImageBitmap,
        val srcOffsetX: Int,
        val srcOffsetY: Int,
        val srcWidth: Int,
        val srcHeight: Int,
        val dstOffsetX: Int,
        val dstOffsetY: Int,
        val dstWidth: Int,
        val dstHeight: Int,
    ) : DrawCommand

    /** 文本绘制命令(由 [MinecraftParagraph] 记录)。平台适配点(T.1):携带 MC 样式快照 */
    class DrawTextCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        val text: String,
        val x: Float,
        val y: Float,
        val style: Style,
        /** 文本整体透明度 0..1(图层级 alpha 叠加,阶段 C 经颜色 alpha 通道应用) */
        val alpha: Float = 1f,
        /** 渐变着色器(平台适配点):非 null 时文本颜色由渐变采样决定,覆盖 style 色 */
        val shader: Shader? = null,
        /**
         * 渲染后端定向选择(T.TT,设计文档 §3.1.1):[TextRenderBackend.VANILLA]
         * 强制原版渲染;[TextRenderBackend.DEFAULT] 跟随全局开关分流。
         * 组合期由文本组件随 scale 一起捕获盖章(P2 接入 LocalTextRenderBackend)。
         */
        val backend: TextRenderBackend = TextRenderBackend.DEFAULT,
    ) : DrawCommand {
        override val paint: PaintSnapshot? = null
    }

    /**
     * 渐变矩形命令(T.14 阴影):顶部/底部双色垂直渐变,渲染端经 MC 原生
     * ColoredRectangleRenderState(GUI pipeline)提交 —— 与 blit 矩形同排序组,
     * 阴影先记录先绘制,层级正确。
     */
    class DrawGradientRectCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** 0xAARRGGBB,顶部(y0)颜色 */
        val topColorArgb: Int,
        /** 0xAARRGGBB,底部(y1)颜色 */
        val bottomColorArgb: Int,
    ) : DrawCommand {
        override val paint: PaintSnapshot? = null
    }

    /**
     * 阴影命令(T.14 CPU 离屏真模糊):携带内容矩形(局部)与扩散距离,
     * 渲染端经 [moe.forpleuvoir.compose_minecraft.platform.render.MinecraftShadowRenderer] 提交。
     * [offsetX]/[offsetY] 为投影偏移(光源反方向,局部单位):阴影本体 =
     * 内容矩形平移该偏移后的矩形。
     */
    class DrawShadowCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** 扩散距离(局部单位) */
        val elevation: Float,
        /** 投影偏移 x(局部单位,光源反方向) */
        val offsetX: Float,
        /** 投影偏移 y(局部单位,光源反方向) */
        val offsetY: Float,
        /** 圆角半径(局部单位,0 = 直角矩形) */
        val cornerRadius: Float,
        /** Path 轮廓段(非空 = Path 阴影,left/top/right/bottom 忽略) */
        val pathSegments: List<MinecraftPath.PathSegmentData>? = null,
        /** ambient 阴影颜色(0xAARRGGBB,T.18;默认黑 = 官方默认) */
        val ambientColorArgb: Int = 0xFF000000.toInt(),
        /** spot 阴影颜色(0xAARRGGBB,T.18;默认黑 = 官方默认) */
        val spotColorArgb: Int = 0xFF000000.toInt(),
    ) : DrawCommand {
        override val paint: PaintSnapshot? = null
    }

    internal val drawCommands = ArrayList<DrawCommand>()

    /**
     * 文本渲染后端覆盖(P2 LocalTextRenderBackend):绘制节点在调用
     * paragraph.paint 前设置、finally 恢复;null = 跟随全局开关。
     * recordTextDraw 落章时读取,随命令进入分流层。
     */
    internal var textBackendOverride: TextRenderBackend? = null

    /** 回放用:当前帧的全部绘制命令(阶段 C 由 MinecraftRenderContext 消费) */
    internal fun commands(): List<DrawCommand> = drawCommands

    /** 清空上一帧记录 */
    internal fun clearCommands() {
        drawCommands.clear()
    }

    /** 记录一段文本绘制(阶段 C 由 MinecraftRenderContext 用 Minecraft 字体渲染) */
    internal fun recordTextDraw(
        text: String,
        x: Float,
        y: Float,
        style: Style,
        alpha: Float = 1f,
        shader: Shader? = null,
        backend: TextRenderBackend = TextRenderBackend.DEFAULT,
    ) {
        drawCommands.add(
            DrawTextCommand(
                matrix = currentMatrix.values.copyOf(),
                clip = currentClip,
                text = text,
                x = x,
                y = y,
                style = style,
                alpha = alpha,
                shader = shader,
                backend = textBackendOverride ?: backend,
            )
        )
    }

    /** 记录一段垂直渐变矩形(T.14 阴影,渲染端经 ColoredRectangleRenderState 提交) */
    internal fun recordGradientRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        topColorArgb: Int,
        bottomColorArgb: Int,
    ) {
        drawCommands.add(
            DrawGradientRectCommand(
                matrix = currentMatrix.values.copyOf(),
                clip = currentClip,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                topColorArgb = topColorArgb,
                bottomColorArgb = bottomColorArgb,
            )
        )
    }

    /** 记录一段阴影(T.14 CPU 离屏真模糊,渲染端经 MinecraftShadowRenderer 提交) */
    internal fun recordShadow(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        elevation: Float,
        offsetX: Float,
        offsetY: Float,
        cornerRadius: Float,
        pathSegments: List<MinecraftPath.PathSegmentData>? = null,
        ambientColorArgb: Int = 0xFF000000.toInt(),
        spotColorArgb: Int = 0xFF000000.toInt(),
    ) {
        drawCommands.add(
            DrawShadowCommand(
                matrix = currentMatrix.values.copyOf(),
                clip = currentClip,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                elevation = elevation,
                offsetX = offsetX,
                offsetY = offsetY,
                cornerRadius = cornerRadius,
                pathSegments = pathSegments,
                ambientColorArgb = ambientColorArgb,
                spotColorArgb = spotColorArgb,
            )
        )
    }

    /**
     * 把 [source] 记录的绘制命令回放到本画布(阶段 C:GraphicsLayer.draw 使用)。
     *
     * 每条命令都携带记录时的矩阵/裁剪快照;回放时先 save + concat 该矩阵,
     * 再重放裁剪与原始绘制参数,最后 restore。
     * [alphaMultiplier] 用于叠加图层级透明度(GraphicsLayer.alpha)。
     */
    internal fun replayFrom(
        source: MinecraftCanvas,
        alphaMultiplier: Float = 1f,
        // T.21:图层级颜色滤镜/混合(官方 GraphicsLayer.colorFilter/blendMode)。
        // draw 级近似:命令自身带 colorFilter/blendMode 时优先用命令的;
        // 命令未带时回退到图层级(官方语义是"图层内容整体后处理",
        // 本平台无离屏,逐命令应用,组合情况以命令为准,见 AGENTS.md)。
        layerColorFilter: NativeColorFilter? = null,
        layerBlendMode: BlendMode = BlendMode.SrcOver,
    ) {
        for (command in source.commands()) {
            save()
            // 平台适配点(T.9 修复):命令 clip 处于**录制画布的根空间**(录制画布
            // 以单位矩阵起始),回放时须经目标画布的**基矩阵**(concat 之前)换算到
            // 目标根空间;按 concat 后的矩阵换算会叠加命令自身矩阵造成双重变换。
            val base = Matrix(currentMatrix.values.copyOf())
            concat(Matrix(command.matrix.copyOf()))
            // 平台适配点(T.35 修复):command.clip 压栈必须与 save()/restore() 严格配对。
            // 此前 clipStack.addLast 是不平衡的额外压栈,restore() 只弹 save() 那一层,
            // 每回放一条带 clip 的命令就泄漏一层裁剪到 clipStack —— 后续兄弟元素被错误
            // 裁剪(多行文本触发,因 clipToBounds 图层录制命令全部携带 clip)。
            var clipPushed = false
            command.clip?.let { clip ->
                val rootClip = base.map(clip)
                clipStack.addLast(currentClip?.intersect(rootClip) ?: rootClip)
                clipPushed = true
            }
            val snapshot = command.paint
            if (snapshot != null) {
                val paint = MinecraftPaint(
                    color = snapshot.color,
                    alpha = snapshot.alpha * alphaMultiplier,
                    style = snapshot.style,
                    strokeWidth = snapshot.strokeWidth,
                    strokeCap = snapshot.strokeCap,
                    filterQuality = snapshot.filterQuality,
                    // T.21:图层级/命令级混合透传(命令优先,图层回退)
                    blendMode = if (snapshot.blendMode == BlendMode.SrcOver) layerBlendMode else snapshot.blendMode,
                    shader = snapshot.shader,
                ).apply {
                    // T.21:图层级/命令级滤镜透传(命令优先,图层回退)经内部通道注入
                    nativeColorFilter = snapshot.colorFilter ?: layerColorFilter
                }
                if (command.layer3D != null) {
                    // T.15 修复:3D 命令(带 layer3D)必须**透传** layer3D。
                    // 嵌套图层时,子图层 3D 命令先进入父图层录制画布,父图层
                    // drawLayer 经 replayFrom 回放;若走 drawXxx 重新构造会丢失
                    // layer3D(变成普通 2D 命令),渲染端 render3D 永不触发
                    // (表现为方块位置错乱/跑左上角)。
                    // 此处直接构造带 layer3D 的新命令:
                    // - 矩阵 = **原始命令矩阵**(局部→录制画布,不叠加当前画布矩阵),
                    //   因为渲染端 map3D 链是「局部 → 命令矩阵 → layer3D → 屏幕」;
                    // - layer3D = 原 layer3D 右乘当前画布矩阵(父级/场景变换,行主序化),
                    //   保证嵌套层级正确(命令矩阵只负责局部,父级变换全进 layer3D);
                    // - clip = 换算后的根空间裁剪。
                    val paintSnap = paint.snapshot()
                    val clipNow = currentClip
                    val origM = command.matrix
                    // 当前画布矩阵(列主序 2D)→ 行主序 4x4,右乘进 layer3D
                    val baseRow = Matrix().apply {
                        values[0] = base.values[0]
                        values[1] = base.values[4]
                        values[4] = base.values[1]
                        values[5] = base.values[5]
                        values[10] = 1f
                        values[12] = base.values[12]
                        values[13] = base.values[13]
                        values[15] = 1f
                    }
                    val layer3DWithBase = Matrix(Array(16) { i -> command.layer3D!![i] }.toFloatArray())
                        .apply { timesAssign(baseRow) }
                        .values
                    when (command) {
                        is DrawRectCommand         -> record(
                            DrawRectCommand(
                                origM, clipNow, paintSnap,
                                command.left, command.top, command.right, command.bottom,
                                layer3D = layer3DWithBase,
                            )
                        )

                        is DrawRoundRectCommand    -> record(
                            DrawRoundRectCommand(
                                origM, clipNow, paintSnap,
                                command.left, command.top, command.right, command.bottom,
                                command.radiusX, command.radiusY,
                                layer3D = layer3DWithBase,
                            )
                        )

                        is DrawOvalCommand         -> record(
                            DrawOvalCommand(
                                origM, clipNow, paintSnap,
                                command.left, command.top, command.right, command.bottom,
                                layer3D = layer3DWithBase,
                            )
                        )

                        is DrawCircleCommand       -> record(
                            DrawCircleCommand(
                                origM, clipNow, paintSnap,
                                command.centerX, command.centerY, command.radius,
                                layer3D = layer3DWithBase,
                            )
                        )

                        is DrawArcCommand          -> record(
                            DrawArcCommand(
                                origM, clipNow, paintSnap,
                                command.left, command.top, command.right, command.bottom,
                                command.startAngle, command.sweepAngle, command.useCenter,
                                layer3D = layer3DWithBase,
                            )
                        )

                        is DrawLineCommand         -> record(
                            DrawLineCommand(
                                origM, clipNow, paintSnap,
                                command.p1x, command.p1y, command.p2x, command.p2y,
                                layer3D = layer3DWithBase,
                            )
                        )

                        is DrawPathCommand         -> record(
                            DrawPathCommand(
                                origM, clipNow, paintSnap, command.segments,
                                layer3D = layer3DWithBase,
                            )
                        )

                        is DrawPointsCommand       -> record(
                            DrawPointsCommand(
                                origM, clipNow, paintSnap,
                                command.pointMode, command.points,
                                layer3D = layer3DWithBase,
                            )
                        )

                        is DrawVerticesCommand     -> record(
                            DrawVerticesCommand(
                                origM, clipNow, paintSnap,
                                command.vertexMode, command.positions, command.colors, command.indices,
                                layer3D = layer3DWithBase,
                            )
                        )

                        is DrawImageRectCommand    -> drawImageRect(
                            command.image,
                            IntOffset(command.srcOffsetX, command.srcOffsetY),
                            IntSize(command.srcWidth, command.srcHeight),
                            IntOffset(command.dstOffsetX, command.dstOffsetY),
                            IntSize(command.dstWidth, command.dstHeight),
                            paint,
                        )

                        is DrawTextCommand         -> Unit // 文本在 else 分支处理
                        is DrawGradientRectCommand -> Unit // 渐变矩形在 else 分支处理
                        is DrawShadowCommand       -> Unit // 阴影在 else 分支处理
                        is DrawCustomCommand       -> record(
                            DrawCustomCommand(
                                matrix = command.matrix, clip = currentClip,
                                paint = paint.snapshot(), layer3D = command.layer3D,
                                tag = command.tag, data = command.data
                            )
                        )

                        else                       -> Unit
                    }
                } else
                    when (command) {
                        is DrawRectCommand         ->
                            drawRect(command.left, command.top, command.right, command.bottom, paint)

                        is DrawRoundRectCommand    ->
                            drawRoundRect(
                                command.left, command.top, command.right, command.bottom,
                                command.radiusX, command.radiusY, paint,
                            )

                        is DrawOvalCommand         ->
                            drawOval(command.left, command.top, command.right, command.bottom, paint)

                        is DrawCircleCommand       ->
                            drawCircle(Offset(command.centerX, command.centerY), command.radius, paint)

                        is DrawArcCommand          ->
                            drawArc(
                                command.left, command.top, command.right, command.bottom,
                                command.startAngle, command.sweepAngle, command.useCenter, paint,
                            )

                        is DrawLineCommand         ->
                            drawLine(
                                Offset(command.p1x, command.p1y),
                                Offset(command.p2x, command.p2y),
                                paint,
                            )

                        is DrawPathCommand         ->
                            drawPath(MinecraftPath(command.segments), paint)

                        is DrawPointsCommand       ->
                            drawPoints(command.pointMode, command.points, paint)

                        is DrawVerticesCommand     -> record(
                            DrawVerticesCommand(
                                snapshot(), currentClip, paint.snapshot(),
                                command.vertexMode, command.positions, command.colors, command.indices,
                            )
                        )

                        is DrawImageRectCommand    ->
                            drawImageRect(
                                command.image,
                                IntOffset(command.srcOffsetX, command.srcOffsetY),
                                IntSize(command.srcWidth, command.srcHeight),
                                IntOffset(command.dstOffsetX, command.dstOffsetY),
                                IntSize(command.dstWidth, command.dstHeight),
                                paint,
                            )

                        is DrawTextCommand         -> Unit // 文本在 else 分支处理
                        is DrawGradientRectCommand -> Unit // 渐变矩形在 else 分支处理
                        is DrawShadowCommand       -> Unit // 阴影在 else 分支处理
                        is DrawCustomCommand       -> record(
                            DrawCustomCommand(
                                matrix = snapshot(), clip = currentClip,
                                paint = paint.snapshot(), layer3D = command.layer3D,
                                tag = command.tag, data = command.data
                            )
                        )

                        else                       -> Unit
                    }
            } else if (command is DrawTextCommand) {
                // 平台适配点(T.36):回放阶段视口剔除 —— 文本命令在回放阶段才拿到
                // 目标画布的完整裁剪(窗口/视口图层 clip);paint() 录制阶段 clip 恒 null
                // (滚动容器内容图层 clip=false),故此处按行的屏幕 y 范围判断是否完全
                // 在裁剪外,在外的行跳过(不生成 text item),避免 ComposeGuiRenderer.prepare
                // 对不可见行重跑 prepareText(粘贴大段文本慢帧根因)。
                val clip = currentClip
                if (clip == null || !isTextLineOutsideY(currentMatrix, command, clip)) {
                    // 平台适配点:文本命令同样叠加图层级 alpha(经颜色 alpha 通道应用),
                    // 否则 graphicsLayer 的 alpha 对图层内文本不生效。
                    // T.TT:shader(渐变画刷)必须透传 —— 滚动容器(verticalScroll 等)
                    // 会走图层捕获→回放路径,丢失 shader 会导致渐变文本退化为纯色。
                    recordTextDraw(
                        command.text, command.x, command.y, command.style,
                        alpha = command.alpha * alphaMultiplier,
                        shader = command.shader,
                        backend = command.backend,
                    )
                }
            } else if (command is DrawGradientRectCommand) {
                // 渐变矩形(阴影):颜色已是最终 ARGB,无需 alphaMultiplier 叠加
                recordGradientRect(
                    command.left, command.top, command.right, command.bottom,
                    command.topColorArgb, command.bottomColorArgb,
                )
            } else if (command is DrawShadowCommand) {
                recordShadow(
                    command.left, command.top, command.right, command.bottom,
                    command.elevation, command.offsetX, command.offsetY, command.cornerRadius,
                    command.pathSegments,
                    command.ambientColorArgb, command.spotColorArgb,
                )
            } else if (command is DrawCustomCommand) {
                record(
                    DrawCustomCommand(
                        matrix = snapshot(), clip = currentClip,
                        paint = command.paint, layer3D = command.layer3D,
                        tag = command.tag, data = command.data
                    )
                )
            }
            if (clipPushed) clipStack.removeLast()
            restore()
        }
    }

    /**
     * 平台适配点(T.36):判断文本命令的行(局部 y∈[command.y, command.y+行高])
     * 经 [m] 映射到目标画布空间后,是否完全在 [clip] 的 y 范围之外(垂直视口外)。
     * 行高按命令样式经 FontResolver 解析(P1):原版 9px 固定 / TrueType 为
     * 字体真实行高;文本 scale 已含在命令矩阵,映射后自然放大。
     * 只按 y 剔除(垂直滚动主场景),x 方向交给渲染端 scissor(行宽未知且不误剔可见行)。
     */
    private fun isTextLineOutsideY(m: Matrix, command: DrawTextCommand, clip: Rect): Boolean {
        // T.TT 语义一致模式:布局度量恒原版(9px),两种渲染器一致
        // P1:度量按命令样式解析(唯一决策点 FontResolver)
        val lineHeight = FontResolver.resolveNative(command.style).metrics.lineHeight
        val top = m.map(Offset(command.x, command.y)).y
        val bottom = m.map(Offset(command.x, command.y + lineHeight)).y
        val minY = minOf(top, bottom)
        val maxY = maxOf(top, bottom)
        return maxY < clip.top || minY > clip.bottom
    }

    /**
     * 3D 版回放(T.15):把 [source] 的绘制命令回放到本画布,并附加图层级
     * 3D 变换 [layer3D](行主序 4x4,含透视分量)。
     *
     * 纯色几何命令(矩形/圆角/圆/椭圆/弧/线/路径/点)携带 [layer3D],
     * 渲染端经 CPU 顶点透视变换展开(真 3D 透视);
     * 文本/阴影/渐变命令无法透视纹理校正,降级为 2D 仿射近似
     * (矩阵 = 命令矩阵 × layer3D 的 2D 部分,不携带 layer3D)。
     * 图片命令保持原样(第一版不支持图片渲染)。
     *
     * [text2D] 是文本 2D 近似的干净线性部分(row-major 展平 [m00, m01, m10, m11],
     * T.15 修复):由 GraphicsLayer 按 S·Rx·Ry·Rz(内旋)的 2x2 计算
     * (单轴退化为 cosθ × scale 对角,双轴含真实剪切),配合 with3D 的列主序
     * 放置与 toMatrix3x2f 的 m01/m10 交换,最终 JOML 2x2 = clean2D 2x2,
     * 文本剪切方向与矩形 map3D 一致(详见 GraphicsLayer.draw 注释)。
     */
    internal fun replayFrom3D(
        source: MinecraftCanvas,
        layer3D: FloatArray,
        text2D: FloatArray = floatArrayOf(1f, 0f, 0f, 1f),
        alphaMultiplier: Float = 1f,
        // T.21:图层级颜色滤镜/混合,规则同 replayFrom(命令优先,图层回退)
        layerColorFilter: NativeColorFilter? = null,
        layerBlendMode: BlendMode = BlendMode.SrcOver,
    ) {
        for (command in source.commands()) {
            val converted = command.with3D(layer3D, text2D, alphaMultiplier, layerColorFilter, layerBlendMode)
            if (converted != null) {
                record(converted)
            }
        }
    }

    /**
     * 把 [layer3D](行主序 4x4,含透视)附加到命令上(T.15)。
     *
     * - 纯色几何:原样拷贝,携带 [layer3D],渲染端走 CPU 透视顶点变换
     *   (父画布矩阵已由 GraphicsLayer.draw 右乘进 layer3D);
     * - 文本/阴影/渐变:降级为 2D 仿射近似 —— 矩阵 = 命令矩阵 × layer3D 的
     *   2D 部分(列主序化;layer3D 已含父画布矩阵,故不再左乘),不携带 layer3D;
     * - 图片:返回 null(第一版不支持图片渲染,3D 下同样跳过)。
     */
    private fun DrawCommand.with3D(
        layer3D: FloatArray,
        text2D: FloatArray,
        alphaMultiplier: Float,
        layerColorFilter: NativeColorFilter? = null,
        layerBlendMode: BlendMode = BlendMode.SrcOver,
    ): DrawCommand? {
        // approx2D 的 2x2 = 图层旋转缩放组合的干净线性部分,由 GraphicsLayer
        // 按 S·Rx·Ry·Rz(内旋,点先 X 再 Y 再 Z)计算并以 **row-major 展平**
        // [m00, m01, m10, m11] 传入 [text2D](T.15 修复):
        // - 此处按列主序放置(approx2D[0]=m00, [1]=m01, [4]=m10, [5]=m11),
        //   渲染端 toMatrix3x2f 交换 m01/m10 后,JOML 2x2 = clean2D 2x2,
        //   文本剪切方向与矩形 map3D(直接读 layer3D row-major)一致;
        // - layer3D 的 2x2 对角(layer3D[0]/layer3D[5])被透视列与平移的
        //   耦合污染(随方块屏幕位置变化,Y 方块 45°/60°/-45° 实测期望
        //   0.707/0.5/0.707 被污染成 1.10/0.98/0.31),不能用作文本压缩;
        // - 双轴(rotationX+rotationY)时 Rx·Ry 组合含真实剪切项 sx·sy,
        //   与矩形(纯色 3D 投影)的平行四边形化一致。
        // 位置由 layer3D 透视映射计算(combine 内),不受 2x2 影响。
        val approx2D = floatArrayOf(
            text2D[0], text2D[1], 0f, 0f,
            text2D[2], text2D[3], 0f, 0f,
            0f, 0f, 1f, 0f,
            layer3D[12], layer3D[13], 0f, 1f,
        )

        fun combine(m: FloatArray): FloatArray {
            // 2x2 部分:approx2D × m 的 2x2(文本随图层变换压扁/剪切,2D 近似)。
            // m 是纯平移(2x2 = 单位),故 2x2 = approx2D 的 2x2。
            val r0 = approx2D[0] * m[0] + approx2D[4] * m[1]
            val r1 = approx2D[1] * m[0] + approx2D[5] * m[1]
            val r4 = approx2D[0] * m[4] + approx2D[4] * m[5]
            val r5 = approx2D[1] * m[4] + approx2D[5] * m[5]
            // 平移部分(T.15 修复):文本位置 = layer3D 对「m 平移点(文本在图层内
            // 的位置)」的**透视映射**(与矩形 map3D 同一公式、含 w 除法)。
            // 此前用 approx2D(纯 2D,无透视)算平移,双轴旋转(rotationX+rotationY)
            // 下透视 w 变化大,文本位置偏离矩形(视觉 = 文本到处飞/飞出方块)。
            // 用 layer3D 透视映射后,文本位置精确贴住矩形中心(形状仍为 2D 近似)。
            val tx = m[12]
            val ty = m[13]
            val w = layer3D[3] * tx + layer3D[7] * ty + layer3D[15]
            val iw = if (w > 0f) 1f / w else 0f
            val px = iw * (layer3D[0] * tx + layer3D[4] * ty + layer3D[12])
            val py = iw * (layer3D[1] * tx + layer3D[5] * ty + layer3D[13])
            return floatArrayOf(
                r0, r1, 0f, 0f,
                r4, r5, 0f, 0f,
                0f, 0f, 1f, 0f,
                px, py, 0f, 1f,
            )
        }

        // 纯色几何 3D 分支:图层 alpha(alphaMultiplier)叠加进 paint 快照
        // (与原 replayFrom 的 MinecraftPaint(alpha = snapshot.alpha * alphaMultiplier)
        // 语义一致;渲染端 render3D 直接消费 paint.alpha)。
        // 纯色几何命令的 paint 恒非空(接口约定);文本/阴影/渐变不走此分支。
        fun paint3D(): PaintSnapshot {
            val p = paint ?: return PaintSnapshot(Color.Black, 1f, PaintingStyle.Fill, 0f, StrokeCap.Butt)
            // T.21:图层级滤镜/混合回退(命令优先,图层回退,与 replayFrom 2D 分支一致)
            val effFilter = p.colorFilter ?: layerColorFilter
            val effBlend = if (p.blendMode == BlendMode.SrcOver) layerBlendMode else p.blendMode
            return if (alphaMultiplier == 1f && p.colorFilter === effFilter && p.blendMode == effBlend) {
                PaintSnapshot(
                    color = p.color, alpha = p.alpha, style = p.style,
                    strokeWidth = p.strokeWidth, strokeCap = p.strokeCap,
                    filterQuality = p.filterQuality,
                    colorFilter = effFilter, blendMode = effBlend,
                )
            } else {
                PaintSnapshot(
                    color = p.color,
                    alpha = p.alpha * alphaMultiplier,
                    style = p.style,
                    strokeWidth = p.strokeWidth,
                    strokeCap = p.strokeCap,
                    filterQuality = p.filterQuality,
                    colorFilter = effFilter,
                    blendMode = effBlend,
                )
            }
        }
        return when (this) {
            is DrawRectCommand         -> DrawRectCommand(
                matrix, clip, paint3D(), left, top, right, bottom,
                layer3D = layer3D,
            )

            is DrawRoundRectCommand    -> DrawRoundRectCommand(
                matrix, clip, paint3D(), left, top, right, bottom, radiusX, radiusY,
                layer3D = layer3D,
            )

            is DrawOvalCommand         -> DrawOvalCommand(
                matrix, clip, paint3D(), left, top, right, bottom,
                layer3D = layer3D,
            )

            is DrawCircleCommand       -> DrawCircleCommand(
                matrix, clip, paint3D(), centerX, centerY, radius,
                layer3D = layer3D,
            )

            is DrawArcCommand          -> DrawArcCommand(
                matrix, clip, paint3D(), left, top, right, bottom,
                startAngle, sweepAngle, useCenter,
                layer3D = layer3D,
            )

            is DrawLineCommand         -> DrawLineCommand(
                matrix, clip, paint3D(), p1x, p1y, p2x, p2y,
                layer3D = layer3D,
            )

            is DrawPathCommand         -> DrawPathCommand(
                matrix, clip, paint3D(), segments,
                layer3D = layer3D,
            )

            is DrawPointsCommand       -> DrawPointsCommand(
                matrix, clip, paint3D(), pointMode, points,
                layer3D = layer3D,
            )

            is DrawVerticesCommand     -> DrawVerticesCommand(
                matrix, clip, paint3D(), vertexMode, positions, colors, indices,
                layer3D = layer3D,
            )

            is DrawTextCommand         -> DrawTextCommand(
                combine(matrix), clip, text, x, y, style,
                alpha = alpha * alphaMultiplier,
                shader = shader,
            )

            is DrawGradientRectCommand -> DrawGradientRectCommand(
                combine(matrix), clip, left, top, right, bottom,
                topColorArgb, bottomColorArgb,
            )

            is DrawShadowCommand       -> DrawShadowCommand(
                combine(matrix), clip, left, top, right, bottom,
                elevation, offsetX, offsetY, cornerRadius, pathSegments,
                ambientColorArgb, spotColorArgb,
            )

            is DrawImageRectCommand    -> null
            else                       -> null // DrawCustomCommand 等自定义命令
        }
    }

    private fun snapshot(): FloatArray = currentMatrix.values.copyOf()

    private fun Paint.snapshot(): PaintSnapshot =
        PaintSnapshot(
            color = color,
            alpha = alpha,
            style = style,
            strokeWidth = strokeWidth,
            strokeCap = strokeCap,
            filterQuality = filterQuality,
            // T.21:colorFilter 透传(compose ColorFilter 内部即 NativeColorFilter,
            // 渲染端对最终色应用颜色矩阵/调制色);回放注入的 nativeColorFilter 优先。
            // (receiver 是 Paint 接口,须 cast 到 MinecraftPaint 才能读内部通道)
            colorFilter = (this as? MinecraftPaint)?.nativeColorFilter ?: colorFilter?.nativeColorFilter,
            blendMode = blendMode,
            shader = shader,
        )

    private fun record(command: DrawCommand) {
        drawCommands.add(command)
    }

    private val matrixStack = ArrayDeque<Matrix>()

    private val clipStack = ArrayDeque<Rect?>()

    init {
        matrixStack.addLast(Matrix())
        clipStack.addLast(null)
    }

    /** 当前矩阵(画布矩阵栈顶)。T.15:3D 分支需读取父画布矩阵(场景变换)。 */
    internal val currentMatrix: Matrix get() = matrixStack.last()

    /** 当前裁剪矩形(屏幕空间)。T.36:回放阶段视口剔除需读取,与 [currentMatrix] 配合反算行屏幕范围。 */
    internal val currentClip: Rect? get() = clipStack.last()

    override fun save() {
        matrixStack.addLast(Matrix(currentMatrix.values.copyOf()))
        clipStack.addLast(currentClip)
    }

    override fun restore() {
        if (matrixStack.size > 1) matrixStack.removeLast()
        if (clipStack.size > 1) clipStack.removeLast()
    }

    override fun saveLayer(bounds: Rect, paint: Paint) {
        throw UnsupportedOperationException("saveLayer is not supported in v1 (no offscreen layers)")
    }

    override fun translate(dx: Float, dy: Float) {
        val m = currentMatrix
        m.values[12] = m.values[12] + m.values[0] * dx + m.values[4] * dy
        m.values[13] = m.values[13] + m.values[1] * dx + m.values[5] * dy
    }

    override fun scale(sx: Float, sy: Float) {
        val m = currentMatrix
        m.values[0] *= sx
        m.values[1] *= sx
        m.values[4] *= sy
        m.values[5] *= sy
    }

    override fun rotate(degrees: Float) {
        // 平台适配点:post-concat(右乘)M' = M * R,与 translate/scale/concat 一致(Skia 语义),
        // 正角度顺时针(屏幕坐标 y 向下,对齐 Compose/Skia)。
        // 注意变量名按真实含义命名:values[1]=m10, values[4]=m01(列主序)。
        val rad = degrees * PI.toFloat() / 180f
        val c = cos(rad)
        val s = sin(rad)
        val m = currentMatrix
        val m00 = m.values[0]
        val m10 = m.values[1]
        val m01 = m.values[4]
        val m11 = m.values[5]
        m.values[0] = m00 * c + m01 * s
        m.values[1] = m10 * c + m11 * s
        m.values[4] = -m00 * s + m01 * c
        m.values[5] = -m10 * s + m11 * c
    }

    override fun skew(sx: Float, sy: Float) {
        val m = currentMatrix
        val r0c0 = m.values[0]
        val r0c1 = m.values[1]
        val r1c0 = m.values[4]
        val r1c1 = m.values[5]
        // M' = K * M, K = [[1, sx], [sy, 1]] (与 Skia skew(sx, sy) 一致:sx 作用于 x, sy 作用于 y)
        m.values[0] = r0c0 + sx * r1c0
        m.values[1] = r0c1 + sx * r1c1
        m.values[4] = r1c0 + sy * r0c0
        m.values[5] = r1c1 + sy * r0c1
    }

    override fun concat(matrix: Matrix) {
        val m = currentMatrix
        val result = Matrix()
        result.values[0] = m.values[0] * matrix.values[0] + m.values[4] * matrix.values[1]
        result.values[1] = m.values[1] * matrix.values[0] + m.values[5] * matrix.values[1]
        result.values[4] = m.values[0] * matrix.values[4] + m.values[4] * matrix.values[5]
        result.values[5] = m.values[1] * matrix.values[4] + m.values[5] * matrix.values[5]
        result.values[12] = m.values[0] * matrix.values[12] + m.values[4] * matrix.values[13] + m.values[12]
        result.values[13] = m.values[1] * matrix.values[12] + m.values[5] * matrix.values[13] + m.values[13]
        for (i in 0 until 16) m.values[i] = result.values[i]
    }

    override fun clipRect(
        left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp,
    ) {
        if (clipOp == ClipOp.Difference) {
            throw UnsupportedOperationException("clipRect(Difference) is not supported in v1")
        }
        // 平台适配点(T.9 修复):裁剪一律换算到**屏幕空间**再入栈。
        // 不同矩阵状态下的局部矩形不能直接相交(会得到退化矩形,如 336x0,
        // 导致 MC enableScissor 崩溃);统一换算为屏幕空间后相交才有效。
        val screen = Matrix(currentMatrix.values.copyOf()).map(Rect(left, top, right, bottom))
        clipStack.addLast(currentClip?.intersect(screen) ?: screen)
    }

    override fun clipPath(path: Path, clipOp: ClipOp) {
        throw UnsupportedOperationException("clipPath is not supported in v1")
    }

    override fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
        validatePaint(paint)
        record(
            DrawLineCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                p1x = p1.x, p1y = p1.y, p2x = p2.x, p2y = p2.y,
            )
        )
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        validatePaint(paint)
        record(
            DrawRectCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                left = left, top = top, right = right, bottom = bottom,
            )
        )
    }

    override fun drawRoundRect(
        left: Float, top: Float, right: Float, bottom: Float,
        radiusX: Float, radiusY: Float, paint: Paint,
    ) {
        validatePaint(paint)
        record(
            DrawRoundRectCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                left = left, top = top, right = right, bottom = bottom,
                radiusX = radiusX, radiusY = radiusY,
            )
        )
    }

    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        validatePaint(paint)
        record(
            DrawOvalCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                left = left, top = top, right = right, bottom = bottom,
            )
        )
    }

    override fun drawCircle(center: Offset, radius: Float, paint: Paint) {
        validatePaint(paint)
        record(
            DrawCircleCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                centerX = center.x, centerY = center.y, radius = radius,
            )
        )
    }

    override fun drawArc(
        left: Float, top: Float, right: Float, bottom: Float,
        startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint,
    ) {
        validatePaint(paint)
        record(
            DrawArcCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                left = left, top = top, right = right, bottom = bottom,
                startAngle = startAngle, sweepAngle = sweepAngle, useCenter = useCenter,
            )
        )
    }

    override fun drawPath(path: Path, paint: Paint) {
        validatePaint(paint)
        if (path !is MinecraftPath) {
            throw UnsupportedOperationException("drawPath only support MinecraftPath,actual: ${path::class.simpleName}")
        }
        record(
            DrawPathCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                segments = path.segments(),
            )
        )
    }

    override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) {
        validatePaint(paint)
        record(
            DrawPointsCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                pointMode = pointMode, points = points,
            )
        )
    }

    override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint) {
        validatePaint(paint)
        val list = ArrayList<Offset>(points.size / 2)
        var i = 0
        while (i + 1 < points.size) {
            list.add(Offset(points[i], points[i + 1]))
            i += 2
        }
        record(
            DrawPointsCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                pointMode = pointMode, points = list,
            )
        )
    }

    override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint) {
        validatePaint(paint)
        // T.23:drawVertices 的 blendMode 是独立参数,优先于 paint.blendMode
        // (官方 Skia 语义:drawVertices(vertices, blendMode, paint) 的 blendMode 覆盖画笔);
        // SrcOver 时直接用画笔快照,非 SrcOver 时覆盖快照的 blendMode。
        val snap = paint.snapshot()
        val effective = if (blendMode == BlendMode.SrcOver || snap.blendMode != BlendMode.SrcOver) {
            snap
        } else {
            PaintSnapshot(
                snap.color, snap.alpha, snap.style, snap.strokeWidth, snap.strokeCap,
                snap.filterQuality, snap.colorFilter, blendMode,
            )
        }
        record(
            DrawVerticesCommand(
                matrix = snapshot(), clip = currentClip, paint = effective,
                vertexMode = vertices.vertexMode,
                positions = vertices.positions,
                colors = vertices.colors,
                indices = vertices.indices,
            )
        )
    }

    override fun drawImage(image: ImageBitmap, topLeftOffset: Offset, paint: Paint) {
        drawImageRect(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(topLeftOffset.x.toInt(), topLeftOffset.y.toInt()),
            dstSize = IntSize(image.width, image.height),
            paint = paint,
        )
    }

    override fun drawImageRect(
        image: ImageBitmap,
        srcOffset: IntOffset,
        srcSize: IntSize,
        dstOffset: IntOffset,
        dstSize: IntSize,
        paint: Paint,
    ) {
        if (image !is MinecraftImageBitmap) {
            throw UnsupportedOperationException("drawImageRect only support MinecraftImageBitmap,actual: ${image::class.simpleName}")
        }
        validatePaint(paint)
        record(
            DrawImageRectCommand(
                matrix = snapshot(), clip = currentClip, paint = paint.snapshot(),
                image = image,
                srcOffsetX = srcOffset.x, srcOffsetY = srcOffset.y,
                srcWidth = srcSize.width, srcHeight = srcSize.height,
                dstOffsetX = dstOffset.x, dstOffsetY = dstOffset.y,
                dstWidth = dstSize.width, dstHeight = dstSize.height,
            )
        )
    }

    override fun enableZ() = Unit

    override fun disableZ() = Unit

    private fun validatePaint(paint: Paint) {
        // T.22:blendMode 支持 17 种可表达模式(渲染端经 BlendPipelines 切换 pipeline),
        // 其余 12 种高级模式(Overlay/Difference/...)渲染端回退 SrcOver —— 记录端不拦截。
    }
}

fun Canvas.recordCustomDraw(
    tag: Identifier,
    data: Any?,
    paint: Paint?,
    layer3D: FloatArray?,
) {
    if (this !is MinecraftCanvas) return
    drawCommands.add(
        DrawCustomCommand(
            matrix = currentMatrix.values.copyOf(),
            clip = currentClip,
            paint = paint?.toPaintSnapshot(),
            layer3D = layer3D,
            tag = tag,
            data = data,
        )
    )
}

/**
 * 自定义绘制命令(T.37):扩展点,用于承载非标准 Compose 绘制的自定义渲染内容。
 *
 * 由 [MinecraftRenderPlugin] 机制消费,内置标签:
 * - `"mc_texture"`:MC 纹理渲染,data 为 [TextureDrawData]
 *
 * 外部 mod 开发者通过注册 [MinecraftRenderPlugin] 处理自定义标签,无需直接使用本类。
 */
internal class DrawCustomCommand(
    override val matrix: FloatArray,
    override val clip: Rect?,
    override val paint: MinecraftCanvas.PaintSnapshot?,
    override val layer3D: FloatArray?,
    val tag: Identifier,
    val data: Any?,
) : MinecraftCanvas.DrawCommand

/** 供 [MinecraftPath.segments] 使用的段类型常量 */
private val Move = MinecraftPath.PathSegmentType.Move
private val Line = MinecraftPath.PathSegmentType.Line
private val Quadratic = MinecraftPath.PathSegmentType.Quadratic
private val Cubic = MinecraftPath.PathSegmentType.Cubic
private val Close = MinecraftPath.PathSegmentType.Close