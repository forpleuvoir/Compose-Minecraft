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

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.shadow.BlurFilter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import moe.forpleuvoir.compose_minecraft.minecraft.McTextStyle

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
    override var filterQuality: FilterQuality = FilterQuality.Low,
) : Paint {
    override fun asFrameworkPaint(): NativePaint {
        throw UnsupportedOperationException("asFrameworkPaint 第一版不支持")
    }

    override fun equals(other: Any?): Boolean = other is Paint && hashCode() == other.hashCode()

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + alpha.hashCode()
        result = 31 * result + (colorFilter?.hashCode() ?: 0)
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
                PathSegmentType.Move -> moveTo(segment.points[0], segment.points[1])
                PathSegmentType.Line -> lineTo(segment.points[0], segment.points[1])
                PathSegmentType.Quadratic -> quadraticBezierTo(
                    segment.points[0], segment.points[1],
                    segment.points[2], segment.points[3],
                )
                PathSegmentType.Cubic -> cubicTo(
                    segment.points[0], segment.points[1],
                    segment.points[2], segment.points[3],
                    segment.points[4], segment.points[5],
                )
                PathSegmentType.Close -> close()
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

        class Close : PathCommand()
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

    override fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        commands.add(PathCommand.QuadTo(x1, y1, x2, y2))
        lastX = x2
        lastY = y2
    }

    override fun relativeQuadraticBezierTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float) =
        quadraticBezierTo(lastX + dx1, lastY + dy1, lastX + dx2, lastY + dy2)

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

    override fun addRect(rect: Rect) = addRect(rect, Path.Direction.CounterClockwise)

    override fun addRect(rect: Rect, direction: Path.Direction) {
        when (direction) {
            Path.Direction.CounterClockwise -> {
                moveTo(rect.left, rect.top)
                lineTo(rect.right, rect.top)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.left, rect.bottom)
            }
            Path.Direction.Clockwise -> {
                moveTo(rect.left, rect.top)
                lineTo(rect.left, rect.bottom)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.right, rect.top)
            }
        }
        close()
    }

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
        quadraticBezierTo(right, top, right, top + ry)
        lineTo(right, bottom - ry)
        quadraticBezierTo(right, bottom, right - rx, bottom)
        lineTo(left + rx, bottom)
        quadraticBezierTo(left, bottom, left, bottom - ry)
        lineTo(left, top + ry)
        quadraticBezierTo(left, top, left + rx, top)
        close()
    }

    override fun addArcRad(
        oval: Rect, startAngleRadians: Float, sweepAngleRadians: Float,
    ) = addArc(oval, Math.toDegrees(startAngleRadians.toDouble()).toFloat(),
        Math.toDegrees(sweepAngleRadians.toDouble()).toFloat())

    override fun addArc(oval: Rect, startAngleDegrees: Float, sweepAngleDegrees: Float) {
        arcTo(oval, startAngleDegrees, sweepAngleDegrees, true)
    }

    override fun addPath(path: Path, offset: Offset) {
        if (path !is MinecraftPath) {
            throw UnsupportedOperationException("addPath 仅支持 MinecraftPath,实际: ${path::class.simpleName}")
        }
        for (command in path.commands) {
            when (command) {
                is PathCommand.MoveTo -> moveTo(command.x + offset.x, command.y + offset.y)
                is PathCommand.LineTo -> lineTo(command.x + offset.x, command.y + offset.y)
                is PathCommand.QuadTo ->
                    quadraticBezierTo(command.x1 + offset.x, command.y1 + offset.y, command.x2 + offset.x, command.y2 + offset.y)
                is PathCommand.CubicTo ->
                    cubicTo(command.x1 + offset.x, command.y1 + offset.y, command.x2 + offset.x, command.y2 + offset.y, command.x3 + offset.x, command.y3 + offset.y)
                is PathCommand.ArcTo -> arcTo(
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
                is PathCommand.Close -> close()
            }
        }
    }

    override fun close() {
        commands.add(PathCommand.Close())
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
                is PathCommand.MoveTo -> commands[i] = PathCommand.MoveTo(c.x + offset.x, c.y + offset.y)
                is PathCommand.LineTo -> commands[i] = PathCommand.LineTo(c.x + offset.x, c.y + offset.y)
                is PathCommand.QuadTo -> commands[i] =
                    PathCommand.QuadTo(c.x1 + offset.x, c.y1 + offset.y, c.x2 + offset.x, c.y2 + offset.y)
                is PathCommand.CubicTo -> commands[i] = PathCommand.CubicTo(
                    c.x1 + offset.x, c.y1 + offset.y,
                    c.x2 + offset.x, c.y2 + offset.y,
                    c.x3 + offset.x, c.y3 + offset.y,
                )
                else -> Unit
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
                is PathCommand.MoveTo -> listOf(command.x to command.y)
                is PathCommand.LineTo -> listOf(command.x to command.y)
                is PathCommand.QuadTo -> listOf(command.x1 to command.y1, command.x2 to command.y2)
                is PathCommand.CubicTo -> listOf(command.x1 to command.y1, command.x2 to command.y2, command.x3 to command.y3)
                else -> emptyList()
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
        throw UnsupportedOperationException("Path.op($operation) 第一版不支持")
    }

    /** 供 [PathIterator] 遍历的扁平化点序列 */
    internal fun segments(): List<PathSegmentData> {
        val result = ArrayList<PathSegmentData>()
        for (command in commands) {
            when (command) {
                is PathCommand.MoveTo -> result.add(PathSegmentData(Move, floatArrayOf(command.x, command.y)))
                is PathCommand.LineTo -> result.add(PathSegmentData(Line, floatArrayOf(command.x, command.y)))
                is PathCommand.QuadTo -> result.add(
                    PathSegmentData(Quadratic, floatArrayOf(command.x1, command.y1, command.x2, command.y2))
                )
                is PathCommand.CubicTo -> result.add(
                    PathSegmentData(Cubic, floatArrayOf(command.x1, command.y1, command.x2, command.y2, command.x3, command.y3))
                )
                is PathCommand.ArcTo -> {
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
                        result.add(PathSegmentData(Line, floatArrayOf(px.toFloat(), py.toFloat())))
                    }
                }
                is PathCommand.Close -> result.add(PathSegmentData(Close, floatArrayOf()))
            }
        }
        return result
    }

    private fun abs(v: Float): Float = if (v < 0) -v else v

    internal data class PathSegmentData(val type: PathSegmentType, val points: FloatArray)

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
            MinecraftPath.PathSegmentType.Move -> PathSegment.Type.Move to 0f
            MinecraftPath.PathSegmentType.Line -> PathSegment.Type.Line to 0f
            MinecraftPath.PathSegmentType.Quadratic -> PathSegment.Type.Quadratic to 0f
            MinecraftPath.PathSegmentType.Cubic -> PathSegment.Type.Cubic to 0f
            MinecraftPath.PathSegmentType.Close -> PathSegment.Type.Close to 0f
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
            MinecraftPath.PathSegmentType.Move -> PathSegment.Type.Move
            MinecraftPath.PathSegmentType.Line -> PathSegment.Type.Line
            MinecraftPath.PathSegmentType.Quadratic -> PathSegment.Type.Quadratic
            MinecraftPath.PathSegmentType.Cubic -> PathSegment.Type.Cubic
            MinecraftPath.PathSegmentType.Close -> PathSegment.Type.Close
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
        throw UnsupportedOperationException("PathMeasure.getSegment 第一版不支持")
    }

    override fun setPath(path: Path?, forceClosed: Boolean) {
        this.path = path as? MinecraftPath
        cachedLength = calculateLength()
    }

    override fun getPosition(distance: Float): Offset {
        throw UnsupportedOperationException("PathMeasure.getPosition 第一版不支持")
    }

    override fun getTangent(distance: Float): Offset {
        throw UnsupportedOperationException("PathMeasure.getTangent 第一版不支持")
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

/** Minecraft 平台 ImageBitmap(CPU 像素缓冲,后续阶段接入 Minecraft 纹理) */
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

    override fun prepareToDraw() = Unit
}

/** Minecraft 平台 NativeColorFilter(记录色值/矩阵,绘制阶段应用) */
internal class NativeColorFilter internal constructor(
    val color: Color? = null,
    val colorMatrix: ColorMatrix? = null,
    val blendMode: BlendMode = BlendMode.SrcIn,
) {
    override fun equals(other: Any?): Boolean =
        other is NativeColorFilter && color == other.color && colorMatrix == other.colorMatrix

    override fun hashCode(): Int = (color?.hashCode() ?: 0) * 31 + (colorMatrix?.hashCode() ?: 0)
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
    )

    /** 绘制命令基类 */
    sealed interface DrawCommand {
        /** 记录时的矩阵快照(4x4,列主序,同 [Matrix.values]) */
        val matrix: FloatArray

        /** 记录时的裁剪矩形(屏幕坐标,null 表示不裁剪) */
        val clip: Rect?

        /** 绘制参数快照;文本命令为 null(文本使用独立字段) */
        val paint: PaintSnapshot?
    }

    class DrawRectCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
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
    ) : DrawCommand

    class DrawOvalCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) : DrawCommand

    class DrawCircleCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
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
    ) : DrawCommand

    class DrawLineCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val p1x: Float,
        val p1y: Float,
        val p2x: Float,
        val p2y: Float,
    ) : DrawCommand

    class DrawPathCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        /** 路径的扁平化段数据(与 [MinecraftPath.segments] 一致) */
        val segments: List<MinecraftPath.PathSegmentData>,
    ) : DrawCommand

    class DrawPointsCommand(
        override val matrix: FloatArray,
        override val clip: Rect?,
        override val paint: PaintSnapshot,
        val pointMode: PointMode,
        val points: List<Offset>,
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
        val style: McTextStyle,
    ) : DrawCommand {
        override val paint: PaintSnapshot? = null
    }

    private val drawCommands = ArrayList<DrawCommand>()

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
        style: McTextStyle,
    ) {
        drawCommands.add(
            DrawTextCommand(
                matrix = currentMatrix.values.copyOf(),
                clip = currentClip,
                text = text,
                x = x,
                y = y,
                style = style,
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
    internal fun replayFrom(source: MinecraftCanvas, alphaMultiplier: Float = 1f) {
        for (command in source.commands()) {
            save()
            // 平台适配点(T.9 修复):命令 clip 处于**录制画布的根空间**(录制画布
            // 以单位矩阵起始),回放时须经目标画布的**基矩阵**(concat 之前)换算到
            // 目标根空间;按 concat 后的矩阵换算会叠加命令自身矩阵造成双重变换。
            val base = Matrix(currentMatrix.values.copyOf())
            concat(Matrix(command.matrix.copyOf()))
            command.clip?.let { clip ->
                val rootClip = base.map(clip)
                clipStack.addLast(currentClip?.let { it.intersect(rootClip) } ?: rootClip)
            }
            val snapshot = command.paint
            if (snapshot != null) {
                val paint = MinecraftPaint(
                    color = snapshot.color,
                    alpha = snapshot.alpha * alphaMultiplier,
                    style = snapshot.style,
                    strokeWidth = snapshot.strokeWidth,
                )
                when (command) {
                    is DrawRectCommand ->
                        drawRect(command.left, command.top, command.right, command.bottom, paint)
                    is DrawRoundRectCommand ->
                        drawRoundRect(
                            command.left, command.top, command.right, command.bottom,
                            command.radiusX, command.radiusY, paint,
                        )
                    is DrawOvalCommand ->
                        drawOval(command.left, command.top, command.right, command.bottom, paint)
                    is DrawCircleCommand ->
                        drawCircle(Offset(command.centerX, command.centerY), command.radius, paint)
                    is DrawArcCommand ->
                        drawArc(
                            command.left, command.top, command.right, command.bottom,
                            command.startAngle, command.sweepAngle, command.useCenter, paint,
                        )
                    is DrawLineCommand ->
                        drawLine(
                            Offset(command.p1x, command.p1y),
                            Offset(command.p2x, command.p2y),
                            paint,
                        )
                    is DrawPathCommand ->
                        drawPath(MinecraftPath(command.segments), paint)
                    is DrawPointsCommand ->
                        drawPoints(command.pointMode, command.points, paint)
                    is DrawImageRectCommand ->
                        drawImageRect(
                            command.image,
                            IntOffset(command.srcOffsetX, command.srcOffsetY),
                            IntSize(command.srcWidth, command.srcHeight),
                            IntOffset(command.dstOffsetX, command.dstOffsetY),
                            IntSize(command.dstWidth, command.dstHeight),
                            paint,
                        )
                    is DrawTextCommand -> Unit // 文本在 else 分支处理
                }
            } else if (command is DrawTextCommand) {
                recordTextDraw(command.text, command.x, command.y, command.style)
            }
            restore()
        }
    }

    private fun snapshot(): FloatArray = currentMatrix.values.copyOf()

    private fun Paint.snapshot(): PaintSnapshot =
        PaintSnapshot(color = color, alpha = alpha, style = style, strokeWidth = strokeWidth)

    private fun record(command: DrawCommand) {
        drawCommands.add(command)
    }

    private val matrixStack = ArrayDeque<androidx.compose.ui.graphics.Matrix>()

    private val clipStack = ArrayDeque<Rect?>()

    init {
        matrixStack.addLast(Matrix())
        clipStack.addLast(null)
    }

    private val currentMatrix: Matrix get() = matrixStack.last()

    private val currentClip: Rect? get() = clipStack.last()

    override fun save() {
        matrixStack.addLast(Matrix(currentMatrix.values.copyOf()))
        clipStack.addLast(currentClip)
    }

    override fun restore() {
        if (matrixStack.size > 1) matrixStack.removeLast()
        if (clipStack.size > 1) clipStack.removeLast()
    }

    override fun saveLayer(bounds: Rect, paint: Paint) {
        throw UnsupportedOperationException("saveLayer 第一版不支持(不允许离屏图层)")
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
        val rad = degrees * PI.toFloat() / 180f
        val c = cos(rad)
        val s = sin(rad)
        val m = currentMatrix
        val m00 = m.values[0]
        val m01 = m.values[1]
        val m10 = m.values[4]
        val m11 = m.values[5]
        m.values[0] = m00 * c + m10 * s
        m.values[1] = m01 * c + m11 * s
        m.values[4] = -m00 * s + m10 * c
        m.values[5] = -m01 * s + m11 * c
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
            throw UnsupportedOperationException("clipRect(Difference) 第一版不支持")
        }
        // 平台适配点(T.9 修复):裁剪一律换算到**屏幕空间**再入栈。
        // 不同矩阵状态下的局部矩形不能直接相交(会得到退化矩形,如 336x0,
        // 导致 MC enableScissor 崩溃);统一换算为屏幕空间后相交才有效。
        val screen = Matrix(currentMatrix.values.copyOf()).map(Rect(left, top, right, bottom))
        clipStack.addLast(currentClip?.let { it.intersect(screen) } ?: screen)
    }

    override fun clipPath(path: Path, clipOp: ClipOp) {
        throw UnsupportedOperationException("clipPath 第一版不支持")
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
            throw UnsupportedOperationException("drawPath 仅支持 MinecraftPath,实际: ${path::class.simpleName}")
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
        throw UnsupportedOperationException("drawVertices 第一版不支持")
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
            throw UnsupportedOperationException("drawImageRect 仅支持 MinecraftImageBitmap,实际: ${image::class.simpleName}")
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
        if (paint.shader != null) {
            throw UnsupportedOperationException("Paint.shader 第一版不支持")
        }
        if (paint.blendMode != BlendMode.SrcOver) {
            throw UnsupportedOperationException("Paint.blendMode 仅支持 SrcOver,实际: ${paint.blendMode}")
        }
    }
}

/** 供 [MinecraftPath.segments] 使用的段类型常量 */
private val Move = MinecraftPath.PathSegmentType.Move
private val Line = MinecraftPath.PathSegmentType.Line
private val Quadratic = MinecraftPath.PathSegmentType.Quadratic
private val Cubic = MinecraftPath.PathSegmentType.Cubic
private val Close = MinecraftPath.PathSegmentType.Close

/** Minecraft 平台 NativeCanvas(NativeCanvas 的别名目标) */
class NativeCanvasHolder {
    val canvas: Canvas = MinecraftCanvas()
}

/** NativePaint 与 NativeCanvas 类型别名(官方 Deprecated API) */
internal typealias NativePaintImpl = MinecraftPaint
internal typealias NativeCanvasImpl = NativeCanvasHolder
