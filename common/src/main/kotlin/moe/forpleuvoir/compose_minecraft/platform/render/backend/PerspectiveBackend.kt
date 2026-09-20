package moe.forpleuvoir.compose_minecraft.platform.render.backend

import moe.forpleuvoir.compose_minecraft.platform.render.renderer.GuiTriangleRenderState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.MinecraftCanvas.PaintSnapshot
import androidx.compose.ui.graphics.MinecraftCanvas.DrawArcCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCircleCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawLineCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawOvalCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPathCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPointsCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRoundRectCommand
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.VertexMode
import moe.forpleuvoir.compose_minecraft.platform.render.util.BlendPipelines
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.GeometryTessellator
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.MinecraftGuiTriangles
import moe.forpleuvoir.compose_minecraft.platform.render.paint.ColorEvaluator
import moe.forpleuvoir.compose_minecraft.platform.render.paint.toArgb
import org.joml.Matrix3x2f
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 3D 透视后端(自 `MinecraftRenderContext.render3D` 原样搬移)。
 *
 * 图层 rotationX/rotationY 的 3D 透视无法用 MC 的 2D GUI 管线表达,因此在 CPU 端完成:
 * 局部几何的每个顶点先经命令矩阵(列主序 2D)变换到图层空间,再经 [DrawCommand.layer3D]
 * (行主序 4x4,含透视)变换到屏幕空间(含透视除法 w)。输出屏幕坐标三角形,
 * pose = identity,coverage = OPAQUE(3D 下关闭抗锯齿,实心填充)。文本/阴影/渐变在记录端
 * 已降级为 2D 近似,不会进入本后端。
 *
 * 注意:3D 几何化在方法内完成(与 GPU 回放路径独立 —— 3D 用 OPAQUE 实心三角形,无 AA 距离场),
 * 不共享 2D triangleCache;D4 已定:3D 维持现行为。
 *
 * 防御:w <= 0(图元在相机后方)的顶点丢弃,对应三角形跳过。
 */
internal class PerspectiveBackend(internal var sink: GuiCommandSink) {

    /** 3D 三角化输出缓冲(每帧由外层设置 / 复用) */
    internal var triangleSink = GeometryTessellator.Sink()


    /** Paint 快照 → 最终 0xAARRGGBB(alpha 叠加 +  颜色滤镜)。 */
    private fun PaintSnapshot.toArgb(): Int = ColorEvaluator.applyColorFilter(color.toArgb(alpha), colorFilter)

    /** 命令矩阵(列主序 4x4)2D 部分的最大轴缩放 */
    private fun matrixScale(m: FloatArray): Float {
        val scaleX = sqrt(m[0] * m[0] + m[1] * m[1])
        val scaleY = sqrt(m[4] * m[4] + m[5] * m[5])
        return max(scaleX, scaleY)
    }

    fun render(command: DrawCommand) {
        val layer3D = command.layer3D ?: return
        val m2 = command.matrix
        val paint = command.paint ?: return
        // 颜色滤镜经 PaintSnapshot.toArgb 应用(alpha + colorFilter)。
        // alpha 作为透明度:替换/擦除族退化为 SrcOver,颜色按模式解算(见 BlendPipelines.fadeColor)
        val fadeMode = BlendPipelines.fadeBlendMode(paint.blendMode)
        val colorArgb = BlendPipelines.fadeColor(paint.blendMode, paint.toArgb())
        var output = FloatArray(384)
        var count = 0

        /** :DrawVerticesCommand 的逐顶点色(其余命令为 null) */
        var outColors3D: IntArray? = null

        /** 追加一个 3D 变换后的三角形;返回 false 表示任一顶点在相机后方 */
        fun emitTriangle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) {
            val pa = map3D(m2, layer3D, ax, ay) ?: return
            val pb = map3D(m2, layer3D, bx, by) ?: return
            val pc = map3D(m2, layer3D, cx, cy) ?: return
            if (count + 9 > output.size) output = output.copyOf(output.size * 2)
            output[count] = pa[0]; output[count + 1] = pa[1]; output[count + 2] = OPAQUE_COVERAGE
            output[count + 3] = pb[0]; output[count + 4] = pb[1]; output[count + 5] = OPAQUE_COVERAGE
            output[count + 6] = pc[0]; output[count + 7] = pc[1]; output[count + 8] = OPAQUE_COVERAGE
            count += 9
        }

        // 矩形 / 退化圆角矩形:4 角 → 2 三角形
        fun quad(left: Float, top: Float, right: Float, bottom: Float) {
            emitTriangle(left, top, right, top, right, bottom)
            emitTriangle(left, top, right, bottom, left, bottom)
        }

        fun tessellated(tessellate: (GeometryTessellator.Sink) -> Unit) {
            // 像素场景(1:1),AA 距离不再乘 guiScale
            triangleSink.aaScale = matrixScale(m2)
            triangleSink.clear()
            tessellate(triangleSink)
            if (triangleSink.vertexCount < 3) return
            val src = triangleSink.toArray()
            var i = 0
            while (i + 2 < src.size) {
                val pa = map3D(m2, layer3D, src[i], src[i + 1])
                val pb = map3D(m2, layer3D, src[i + 3], src[i + 4])
                val pc = map3D(m2, layer3D, src[i + 6], src[i + 7])
                if (pa != null && pb != null && pc != null) {
                    if (count + 9 > output.size) output = output.copyOf(output.size * 2)
                    output[count] = pa[0]; output[count + 1] = pa[1]; output[count + 2] = OPAQUE_COVERAGE
                    output[count + 3] = pb[0]; output[count + 4] = pb[1]; output[count + 5] = OPAQUE_COVERAGE
                    output[count + 6] = pc[0]; output[count + 7] = pc[1]; output[count + 8] = OPAQUE_COVERAGE
                    count += 9
                }
                i += 9
            }
        }

        when (command) {
            // 平台适配点(修复):3D 路径同样按 style 分流 —— Fill 走实心 quad,
            // Stroke 走三角化描边带(与主路径一致,见 render() 内 DrawRectCommand)。
            is DrawRectCommand                     ->
                if (paint.style == PaintingStyle.Fill) {
                    quad(command.left, command.top, command.right, command.bottom)
                } else {
                    tessellated { sink ->
                        GeometryTessellator.roundRect(
                            command.left, command.top, command.right, command.bottom,
                            0f, 0f,
                            fill = false,
                            strokeWidth = paint.strokeWidth,
                            sink = sink,
                            join = paint.strokeJoin,
                            miterLimit = paint.strokeMiterLimit,
                            pathEffect = paint.pathEffect,
                        )
                    }
                }

            is DrawRoundRectCommand                ->
                if (command.radiusX <= 1f && command.radiusY <= 1f &&
                    paint.style == PaintingStyle.Fill
                ) {
                    quad(command.left, command.top, command.right, command.bottom)
                } else {
                    tessellated { sink ->
                        GeometryTessellator.roundRect(
                            command.left, command.top, command.right, command.bottom,
                            command.radiusX, command.radiusY,
                            fill = paint.style == PaintingStyle.Fill,
                            strokeWidth = paint.strokeWidth,
                            sink = sink,
                            join = paint.strokeJoin,
                            miterLimit = paint.strokeMiterLimit,
                            pathEffect = paint.pathEffect,
                        )
                    }
                }

            is DrawOvalCommand                     -> tessellated { sink ->
                GeometryTessellator.oval(
                    command.left, command.top, command.right, command.bottom,
                    fill = paint.style == PaintingStyle.Fill,
                    strokeWidth = paint.strokeWidth,
                    sink = sink,
                    join = paint.strokeJoin,
                    miterLimit = paint.strokeMiterLimit,
                    pathEffect = paint.pathEffect,
                )
            }

            is DrawCircleCommand                   -> tessellated { sink ->
                GeometryTessellator.circle(
                    command.centerX, command.centerY, command.radius,
                    fill = paint.style == PaintingStyle.Fill,
                    strokeWidth = paint.strokeWidth,
                    sink = sink,
                    join = paint.strokeJoin,
                    miterLimit = paint.strokeMiterLimit,
                    pathEffect = paint.pathEffect,
                )
            }

            is DrawArcCommand                      -> tessellated { sink ->
                GeometryTessellator.arc(
                    command.left, command.top, command.right, command.bottom,
                    command.startAngle, command.sweepAngle, command.useCenter,
                    fill = paint.style == PaintingStyle.Fill,
                    strokeWidth = paint.strokeWidth,
                    sink = sink,
                    join = paint.strokeJoin,
                    miterLimit = paint.strokeMiterLimit,
                    pathEffect = paint.pathEffect,
                )
            }

            is DrawLineCommand                     -> tessellated { sink ->
                GeometryTessellator.line(
                    command.p1x, command.p1y, command.p2x, command.p2y,
                    command.paint.strokeWidth,
                    command.paint.strokeCap,
                    sink = sink,
                    join = paint.strokeJoin,
                    miterLimit = paint.strokeMiterLimit,
                    pathEffect = paint.pathEffect,
                )
            }

            is DrawPathCommand                     -> tessellated { sink ->
                GeometryTessellator.path(
                    command.segments,
                    fill = paint.style == PaintingStyle.Fill,
                    strokeWidth = paint.strokeWidth,
                    cap = paint.strokeCap,
                    sink = sink,
                    join = paint.strokeJoin,
                    miterLimit = paint.strokeMiterLimit,
                    pathEffect = paint.pathEffect,
                )
            }

            is DrawPointsCommand                   -> tessellated { sink ->
                GeometryTessellator.points(
                    command.pointMode, command.points,
                    command.paint.strokeWidth,
                    command.paint.strokeCap,
                    sink = sink,
                    join = paint.strokeJoin,
                    miterLimit = paint.strokeMiterLimit,
                    pathEffect = paint.pathEffect,
                )
            }

            is MinecraftCanvas.DrawVerticesCommand -> {
                // 顶点网格 3D 透视 —— 逐顶点 map3D + 逐顶点色(alpha + colorFilter)
                val vc = command.positions.size / 2
                if (vc >= 3) {
                    val alphaMul = paint.alpha
                    var outColors = IntArray(384)

                    /** 逐顶点色:alpha 叠加 + colorFilter;再按模式解算透明度(见 fadeColor) */
                    fun vertexColor(i: Int): Int {
                        val c = ColorEvaluator.applyColorFilter(
                            ColorEvaluator.scaleAlpha(command.colors[i], alphaMul), paint.colorFilter,
                        )
                        return BlendPipelines.fadeColor(paint.blendMode, c)
                    }

                    fun emitV(ai: Int, bi: Int, ci: Int) {
                        val pa = map3D(m2, layer3D, command.positions[ai * 2], command.positions[ai * 2 + 1]) ?: return
                        val pb = map3D(m2, layer3D, command.positions[bi * 2], command.positions[bi * 2 + 1]) ?: return
                        val pc = map3D(m2, layer3D, command.positions[ci * 2], command.positions[ci * 2 + 1]) ?: return
                        if (count + 9 > output.size) {
                            output = output.copyOf(output.size * 2)
                            outColors = outColors.copyOf(outColors.size * 2)
                        }
                        output[count] = pa[0]; output[count + 1] = pa[1]; output[count + 2] = OPAQUE_COVERAGE
                        output[count + 3] = pb[0]; output[count + 4] = pb[1]; output[count + 5] = OPAQUE_COVERAGE
                        output[count + 6] = pc[0]; output[count + 7] = pc[1]; output[count + 8] = OPAQUE_COVERAGE
                        outColors[count / 3] = vertexColor(ai)
                        outColors[count / 3 + 1] = vertexColor(bi)
                        outColors[count / 3 + 2] = vertexColor(ci)
                        count += 9
                    }

                    val idx = command.indices
                    if (idx.isNotEmpty()) {
                        when (command.vertexMode) {
                            VertexMode.Triangles     -> {
                                var i = 0
                                while (i + 2 < idx.size) {
                                    emitV(idx[i].toInt(), idx[i + 1].toInt(), idx[i + 2].toInt()); i += 3
                                }
                            }

                            VertexMode.TriangleStrip -> {
                                for (i in 0 until idx.size - 2) emitV(idx[i].toInt(), idx[i + 1].toInt(), idx[i + 2].toInt())
                            }

                            VertexMode.TriangleFan   -> {
                                for (i in 1 until idx.size - 1) emitV(idx[0].toInt(), idx[i].toInt(), idx[i + 1].toInt())
                            }
                        }
                    } else {
                        when (command.vertexMode) {
                            VertexMode.Triangles     -> {
                                var i = 0
                                while (i + 2 < vc) {
                                    emitV(i, i + 1, i + 2); i += 3
                                }
                            }

                            VertexMode.TriangleStrip -> {
                                for (i in 0 until vc - 2) emitV(i, i + 1, i + 2)
                            }

                            VertexMode.TriangleFan   -> {
                                for (i in 1 until vc - 1) emitV(0, i, i + 1)
                            }
                        }
                    }
                    outColors3D = outColors
                }
            }

            else                                   -> return // 文本/阴影/渐变在记录端已降级为 2D 近似,不会到这里
        }

        if (count >= 9) {
            MinecraftGuiTriangles.ensureCompiled()
            BlendPipelines.ensureCompiled()
            sink.addElement(
                GuiTriangleRenderState(
                    pose = IDENTITY_MATRIX,
                    colorArgb = colorArgb,
                    scissor = null,
                    vertices = output.copyOf(count),
                    blendMode = fadeMode,
                    vertexColors = outColors3D?.copyOf(count / 3),
                )
            )
        }
    }

    /**
     * 命令矩阵(列主序 2D)→ 图层空间,再经 layer3D(行主序 4x4,透视除法)
     * → 屏幕空间。返回 null 表示顶点在相机后方(w <= 0)。
     */
    private fun map3D(m2: FloatArray, layer3D: FloatArray, x: Float, y: Float): FloatArray? {
        // m2 是纯 2D 仿射(透视列恒 0/1),Matrix.map 退化为普通 2D 映射,逐位等价
        val p = Matrix(m2).map(Offset(x, y))
        val x1 = p.x
        val y1 = p.y
        // layer3D 含真实透视:保持手写(Matrix.map 在 w<=0 时不具备剔除语义)
        val w = layer3D[3] * x1 + layer3D[7] * y1 + layer3D[15]
        if (w <= 0f) return null
        val iw = 1f / w
        return floatArrayOf(
            iw * (layer3D[0] * x1 + layer3D[4] * y1 + layer3D[12]),
            iw * (layer3D[1] * x1 + layer3D[5] * y1 + layer3D[13]),
        )
    }

    private companion object {
        /** 3D 实心填充 coverage 大数(关闭抗锯齿,与 GeometryTessellator.OPAQUE 同值) */
        const val OPAQUE_COVERAGE = 1e4f

        /** 3D 顶点已是屏幕坐标,pose = identity */
        val IDENTITY_MATRIX: Matrix3x2f = Matrix3x2f()
    }
}
