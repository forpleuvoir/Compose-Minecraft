package moe.forpleuvoir.compose_minecraft.platform.render.text

import androidx.compose.ui.graphics.MinecraftCanvas.DrawTextCommand
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.render.toMatrix3x2f
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toComponent
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.locale.Language
import net.minecraft.network.chat.FontDescription
import kotlin.math.roundToInt

/**
 * 位图通道(MC_FREETYPE / MC_BITMAP)统一提交助手(P2-B4,总设计 A4/I4):
 *
 * - 布局坐标为**最终像素空间**(emPx 绝对值);字形网格由 pose 承载缩放比
 *   `k = emPx / providerEmPx`(I3:缩放仅存在于位图通道 pose 的唯一一处);
 * - 原版把字形基线硬编码在「行顶 + 7」网格像素([com.mojang.blaze3d.font.GlyphBitmap]
 *   语义),补偿在此**一次性**完成 —— 全系统不再有第二份 7f 锚点常量;
 * - 缺字段的字体描述替换由调用方决定(经 [ResolvedFont.ownerOf] 解析归属)。
 */
internal object VanillaBitmapSubmitter {

    /** 原版字形基线锚点(网格像素;唯一允许出现的一份) */
    internal const val VANILLA_GLYPH_ANCHOR_GRID = 7f

    /**
     * 提交一段位图渲染文本。
     *
     * @param font 该段归属字体绑定(emPx/providerEmPx 决定 pose 比与锚点补偿)
     * @param fontId 提交给原版的字体描述(主段 = 原样式字体;回退段 = 归属字体 id;
     *   minecraft:default 链的位图终端即 [FontDescription.DEFAULT])
     * @param xBaseline 本段起点 x(最终像素空间)
     * @param yBaseline 本段基线 y(最终像素空间)
     */
    fun submit(
        sink: moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink,
        text: String,
        style: net.minecraft.network.chat.Style,
        font: ResolvedFont,
        fontId: FontDescription,
        xBaseline: Float,
        yBaseline: Float,
        colorArgb: Int,
        matrix: FloatArray,
        scissor: ScreenRectangle?,
        extraPoseScale: Float = 1f,
    ) {
        val k = (font.emPx / font.font.providerEmPx) * extraPoseScale
        if (k <= 0f || !k.isFinite()) return
        // 网格空间局部坐标:pose(k) 变换后恰好落在最终像素位置
        val localX = xBaseline / k
        val localY = yBaseline / k - VANILLA_GLYPH_ANCHOR_GRID
        val pose = matrix.toMatrix3x2f().apply { scale(k, k) }
        sink.addText(
            GuiTextRenderState(
                mc.font,
                Language.getInstance().getVisualOrder(style.withFont(fontId).toComponent(text)),
                pose,
                localX.roundToInt(),
                localY.roundToInt(),
                colorArgb,
                0,
                false,
                false,
                scissor,
            )
        )
    }

    /** 便捷:从命令提取公共参数提交(回退段/渐变字符共用)。 */
    fun submitFor(
        cmd: DrawTextCommand,
        sink: moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink,
        scissor: ScreenRectangle?,
        text: String,
        font: ResolvedFont,
        fontId: FontDescription,
        xBaseline: Float,
        yBaseline: Float,
        colorArgb: Int,
    ) = submit(
        sink = sink,
        text = text,
        style = cmd.style,
        font = font,
        fontId = fontId,
        xBaseline = xBaseline,
        yBaseline = yBaseline,
        colorArgb = colorArgb,
        matrix = cmd.matrix,
        scissor = scissor,
    )
}
