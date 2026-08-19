package moe.forpleuvoir.compose_minecraft.platform.render.backend

import androidx.compose.ui.graphics.DrawCustomCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawArcCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCircleCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawGradientRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawImageRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawLineCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawOvalCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPathCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawPointsCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawRoundRectCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawShadowCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawTextCommand
import androidx.compose.ui.graphics.MinecraftCanvas.DrawVerticesCommand

/**
 * 渲染后端接口(T.37:开放接口,支持自定义命令扩展)。
 *
 * 每个命令类型一个方法 —— 新增后端须实现全部方法。
 * [drawCustom] 有默认空实现,用于处理 [DrawCustomCommand]。
 *
 * 当前实现:
 * - [GuiStateBackend]:2D GUI 回放(命令 → GuiElementRenderState);
 * - [RasterBackend]:CPU 快照(命令 → 像素缓冲 IntArray);
 * - 3D 透视路径(P3):复用同一分派,后端内做 CPU 顶点变换。
 */
internal interface GeometryBackend {
    fun drawRect(cmd: DrawRectCommand)
    fun drawRoundRect(cmd: DrawRoundRectCommand)
    fun drawOval(cmd: DrawOvalCommand)
    fun drawCircle(cmd: DrawCircleCommand)
    fun drawArc(cmd: DrawArcCommand)
    fun drawLine(cmd: DrawLineCommand)
    fun drawPath(cmd: DrawPathCommand)
    fun drawPoints(cmd: DrawPointsCommand)
    fun drawText(cmd: DrawTextCommand)
    fun drawGradientRect(cmd: DrawGradientRectCommand)
    fun drawShadow(cmd: DrawShadowCommand)
    fun drawImageRect(cmd: DrawImageRectCommand)
    fun drawVertices(cmd: DrawVerticesCommand)

    /** 自定义绘制命令(T.37):默认空实现,由 [GuiStateBackend] 重写。 */
    fun drawCustom(cmd: DrawCustomCommand)
}

/**
 * 命令分派器(P2):唯一的一处 `when(command)`,按命令类型把 [DrawCommand]
 * 分派给当前 [GeometryBackend]。所有渲染后端(GUI 回放 / CPU 快照 / 3D 透视)
 * 共用本分派,新增命令类型只改这里 + 各后端实现。
 */
internal object CommandDispatcher {
    fun dispatch(cmd: DrawCommand, backend: GeometryBackend) {
        when (cmd) {
            is DrawRectCommand         -> backend.drawRect(cmd)
            is DrawRoundRectCommand    -> backend.drawRoundRect(cmd)
            is DrawOvalCommand         -> backend.drawOval(cmd)
            is DrawCircleCommand       -> backend.drawCircle(cmd)
            is DrawArcCommand          -> backend.drawArc(cmd)
            is DrawLineCommand         -> backend.drawLine(cmd)
            is DrawPathCommand         -> backend.drawPath(cmd)
            is DrawPointsCommand       -> backend.drawPoints(cmd)
            is DrawTextCommand         -> backend.drawText(cmd)
            is DrawGradientRectCommand -> backend.drawGradientRect(cmd)
            is DrawShadowCommand       -> backend.drawShadow(cmd)
            is DrawImageRectCommand    -> backend.drawImageRect(cmd)
            is DrawVerticesCommand     -> backend.drawVertices(cmd)
            is DrawCustomCommand       -> backend.drawCustom(cmd)
        }
    }
}