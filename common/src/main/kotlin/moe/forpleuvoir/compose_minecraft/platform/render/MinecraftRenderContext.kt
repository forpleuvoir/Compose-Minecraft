package moe.forpleuvoir.compose_minecraft.platform.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.MinecraftCanvas.DrawArcCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCircleCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawImageRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawLineCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawOvalCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPathCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPointsCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRoundRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawTextCommand
import androidx.compose.ui.graphics.MinecraftCanvas.PaintSnapshot
import androidx.compose.ui.graphics.MinecraftImageBitmap
import androidx.compose.ui.graphics.NativeColorFilter
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.VertexMode
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.locale.Language
import moe.forpleuvoir.compose_minecraft.platform.ui.text.obfuscatedRaw
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toComponent
import org.joml.Matrix3x2f
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 把 [MinecraftCanvas] 记录的绘制命令提交到 Minecraft 当前帧的 [GuiRenderState]。
 *
 * 阶段 C:把 [MinecraftCanvas] 记录的绘制命令提交到 Minecraft 当前帧的 [GuiRenderState]。
 * 第一版支持:
 * - 矩形(含圆角为 0 的圆角矩形)→ [BlitRenderState] 纯色四边形;
 * - 圆/椭圆/弧/线/路径/点/带圆角矩形 → [GeometryTessellator] CPU 三角化,
 *   经 [GuiTriangleRenderState](自定义 TRIANGLES pipeline)提交;
 * - 文本 → [GuiTextRenderState](阶段 E,MC Font 字形,布局度量同为 MC 字形)。
 * 直接进入当前帧 GUI 渲染;不创建任何额外 RenderTarget/离屏纹理。
 *
 * 尚未支持(后续阶段):
 * - 图片(drawImageRect)→ 需要把 [androidx.compose.ui.graphics.MinecraftImageBitmap]
 *   的 CPU 像素上传为 GpuTexture。
 *
 * 内部类:MinecraftCanvas 及其命令模型为 internal,业务代码经 MinecraftComposeScene 使用。
 */
internal class MinecraftRenderContext {

    /** 三角化输出缓冲(每帧复用,避免分配) */
    private val triangleSink = GeometryTessellator.Sink()

    /**
     * 三角化结果缓存:按「命令几何内容 + aaScale + Paint 参数」指纹复用顶点数组。
     * 重复图形(相同内容与缩放)命中缓存直接复用,不再每帧重新三角化;
     * 内容变化(坐标/参数/缩放)指纹随之变化 → 自动失效重建。
     * 缓存数组被 [GuiTriangleRenderState] 只读共享;膨胀超限时整体清空。
     */
    private val triangleCache = HashMap<Long, FloatArray>()

    /** 把 [canvas] 中的命令逐条提交到 [sink](T.24:像素坐标,场景 1:1 窗口像素) */
    fun render(canvas: MinecraftCanvas, sink: GuiCommandSink) {
        val windowState = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState
        // T.24:场景尺寸 = 窗口像素(1:1),不再除 guiScale
        val windowWidth = windowState.width.toFloat()
        val windowHeight = windowState.height.toFloat()

        for (command in canvas.commands()) {
            // T.15:3D 命令(图层 rotationX/rotationY,携带行主序含透视的 layer3D)
            // 走 CPU 顶点透视变换路径(纯色几何 → 屏幕三角形,实心无 AA)。
            if (command.layer3D != null) {
                render3D(sink, command)
                continue
            }
            // 平台适配点(T.9 兜底):MC 的 enableScissor 对 "宽高 <= 0" 直接抛
            // IllegalArgumentException("Scissor size must be >0")。两个来源:
            // 1. 退化裁剪(空、反转、NaN、亚像素高度)—— 用四舍五入后的整数尺寸判定;
            // 2. 裁剪矩形完全落在窗口外 —— MC 钳制后高/宽会变成 0 同样崩溃,
            //    因此先与窗口矩形相交,相交为空则整条命令跳过。
            val scissor =
                command.clip?.let { raw ->
                    val clamped =
                        raw.intersect(Rect(0f, 0f, windowWidth.toFloat(), windowHeight.toFloat()))
                    val l = clamped.left.roundToInt()
                    val t = clamped.top.roundToInt()
                    val r = clamped.right.roundToInt()
                    val b = clamped.bottom.roundToInt()
                    if (r > l && b > t) Rect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()) else null
                }
            if (command.clip != null && scissor == null) continue

            when (command) {
                is DrawRectCommand -> sink.addElement(
                    blit(
                        command.matrix, scissor,
                        command.left, command.top, command.right, command.bottom,
                        command.paint,
                    )
                )
                is DrawRoundRectCommand -> {
                    // 圆角为 0(或小于 1px)时退化为矩形;带圆角走三角化
                    if (command.radiusX <= 1f && command.radiusY <= 1f) {
                        sink.addElement(
                            blit(
                                command.matrix, scissor,
                                command.left, command.top, command.right, command.bottom,
                                command.paint,
                            )
                        )
                    } else {
                        addTriangles(sink, command, scissor) { sink ->
                            GeometryTessellator.roundRect(
                                command.left, command.top, command.right, command.bottom,
                                command.radiusX, command.radiusY,
                                fill = command.paint.style == PaintingStyle.Fill,
                                strokeWidth = command.paint.strokeWidth,
                                sink = sink,
                            )
                        }
                    }
                }
                is DrawOvalCommand -> addTriangles(sink, command, scissor) { sink ->
                    GeometryTessellator.oval(
                        command.left, command.top, command.right, command.bottom,
                        fill = command.paint.style == PaintingStyle.Fill,
                        strokeWidth = command.paint.strokeWidth,
                        sink = sink,
                    )
                }
                is DrawCircleCommand -> addTriangles(sink, command, scissor) { sink ->
                    GeometryTessellator.circle(
                        command.centerX, command.centerY, command.radius,
                        fill = command.paint.style == PaintingStyle.Fill,
                        strokeWidth = command.paint.strokeWidth,
                        sink = sink,
                    )
                }
                is DrawArcCommand -> addTriangles(sink, command, scissor) { sink ->
                    GeometryTessellator.arc(
                        command.left, command.top, command.right, command.bottom,
                        command.startAngle, command.sweepAngle, command.useCenter,
                        fill = command.paint.style == PaintingStyle.Fill,
                        strokeWidth = command.paint.strokeWidth,
                        sink = sink,
                    )
                }
                is DrawLineCommand -> addTriangles(sink, command, scissor) { sink ->
                    GeometryTessellator.line(
                        command.p1x, command.p1y, command.p2x, command.p2y,
                        command.paint.strokeWidth,
                        command.paint.strokeCap,
                        sink = sink,
                    )
                }
                is DrawPathCommand -> addTriangles(sink, command, scissor) { sink ->
                    GeometryTessellator.path(
                        command.segments,
                        fill = command.paint.style == PaintingStyle.Fill,
                        strokeWidth = command.paint.strokeWidth,
                        cap = command.paint.strokeCap,
                        sink = sink,
                    )
                }
                is DrawPointsCommand -> addTriangles(sink, command, scissor) { sink ->
                    GeometryTessellator.points(
                        command.pointMode, command.points,
                        command.paint.strokeWidth,
                        command.paint.strokeCap,
                        sink = sink,
                    )
                }
                is DrawTextCommand -> sink.addText(text(command, scissor))
                is MinecraftCanvas.DrawGradientRectCommand -> sink.addElement(
                    // T.14 阴影(渐变保底):MC 原生双色垂直渐变矩形(GUI pipeline,与 blit 同排序组,
                    // 阴影命令先记录先绘制,层级正确)
                    ColoredRectangleRenderState(
                        RenderPipelines.GUI,
                        TextureSetup.noTexture(),
                        command.matrix.toMatrix3x2f(),
                        command.left.roundToInt(),
                        command.top.roundToInt(),
                        command.right.roundToInt(),
                        command.bottom.roundToInt(),
                        command.topColorArgb,
                        command.bottomColorArgb,
                        scissor?.toScreenRectangle(),
                    )
                )
                is MinecraftCanvas.DrawShadowCommand ->
                    // T.14 阴影(GPU 距离场):CPU 三角化 + 每顶点距离场,
                    // gui_shadow shader 高斯模糊解析解生成软阴影(参照 Skia SkShadowUtils)
                    MinecraftShadowRenderer.renderShadow(
                        sink = sink,
                        matrix = command.matrix,
                        left = command.left,
                        top = command.top,
                        right = command.right,
                        bottom = command.bottom,
                        elevation = command.elevation,
                        offsetX = command.offsetX,
                        offsetY = command.offsetY,
                        cornerRadius = command.cornerRadius,
                        pathSegments = command.pathSegments,
                        ambientColorArgb = command.ambientColorArgb,
                        spotColorArgb = command.spotColorArgb,
                        scissor = scissor?.toScreenRectangle(),
                    )
                is DrawImageRectCommand -> sink.addElement(
                    blitImage(command, scissor)
                )
                is MinecraftCanvas.DrawVerticesCommand -> addVertices(sink, command, scissor)
            }
        }
    }

    /**
     * 3D 命令提交(T.15):CPU 顶点透视变换。
     *
     * 图层 rotationX/rotationY 的 3D 透视无法用 MC 的 2D GUI 管线表达,因此
     * 在 CPU 端完成:局部几何(三角化/四边形)的每个顶点先经命令矩阵
     * (列主序 2D)变换到图层空间,再经 [DrawCommand.layer3D](行主序 4x4,
     * 含透视)变换到屏幕空间(含透视除法 w)。输出屏幕坐标三角形,
     * pose = identity,coverage = OPAQUE(3D 下关闭抗锯齿,实心填充)。
     *
     * 语义对齐:与命中测试(GraphicsLayerOwnerLayer.updateMatrix →
     * prepareTransformationMatrix)使用同一矩阵构建逻辑(见 GraphicsLayer.draw
     * 3D 分支),绘制与命中一致。
     *
     * 防御:w <= 0(图元在相机后方)的顶点丢弃,对应三角形跳过。
     */
    private fun render3D(sink: GuiCommandSink, command: DrawCommand) {
        val layer3D = command.layer3D ?: return
        val m2 = command.matrix
        val paint = command.paint ?: return
        // T.21:颜色滤镜经 PaintSnapshot.toArgb() 应用(alpha + colorFilter)
        val colorArgb = paint.toArgb()
        var output = FloatArray(384)
        var count = 0
        /** T.23:DrawVerticesCommand 的逐顶点色(其余命令为 null) */
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
            // T.24:像素场景(1:1),AA 距离不再乘 guiScale
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
            is DrawRectCommand -> quad(command.left, command.top, command.right, command.bottom)
            is DrawRoundRectCommand ->
                if (command.radiusX <= 1f && command.radiusY <= 1f) {
                    quad(command.left, command.top, command.right, command.bottom)
                } else {
                    tessellated { sink ->
                        GeometryTessellator.roundRect(
                            command.left, command.top, command.right, command.bottom,
                            command.radiusX, command.radiusY,
                            fill = paint.style == PaintingStyle.Fill,
                            strokeWidth = paint.strokeWidth,
                            sink = sink,
                        )
                    }
                }
            is DrawOvalCommand -> tessellated { sink ->
                GeometryTessellator.oval(
                    command.left, command.top, command.right, command.bottom,
                    fill = paint.style == PaintingStyle.Fill,
                    strokeWidth = paint.strokeWidth,
                    sink = sink,
                )
            }
            is DrawCircleCommand -> tessellated { sink ->
                GeometryTessellator.circle(
                    command.centerX, command.centerY, command.radius,
                    fill = paint.style == PaintingStyle.Fill,
                    strokeWidth = paint.strokeWidth,
                    sink = sink,
                )
            }
            is DrawArcCommand -> tessellated { sink ->
                GeometryTessellator.arc(
                    command.left, command.top, command.right, command.bottom,
                    command.startAngle, command.sweepAngle, command.useCenter,
                    fill = paint.style == PaintingStyle.Fill,
                    strokeWidth = paint.strokeWidth,
                    sink = sink,
                )
            }
            is DrawLineCommand -> tessellated { sink ->
                GeometryTessellator.line(
                    command.p1x, command.p1y, command.p2x, command.p2y,
                    command.paint.strokeWidth,
                    command.paint.strokeCap,
                    sink = sink,
                )
            }
            is DrawPathCommand -> tessellated { sink ->
                GeometryTessellator.path(
                    command.segments,
                    fill = paint.style == PaintingStyle.Fill,
                    strokeWidth = paint.strokeWidth,
                    cap = paint.strokeCap,
                    sink = sink,
                )
            }
            is DrawPointsCommand -> tessellated { sink ->
                GeometryTessellator.points(
                    command.pointMode, command.points,
                    command.paint.strokeWidth,
                    command.paint.strokeCap,
                    sink = sink,
                )
            }
            is MinecraftCanvas.DrawVerticesCommand -> {
                // T.23:顶点网格 3D 透视 —— 逐顶点 map3D + 逐顶点色(alpha + colorFilter)
                val vc = command.positions.size / 2
                if (vc >= 3) {
                    val alphaMul = paint.alpha
                    var outColors = IntArray(384)
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
                        outColors[count / 3] = applyColorFilter(scaleAlpha(command.colors[ai], alphaMul), paint.colorFilter)
                        outColors[count / 3 + 1] = applyColorFilter(scaleAlpha(command.colors[bi], alphaMul), paint.colorFilter)
                        outColors[count / 3 + 2] = applyColorFilter(scaleAlpha(command.colors[ci], alphaMul), paint.colorFilter)
                        count += 9
                    }
                    val idx = command.indices
                    if (idx.isNotEmpty()) {
                        when (command.vertexMode) {
                            VertexMode.Triangles -> {
                                var i = 0
                                while (i + 2 < idx.size) {
                                    emitV(idx[i].toInt(), idx[i + 1].toInt(), idx[i + 2].toInt()); i += 3
                                }
                            }
                            VertexMode.TriangleStrip -> {
                                for (i in 0 until idx.size - 2) emitV(idx[i].toInt(), idx[i + 1].toInt(), idx[i + 2].toInt())
                            }
                            VertexMode.TriangleFan -> {
                                for (i in 1 until idx.size - 1) emitV(idx[0].toInt(), idx[i].toInt(), idx[i + 1].toInt())
                            }
                        }
                    } else {
                        when (command.vertexMode) {
                            VertexMode.Triangles -> {
                                var i = 0
                                while (i + 2 < vc) {
                                    emitV(i, i + 1, i + 2); i += 3
                                }
                            }
                            VertexMode.TriangleStrip -> {
                                for (i in 0 until vc - 2) emitV(i, i + 1, i + 2)
                            }
                            VertexMode.TriangleFan -> {
                                for (i in 1 until vc - 1) emitV(0, i, i + 1)
                            }
                        }
                    }
                    outColors3D = outColors
                }
            }
            else -> return // 文本/阴影/渐变在记录端已降级为 2D 近似,不会到这里
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
                    blendMode = paint.blendMode,
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
        val x1 = m2[0] * x + m2[4] * y + m2[12]
        val y1 = m2[1] * x + m2[5] * y + m2[13]
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

    /**
     * 三角化一条几何命令并作为 [GuiTriangleRenderState] 提交。
     * 空几何(三角化结果无顶点)自动跳过;pipeline 首次使用时懒编译。
     * coverage 的屏幕像素距离按「矩阵最大轴缩放 × guiScale」换算。
     */
    private fun addTriangles(
        sink: GuiCommandSink,
        command: DrawCommand,
        scissor: Rect?,
        tessellate: (GeometryTessellator.Sink) -> Unit,
    ) {
        val paint = command.paint ?: return
        // T.24:像素场景(1:1),AA 距离不再乘 guiScale
        val aaScale = matrixScale(command.matrix)
        val key = geometryFingerprint(command, paint, aaScale)
        var vertices = triangleCache[key]
        if (vertices == null) {
            triangleSink.aaScale = aaScale
            triangleSink.clear()
            tessellate(triangleSink)
            if (triangleSink.vertexCount < 3) return
            vertices = triangleSink.toArray()
            if (triangleCache.size > 1024) triangleCache.clear()
            triangleCache[key] = vertices
        }
        MinecraftGuiTriangles.ensureCompiled()
        BlendPipelines.ensureCompiled()
        sink.addElement(
            GuiTriangleRenderState(
                pose = command.matrix.toMatrix3x2f(),
                colorArgb = paint.toArgb(),
                scissor = scissor?.toScreenRectangle(),
                vertices = vertices,
                stroke = paint.style == PaintingStyle.Stroke,
                blendMode = paint.blendMode,
            )
        )
    }

    /**
     * 顶点网格命令回放(T.23):按 [DrawVerticesCommand.vertexMode] 与索引展开
     * 三角形,逐顶点色(源色 × Paint.alpha + colorFilter)提交
     * [GuiTriangleRenderState(vertexColors)] —— GPU 顶点色插值产生渐变。
     *
     * - 无 AA:内部实心 coverage = 大数(顶点网格无轮廓距离场);
     * - 纹理坐标忽略(平台 GUI shader 无纹理采样);
     * - 3D 图层下走 [render3D] 的 CPU 透视路径(见 render3D 内分支)。
     */
    private fun addVertices(
        sink: GuiCommandSink,
        command: MinecraftCanvas.DrawVerticesCommand,
        scissor: Rect?,
    ) {
        val paint = command.paint
        val vc = command.positions.size / 2
        if (vc < 3) return
        val alphaMul = paint.alpha
        val srcColors = command.colors
        // 逐顶点:alpha 叠加 + colorFilter(T.21)
        val vertColors = IntArray(vc) { i -> applyColorFilter(scaleAlpha(srcColors[i], alphaMul), paint.colorFilter) }
        // 预估输出:indices 非空按索引数,否则按顶点数
        val maxTris = if (command.indices.isNotEmpty()) command.indices.size else vc
        var out = FloatArray(maxTris * 9)
        var outColors = IntArray(maxTris * 3)
        var count = 0
        fun emit(ai: Int, bi: Int, ci: Int) {
            if (count + 9 > out.size) {
                out = out.copyOf(out.size * 2)
                outColors = outColors.copyOf(outColors.size * 2)
            }
            out[count] = command.positions[ai * 2]
            out[count + 1] = command.positions[ai * 2 + 1]
            out[count + 2] = OPAQUE_COVERAGE
            outColors[count / 3] = vertColors[ai]
            out[count + 3] = command.positions[bi * 2]
            out[count + 4] = command.positions[bi * 2 + 1]
            out[count + 5] = OPAQUE_COVERAGE
            outColors[count / 3 + 1] = vertColors[bi]
            out[count + 6] = command.positions[ci * 2]
            out[count + 7] = command.positions[ci * 2 + 1]
            out[count + 8] = OPAQUE_COVERAGE
            outColors[count / 3 + 2] = vertColors[ci]
            count += 9
        }
        val idx = command.indices
        if (idx.isNotEmpty()) {
            when (command.vertexMode) {
                VertexMode.Triangles -> {
                    var i = 0
                    while (i + 2 < idx.size) {
                        emit(idx[i].toInt(), idx[i + 1].toInt(), idx[i + 2].toInt()); i += 3
                    }
                }
                VertexMode.TriangleStrip -> {
                    for (i in 0 until idx.size - 2) emit(idx[i].toInt(), idx[i + 1].toInt(), idx[i + 2].toInt())
                }
                VertexMode.TriangleFan -> {
                    for (i in 1 until idx.size - 1) emit(idx[0].toInt(), idx[i].toInt(), idx[i + 1].toInt())
                }
            }
        } else {
            when (command.vertexMode) {
                VertexMode.Triangles -> {
                    var i = 0
                    while (i + 2 < vc) {
                        emit(i, i + 1, i + 2); i += 3
                    }
                }
                VertexMode.TriangleStrip -> {
                    for (i in 0 until vc - 2) emit(i, i + 1, i + 2)
                }
                VertexMode.TriangleFan -> {
                    for (i in 1 until vc - 1) emit(0, i, i + 1)
                }
            }
        }
        if (count < 9) return
        MinecraftGuiTriangles.ensureCompiled()
        BlendPipelines.ensureCompiled()
        sink.addElement(
            GuiTriangleRenderState(
                pose = command.matrix.toMatrix3x2f(),
                colorArgb = -1, // 0xFFFFFFFF;vertexColors 优先,此值仅占位
                scissor = scissor?.toScreenRectangle(),
                vertices = out.copyOf(count),
                blendMode = paint.blendMode,
                vertexColors = outColors.copyOf(count / 3),
            )
        )
    }

    /** 0xAARRGGBB 的 alpha 通道乘系数(用于 drawVertices 逐顶点 alpha 叠加) */
    private fun scaleAlpha(argb: Int, alpha: Float): Int {
        if (alpha >= 1f) return argb
        val a = (((argb ushr 24) and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * 命令几何内容指纹(64 位,碰撞概率 ~2^-64 可忽略):
     * 遍历命令参数 + aaScale + Paint(style/strokeWidth/strokeCap),
     * 内容任何变化 → 指纹变化 → 缓存自动失效。
     */
    private fun geometryFingerprint(command: DrawCommand, paint: PaintSnapshot, aaScale: Float): Long {
        var h1 = 1125899906842597L
        var h2 = 31L
        fun mix(v: Float) {
            h1 = h1 * 31 + v.toRawBits()
            h2 = h2 * 31 + (h1 ushr 1)
        }
        fun mix(i: Int) {
            h1 = h1 * 31 + i
            h2 = h2 * 31 + (h1 ushr 1)
        }
        fun mixB(b: Boolean) = mix(if (b) 1 else 0)
        mix(aaScale)
        mix(if (paint.style == PaintingStyle.Fill) 0 else 1)
        mix(paint.strokeWidth)
        mix(
            when (paint.strokeCap) {
                StrokeCap.Butt -> 0
                StrokeCap.Round -> 1
                StrokeCap.Square -> 2
                else -> 0
            }
        )
        when (command) {
            is DrawCircleCommand -> {
                mix(1); mix(command.centerX); mix(command.centerY); mix(command.radius)
            }
            is DrawOvalCommand -> {
                mix(2); mix(command.left); mix(command.top); mix(command.right); mix(command.bottom)
            }
            is DrawArcCommand -> {
                mix(3); mix(command.left); mix(command.top); mix(command.right); mix(command.bottom)
                mix(command.startAngle); mix(command.sweepAngle); mixB(command.useCenter)
            }
            is DrawRoundRectCommand -> {
                mix(4); mix(command.left); mix(command.top); mix(command.right); mix(command.bottom)
                mix(command.radiusX); mix(command.radiusY)
            }
            is DrawLineCommand -> {
                mix(5); mix(command.p1x); mix(command.p1y); mix(command.p2x); mix(command.p2y)
            }
            is DrawPathCommand -> {
                mix(6)
                for (seg in command.segments) {
                    mix(seg.type.ordinal)
                    mix(seg.points.size)
                    for (v in seg.points) mix(v)
                }
            }
            is DrawPointsCommand -> {
                mix(7)
                mix(
                    when (command.pointMode) {
                        PointMode.Points -> 0
                        PointMode.Lines -> 1
                        PointMode.Polygon -> 2
                        else -> 0
                    }
                )
                for (p in command.points) { mix(p.x); mix(p.y) }
            }
            else -> return 0L // 不缓存(非三角化命令)
        }
        return (h1 shl 1) xor h2
    }

    /** 命令矩阵(列主序 4x4)2D 部分的最大轴缩放 */
    private fun matrixScale(m: FloatArray): Float {
        val scaleX = sqrt(m[0] * m[0] + m[1] * m[1])
        val scaleY = sqrt(m[4] * m[4] + m[5] * m[5])
        return max(scaleX, scaleY)
    }

    /**
     * 把一条文本绘制命令转成 [GuiTextRenderState]。
     *
     * - 文本:命令携带的 MC 样式快照([Style])→ 构造带完整 [Style] 的 [Component],
     *   颜色/加粗/斜体/下划线/删除线/乱码/资源字体全部生效(T.1);
     * - 颜色:样式 color(TextColor.value 为 0xRRGGBB,补 alpha 为不透明;无颜色时用 MC 默认白);
     * - 背景/阴影:对齐 MC 原生(backgroundColor=0 无背景、dropShadow=false),
     *   不再由样式携带(原 McTextStyle 的 background/shadow 字段已随包装移除);
     * - 坐标:命令记录的**行顶** y(MC 的 y 即行顶:下划线画在 y+9、背景为 y..y+9,
     *   见 Font.PreparedTextBuilder.accept),不可再加基线偏移;
     * - pose:命令矩阵(字形顶点经 pose 变换;JOML Matrix3x2f 为列主序构造);
     * - scissor:命令裁剪矩形(记录时已换算为屏幕空间,MC scissor 即屏幕坐标)。
     */
    private fun text(command: DrawTextCommand, scissor: Rect?): GuiTextRenderState {
        val font = Minecraft.getInstance().font
        // 平台适配点:文本颜色 alpha 通道承载图层级透明度(样式色无 alpha 概念,
        // TextColor.value 为 0xRRGGBB)。MC 字形颜色按 0xAARRGGBB 位模式消费。
        val baseColor = command.style.color?.value?.or(0xFF000000.toInt()) ?: 0xFFFFFFFF.toInt()
        val alphaByte = (command.alpha * 255f).roundToInt().coerceIn(0, 255)
        val color = (baseColor and 0x00FFFFFF) or (alphaByte shl 24)
        return GuiTextRenderState(
            font,
            Language.getInstance().getVisualOrder(command.style.toComponent(command.text)),
            command.matrix.toMatrix3x2f(),
            command.x.roundToInt(),
            command.y.roundToInt(),
            color,
            0, // backgroundColor:对齐 MC 原生,无背景
            false, // dropShadow:对齐 MC 原生,不画阴影
            false, // includeEmpty
            scissor?.toScreenRectangle(),
        )
    }

    /**
     * 把一条矩形绘制转成 [BlitRenderState]。
     *
     * - pose:命令记录时的矩阵快照(列主序 4x4)→ JOML Matrix3x2f;
     * - 坐标:场景 px(密度 1)= GUI 单位,四舍五入为 int;
     * - 颜色:Compose Color → 0xAARRGGBB(alpha 叠加 Paint.alpha);
     * - scissor:命令记录时的裁剪矩形(记录时已换算为屏幕空间)。
     */
    private fun blit(
        matrix: FloatArray,
        clip: Rect?,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: PaintSnapshot,
    ): BlitRenderState {
        // Java record 构造器无参数名,必须使用位置参数
        return BlitRenderState(
            // T.22:blendMode ≠ SrcOver 时选对应 blend 变体 pipeline,否则默认 GUI
            BlendPipelines.guiFor(paint.blendMode) ?: RenderPipelines.GUI,
            TextureSetup.noTexture(),
            matrix.toMatrix3x2f(),
            left.roundToInt(),
            top.roundToInt(),
            right.roundToInt(),
            bottom.roundToInt(),
            0f,
            1f,
            0f,
            1f,
            // T.21:颜色滤镜经 PaintSnapshot.toArgb() 应用(alpha + colorFilter)
            paint.toArgb(),
            clip?.toScreenRectangle(),
        )
    }

    /**
     * 把一条图片绘制命令转成 [BlitRenderState](T.16 图片管线)。
     *
     * - 纹理:CPU 像素(0xAARRGGBB)→ [MinecraftImageTextureCache] 上传为
     *   [com.mojang.blaze3d.textures.GpuTexture],按位图身份缓存,首次绘制上传一次;
     * - pipeline:[RenderPipelines.GUI_TEXTURED](带纹理 GUI 管线,与纯色 GUI 不同);
     * - UV:归一化(src 矩形 / 纹理尺寸,MC 语义 0..1);
     * - 颜色:官方 drawImage 语义 **不调制颜色** —— 位图内容直出,恒白色调制,
     *   仅 alpha 生效(paint.alpha 叠加;paint.color 恒为黑,是官方 drawImage
     *   的默认画笔色,不可用作调制色,否则纹理 × (0,0,0,α) 全黑)。
     * - dst 坐标:记录时的局部坐标(整型 IntOffset),变换由 pose(命令矩阵 2D 部分)完成,
     *   与纯色 [blit] 同一提交语义。
     */
    private fun blitImage(command: DrawImageRectCommand, clip: Rect?): BlitRenderState {
        val image = command.image
        val texW = image.width
        val texH = image.height
        val u0 = command.srcOffsetX.toFloat() / texW
        val u1 = (command.srcOffsetX + command.srcWidth).toFloat() / texW
        val v0 = command.srcOffsetY.toFloat() / texH
        val v1 = (command.srcOffsetY + command.srcHeight).toFloat() / texH
        return BlitRenderState(
            RenderPipelines.GUI_TEXTURED,
            MinecraftImageTextureCache.textureSetup(image, command.paint.filterQuality),
            command.matrix.toMatrix3x2f(),
            command.dstOffsetX,
            command.dstOffsetY,
            command.dstOffsetX + command.dstWidth,
            command.dstOffsetY + command.dstHeight,
            u0,
            u1,
            v0,
            v1,
            // 恒白色调制(官方 drawImage 语义:颜色不参与,仅 alpha 生效)
            Color.White.toArgb(command.paint.alpha),
            clip?.toScreenRectangle(),
        )
    }

    /** 屏幕空间裁剪矩形 → [ScreenRectangle](MC scissor,屏幕坐标) */

    /**
     * 命令矩阵(列主序 4x4)→ JOML [Matrix3x2f](列主序 3x2)。
     * androidx Matrix.values 为列主序:values[0]=m00, values[1]=m10, values[4]=m01,
     * values[5]=m11, values[12]=m20, values[13]=m21;
     * JOML 构造器参数序 (m00, m01, m10, m11, m20, m21),注意顺序不同。
     *
     * 平台适配点(T.13 修复):MC 26.2 运行时打包的 JOML,`transformPosition` 为
     * **行主序**实现(x' = m00·x + m10·y + m20,实测见运行时探针),与标准列主序
     * (x' = m00·x + m01·y + m20)相反。若按列主序直接传入,2x2 旋转矩阵会被
     * **转置**:旋转方向反转,且绕 pivot 旋转时中心随角度摆动(幅度 2·|sinθ|·|p|,
     * 表现为"公转"观感)。因此传入时交换 m01/m10(即对 2x2 预转置),抵消其行主序行为。
     */
    private fun FloatArray.toMatrix3x2f(): Matrix3x2f =
        Matrix3x2f(this[0], this[1], this[4], this[5], this[12], this[13])

    private fun Rect.toScreenRectangle(): ScreenRectangle = ScreenRectangle(
        left.roundToInt(),
        top.roundToInt(),
        width.roundToInt(),
        height.roundToInt(),
    )

    /** Compose Color(Float 通道)→ Minecraft GUI 0xAARRGGBB */
    private fun Color.toArgb(alphaMultiplier: Float): Int {
        val a = (alpha * alphaMultiplier).coerceIn(0f, 1f)
        return ((a * 255f).roundToInt() shl 24) or
            ((red * 255f).roundToInt() shl 16) or
            ((green * 255f).roundToInt() shl 8) or
            (blue * 255f).roundToInt()
    }

    /**
     * Paint 快照 → 最终 0xAARRGGBB(alpha 叠加 + T.21 颜色滤镜)。
     *
     * T.21 限制:[blendMode] 已透传到快照与回放链路,但渲染端固定使用
     * GUI 管线的 TRANSLUCENT alpha 合成 —— MC 26.2 的 blend 函数在
     * pipeline 编译期固定,draw 级无法逐命令切换,仅 SrcOver(默认)生效;
     * 其余模式需自建 blend pipeline / 离屏合成(待架构决策,见 AGENTS.md)。
     */
    private fun PaintSnapshot.toArgb(): Int = applyColorFilter(color.toArgb(alpha), colorFilter)

    /**
     * 应用颜色滤镜(T.21,draw 级):
     * - [NativeColorFilter.colorMatrix]:4x5 颜色矩阵(直通 RGBA,0..255);
     * - [NativeColorFilter.color](调制色,BlendModeColorFilter 的 tint /
     *   LightingColorFilter 的 multiply):out = src × color + add × 255
     *   (tint 按 [NativeColorFilter.blendMode] 与底色混合,SrcIn 非恒色时
     *   退化为 lerp(src, color, color.a);官方离屏语义见 AGENTS.md);
     * - 其余:原样返回。
     */
    private fun applyColorFilter(argb: Int, filter: NativeColorFilter?): Int {
        if (filter == null) return argb
        val c = filter.color
        if (c != null) {
            // tint(SrcIn 通用):结果 = color 调制,alpha 保留源
            if (filter.colorMatrix == null && filter.add == null) {
                // BlendModeColorFilter:color 与底色按 blendMode 合成(draw 级近似)
                val sa = (c.alpha * 255f).roundToInt()
                if (sa >= 255 && filter.blendMode == BlendMode.SrcIn) {
                    // SrcIn + 不透明 tint → 直接替换为 tint 色
                    return ((argb ushr 24) shl 24) or (c.toArgb(1f) and 0x00FFFFFF)
                }
                val sr = (c.red * 255f).roundToInt()
                val sg = (c.green * 255f).roundToInt()
                val sb = (c.blue * 255f).roundToInt()
                val dr = (argb shr 16) and 0xFF
                val dg = (argb shr 8) and 0xFF
                val db = argb and 0xFF
                val da = (argb ushr 24) and 0xFF
                return when (filter.blendMode) {
                    // SrcOver:出 = src×sa + dst×(1-sa)(draw 级近似,无背景知识)
                    BlendMode.SrcOver -> {
                        val ia = 255 - sa
                        val r = (sr * sa + dr * ia) / 255
                        val g = (sg * sa + dg * ia) / 255
                        val b = (sb * sa + db * ia) / 255
                        (da shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    // Modulate:出 = src × dst / 255
                    BlendMode.Modulate -> {
                        val r = (sr * dr) / 255
                        val g = (sg * dg) / 255
                        val b = (sb * db) / 255
                        (da shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    // 其他 blendMode 的 draw 级近似:SrcIn 之外回退到 tint 色 + 源 alpha
                    else -> (da shl 24) or (sr shl 16) or (sg shl 8) or sb
                }
            }
            // LightingColorFilter:out = src × multiply + add × 255
            val add = filter.add
            val a = ((argb ushr 24) and 0xFF)
            val r = ((argb shr 16) and 0xFF)
            val g = ((argb shr 8) and 0xFF)
            val b = (argb and 0xFF)
            fun mix(v: Int, mul: Float, aoff: Float): Int =
                (v * mul + aoff * 255f).roundToInt().coerceIn(0, 255)
            return (mix(a, c.alpha, add?.alpha ?: 0f) shl 24) or
                (mix(r, c.red, add?.red ?: 0f) shl 16) or
                (mix(g, c.green, add?.green ?: 0f) shl 8) or
                mix(b, c.blue, add?.blue ?: 0f)
        }
        val m = filter.colorMatrix
        if (m != null) {
            val a = (argb ushr 24) and 0xFF
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val v = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat())
            val out = IntArray(4)
            for (row in 0 until 4) {
                // 偏移列(m[row,4])本身已是 0..255 刻度(如反相矩阵的 255),不再乘 255
                var acc = m[row, 4]
                var col = 0
                while (col < 4) {
                    acc += m[row, col] * v[col]
                    col++
                }
                out[row] = acc.roundToInt().coerceIn(0, 255)
            }
            return (out[3] shl 24) or (out[0] shl 16) or (out[1] shl 8) or out[2]
        }
        return argb
    }
}
