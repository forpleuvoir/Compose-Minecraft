package moe.forpleuvoir.compose_minecraft.platform.render.backend

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
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
import androidx.compose.ui.graphics.MinecraftCanvas.PaintSnapshot
import androidx.compose.ui.graphics.PointMode

/**
 * 渲染后端接口(开放接口,支持自定义命令扩展)。
 *
 * 每个命令类型一个方法 —— 新增后端须实现全部方法。
 * [drawCustom] 有默认空实现,用于处理 [DrawCustomCommand]。
 *
 * 当前实现:
 * - [GuiStateBackend]:2D GUI 回放(命令 → GuiElementRenderState);
 * - [RasterBackend]:CPU 快照(命令 → 像素缓冲 IntArray);
 * - 3D 透视路径:复用同一分派,后端内做 CPU 顶点变换。
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

    /** 自定义绘制命令:默认空实现,由 [GuiStateBackend] 重写。 */
    fun drawCustom(cmd: DrawCustomCommand)
}

/**
 * 命令分派器:唯一的一处 `when(command)`,按命令类型把 [DrawCommand]
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

/**
 * 连续线段合并(平台适配点,修复逐段 drawLine 拼接曲线的接缝):
 *
 * 每个 drawLine 是独立命令(独立 butt 端帽 + 端帽 fringe):相邻段在拐折处
 * 留下外侧楔形缺口,端帽 fringe 重叠区 SrcOver 双混合 → 曲线「缺口 /
 * 粗细不匀」。这里把「同 paint、同矩阵、同裁剪、首尾相接」的连续
 * [DrawLineCommand] 合并为一条 Polygon 折线命令,由描边带统一 join
 * (两端仍按原 cap 端帽),API 不变、仅修渲染效果。
 *
 * 保守条件(不满足则保持原样):shader / colorFilter / pathEffect 均为 null,
 * blendMode 为 SrcOver,layer3D 为 null,且端点严格相等(浮点完全一致)。
 */
internal object ConnectedLineMerger {

    fun merge(commands: List<DrawCommand>): List<DrawCommand> {
        val out = ArrayList<DrawCommand>(commands.size)
        var changed = false
        var i = 0
        while (i < commands.size) {
            val first = commands[i]
            if (first !is DrawLineCommand || !mergeable(first)) {
                out.add(first)
                i++
                continue
            }
            var endX = first.p2x
            var endY = first.p2y
            var j = i + 1
            while (j < commands.size) {
                val next = commands[j]
                if (next is DrawLineCommand && mergeable(next) && sameFrame(first, next) &&
                    next.p1x == endX && next.p1y == endY
                ) {
                    endX = next.p2x
                    endY = next.p2y
                    j++
                } else {
                    break
                }
            }
            if (j == i + 1) {
                out.add(first)
                i++
                continue
            }
            val pts = ArrayList<Offset>(j - i + 1)
            pts.add(Offset(first.p1x, first.p1y))
            for (k in i until j) {
                val c = commands[k] as DrawLineCommand
                pts.add(Offset(c.p2x, c.p2y))
            }
            out.add(DrawPointsCommand(first.matrix, first.clip, first.paint, PointMode.Polygon, pts))
            changed = true
            i = j
        }
        return if (changed) out else commands
    }

    /** 参与合并的前提:无渐变/滤镜/几何效果/特殊混合/3D(这些情况逐段语义保持原样) */
    private fun mergeable(cmd: DrawLineCommand): Boolean {
        val p = cmd.paint
        return cmd.layer3D == null &&
                p.shader == null && p.colorFilter == null && p.pathEffect == null &&
                p.blendMode == BlendMode.SrcOver
    }

    /** 同一「绘制帧」:矩阵/裁剪/paint 值全等(合并后视觉参数不变) */
    private fun sameFrame(a: DrawLineCommand, b: DrawLineCommand): Boolean {
        if (!a.matrix.contentEquals(b.matrix) || a.clip != b.clip) return false
        val pa: PaintSnapshot = a.paint
        val pb: PaintSnapshot = b.paint
        return pa.color == pb.color && pa.alpha == pb.alpha &&
                pa.style == pb.style && pa.strokeWidth == pb.strokeWidth &&
                pa.strokeCap == pb.strokeCap && pa.filterQuality == pb.filterQuality &&
                pa.strokeJoin == pb.strokeJoin && pa.strokeMiterLimit == pb.strokeMiterLimit
    }
}