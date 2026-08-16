package moe.forpleuvoir.compose_minecraft.platform.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.MinecraftCanvas.DrawArcCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCircleCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawLineCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawOvalCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPathCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPointsCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRoundRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawTextCommand
import androidx.compose.ui.graphics.MinecraftCanvas.PaintSnapshot
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.locale.Language
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

    /** 把 [canvas] 中的命令逐条提交到 [renderState](GUI 坐标 = 场景 px,密度 1) */
    fun render(canvas: MinecraftCanvas, renderState: GuiRenderState) {
        val windowState = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState
        val windowWidth = windowState.width / windowState.guiScale
        val windowHeight = windowState.height / windowState.guiScale
        val guiScale = windowState.guiScale

        for (command in canvas.commands()) {
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
                is DrawRectCommand -> renderState.addBlitToCurrentLayer(
                    blit(
                        command.matrix, scissor,
                        command.left, command.top, command.right, command.bottom,
                        command.paint.color, command.paint.alpha,
                    )
                )
                is DrawRoundRectCommand -> {
                    // 圆角为 0(或小于 1px)时退化为矩形;带圆角走三角化
                    if (command.radiusX <= 1f && command.radiusY <= 1f) {
                        renderState.addBlitToCurrentLayer(
                            blit(
                                command.matrix, scissor,
                                command.left, command.top, command.right, command.bottom,
                                command.paint.color, command.paint.alpha,
                            )
                        )
                    } else {
                        addTriangles(renderState, command, scissor, guiScale) { sink ->
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
                is DrawOvalCommand -> addTriangles(renderState, command, scissor, guiScale) { sink ->
                    GeometryTessellator.oval(
                        command.left, command.top, command.right, command.bottom,
                        fill = command.paint.style == PaintingStyle.Fill,
                        strokeWidth = command.paint.strokeWidth,
                        sink = sink,
                    )
                }
                is DrawCircleCommand -> addTriangles(renderState, command, scissor, guiScale) { sink ->
                    GeometryTessellator.circle(
                        command.centerX, command.centerY, command.radius,
                        fill = command.paint.style == PaintingStyle.Fill,
                        strokeWidth = command.paint.strokeWidth,
                        sink = sink,
                    )
                }
                is DrawArcCommand -> addTriangles(renderState, command, scissor, guiScale) { sink ->
                    GeometryTessellator.arc(
                        command.left, command.top, command.right, command.bottom,
                        command.startAngle, command.sweepAngle, command.useCenter,
                        fill = command.paint.style == PaintingStyle.Fill,
                        strokeWidth = command.paint.strokeWidth,
                        sink = sink,
                    )
                }
                is DrawLineCommand -> addTriangles(renderState, command, scissor, guiScale) { sink ->
                    GeometryTessellator.line(
                        command.p1x, command.p1y, command.p2x, command.p2y,
                        command.paint.strokeWidth,
                        command.paint.strokeCap,
                        sink = sink,
                    )
                }
                is DrawPathCommand -> addTriangles(renderState, command, scissor, guiScale) { sink ->
                    GeometryTessellator.path(
                        command.segments,
                        fill = command.paint.style == PaintingStyle.Fill,
                        strokeWidth = command.paint.strokeWidth,
                        cap = command.paint.strokeCap,
                        sink = sink,
                    )
                }
                is DrawPointsCommand -> addTriangles(renderState, command, scissor, guiScale) { sink ->
                    GeometryTessellator.points(
                        command.pointMode, command.points,
                        command.paint.strokeWidth,
                        command.paint.strokeCap,
                        sink = sink,
                    )
                }
                is DrawTextCommand -> renderState.addText(text(command, scissor))
                is MinecraftCanvas.DrawImageRectCommand -> Unit // 图片:后续阶段(像素上传为 GpuTexture)
            }
        }
    }

    /**
     * 三角化一条几何命令并作为 [GuiTriangleRenderState] 提交。
     * 空几何(三角化结果无顶点)自动跳过;pipeline 首次使用时懒编译。
     * coverage 的屏幕像素距离按「矩阵最大轴缩放 × guiScale」换算。
     */
    private fun addTriangles(
        renderState: GuiRenderState,
        command: DrawCommand,
        scissor: Rect?,
        guiScale: Int,
        tessellate: (GeometryTessellator.Sink) -> Unit,
    ) {
        val paint = command.paint ?: return
        val aaScale = matrixScale(command.matrix) * guiScale
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
        renderState.addGuiElement(
            GuiTriangleRenderState(
                pose = command.matrix.toMatrix3x2f(),
                colorArgb = paint.color.toArgb(paint.alpha),
                scissor = scissor?.toScreenRectangle(),
                vertices = vertices,
                stroke = paint.style == PaintingStyle.Stroke,
            )
        )
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
        color: Color,
        alpha: Float,
    ): BlitRenderState {
        // Java record 构造器无参数名,必须使用位置参数
        return BlitRenderState(
            RenderPipelines.GUI,
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
            color.toArgb(alpha),
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
}
