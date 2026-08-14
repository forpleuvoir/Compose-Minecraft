package moe.forpleuvoir.compose_minecraft.minecraft

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import net.minecraft.network.chat.FormattedText
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
        for (command in canvas.commands()) {
            when (command) {
                is DrawRectCommand -> renderState.addBlitToCurrentLayer(
                    blit(
                        command.matrix, command.clip,
                        command.left, command.top, command.right, command.bottom,
                        command.paint.color, command.paint.alpha,
                    )
                )
                is DrawRoundRectCommand -> {
                    // 圆角为 0(或小于 1px)时退化为矩形;带圆角需三角化,后续阶段补齐
                    if (command.radiusX <= 1f && command.radiusY <= 1f) {
                        renderState.addBlitToCurrentLayer(
                            blit(
                                command.matrix, command.clip,
                                command.left, command.top, command.right, command.bottom,
                                command.paint.color, command.paint.alpha,
                            )
                        )
                    }
                }
                is DrawTextCommand -> renderState.addText(text(command))
                else -> Unit // 圆/椭圆/弧/线/路径/点/图片:后续阶段
            }
        }
    }

    /**
     * 把一条文本绘制命令转成 [GuiTextRenderState]。
     *
     * - 字体:MC 默认 [Font](9px 字形,阶段 E 方案 A:字号忽略,布局度量同为 MC 字形);
     * - 坐标:命令记录的行顶 y + 基线偏移(MC 默认字体 ascent=7,行顶 → 基线);
     * - pose:命令矩阵(字形顶点经 pose 变换);
     * - scissor:命令裁剪矩形。
     */
    private fun text(command: DrawTextCommand): GuiTextRenderState {
        val font = Minecraft.getInstance().font
        return GuiTextRenderState(
            font,
            Language.getInstance().getVisualOrder(FormattedText.of(command.text)),
            Matrix3x2f(
                command.matrix[0], command.matrix[1],
                command.matrix[4], command.matrix[5],
                command.matrix[12], command.matrix[13],
            ),
            command.x.roundToInt(),
            command.y.roundToInt() + MC_TEXT_BASELINE_OFFSET,
            command.color.toArgb(1f),
            0, // backgroundColor:无背景
            false, // dropShadow:阶段 E 暂不绘制阴影
            false, // includeEmpty
            command.clip?.let {
                ScreenRectangle(
                    it.left.roundToInt(),
                    it.top.roundToInt(),
                    it.width.roundToInt(),
                    it.height.roundToInt(),
                )
            },
        )
    }

    /**
     * 把一条矩形绘制转成 [BlitRenderState]。
     *
     * - pose:命令记录时的矩阵快照(列主序 4x4)→ JOML Matrix3x2f;
     * - 坐标:场景 px(密度 1)= GUI 单位,四舍五入为 int;
     * - 颜色:Compose Color → 0xAARRGGBB(alpha 叠加 Paint.alpha);
     * - scissor:命令记录时的裁剪矩形 → ScreenRectangle。
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
            Matrix3x2f(matrix[0], matrix[1], matrix[4], matrix[5], matrix[12], matrix[13]),
            left.roundToInt(),
            top.roundToInt(),
            right.roundToInt(),
            bottom.roundToInt(),
            0f,
            1f,
            0f,
            1f,
            color.toArgb(alpha),
            clip?.let {
                ScreenRectangle(
                    it.left.roundToInt(),
                    it.top.roundToInt(),
                    it.width.roundToInt(),
                    it.height.roundToInt(),
                )
            },
        )
    }

    /** Compose Color(Float 通道)→ Minecraft GUI 0xAARRGGBB */
    private fun Color.toArgb(alphaMultiplier: Float): Int {
        val a = (alpha * alphaMultiplier).coerceIn(0f, 1f)
        return ((a * 255f).roundToInt() shl 24) or
            ((red * 255f).roundToInt() shl 16) or
            ((green * 255f).roundToInt() shl 8) or
            (blue * 255f).roundToInt()
    }
}

/**
 * MC 默认字体 ascent(行顶到基线的偏移)。
 * 布局行高 = Font.lineHeight(9),字形高度 8,基线 = 行顶 + 7。
 */
private const val MC_TEXT_BASELINE_OFFSET = 7
