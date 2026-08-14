package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRoundRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawTextCommand
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.locale.Language
import org.joml.Matrix3x2f
import kotlin.math.roundToInt

/**
 * 把 [MinecraftCanvas] 记录的绘制命令提交到 Minecraft 当前帧的 [GuiRenderState]。
 *
 * 阶段 C:把 [MinecraftCanvas] 记录的绘制命令提交到 Minecraft 当前帧的 [GuiRenderState]。
 * 第一版支持:
 * - 矩形(含圆角为 0 的圆角矩形)→ [BlitRenderState] 纯色四边形;
 * - 文本 → [GuiTextRenderState](阶段 E,MC Font 字形,布局度量同为 MC 字形)。
 * 直接进入当前帧 GUI 渲染;不创建任何额外 RenderTarget/离屏纹理。
 *
 * 尚未支持(后续阶段):
 * - 圆/椭圆/弧/线/路径/点 → 需要三角化;
 * - 图片(drawImageRect)→ 需要把 [androidx.compose.ui.graphics.MinecraftImageBitmap]
 *   的 CPU 像素上传为 GpuTexture。
 *
 * 内部类:MinecraftCanvas 及其命令模型为 internal,业务代码经 MinecraftComposeScene 使用。
 */
internal class MinecraftRenderContext {

    /** 把 [canvas] 中的命令逐条提交到 [renderState](GUI 坐标 = 场景 px,密度 1) */
    fun render(canvas: MinecraftCanvas, renderState: GuiRenderState) {
        val windowState = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState
        val windowWidth = windowState.width / windowState.guiScale
        val windowHeight = windowState.height / windowState.guiScale

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
                    // 圆角为 0(或小于 1px)时退化为矩形;带圆角需三角化,后续阶段补齐
                    if (command.radiusX <= 1f && command.radiusY <= 1f) {
                        renderState.addBlitToCurrentLayer(
                            blit(
                                command.matrix, scissor,
                                command.left, command.top, command.right, command.bottom,
                                command.paint.color, command.paint.alpha,
                            )
                        )
                    }
                }
                is DrawTextCommand -> renderState.addText(text(command, scissor))
                else -> Unit // 圆/椭圆/弧/线/路径/点/图片:后续阶段
            }
        }
    }

    /**
     * 把一条文本绘制命令转成 [GuiTextRenderState]。
     *
     * - 文本:命令携带的 MC 样式快照([McTextStyle])→ 构造带完整 [Style] 的 [Component],
     *   颜色/加粗/斜体/下划线/删除线/乱码/资源字体全部生效(T.1);
     * - 颜色:样式 color(0xAARRGGBB,alpha 生效);背景色/阴影由样式 backgroundColor/dropShadow 提供;
     * - 坐标:命令记录的**行顶** y(MC 的 y 即行顶:下划线画在 y+9、背景为 y..y+9,
     *   见 Font.PreparedTextBuilder.accept),不可再加基线偏移;
     * - pose:命令矩阵(字形顶点经 pose 变换;JOML Matrix3x2f 为列主序构造);
     * - scissor:命令裁剪矩形(记录时已换算为屏幕空间,MC scissor 即屏幕坐标)。
     */
    private fun text(command: DrawTextCommand, scissor: Rect?): GuiTextRenderState {
        val font = Minecraft.getInstance().font
        return GuiTextRenderState(
            font,
            Language.getInstance().getVisualOrder(command.style.toComponent(command.text)),
            command.matrix.toMatrix3x2f(),
            command.x.roundToInt(),
            command.y.roundToInt(),
            command.style.color.toArgb(1f),
            command.style.background.toArgb(1f),
            command.style.shadow,
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
     */
    private fun FloatArray.toMatrix3x2f(): Matrix3x2f =
        Matrix3x2f(this[0], this[4], this[1], this[5], this[12], this[13])

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
