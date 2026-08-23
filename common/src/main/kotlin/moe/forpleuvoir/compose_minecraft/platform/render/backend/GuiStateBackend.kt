package moe.forpleuvoir.compose_minecraft.platform.render.backend

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.MinecraftCanvas.*
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.render.CustomDrawContext
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderPlugins
import moe.forpleuvoir.compose_minecraft.platform.render.paint.ColorEvaluator
import moe.forpleuvoir.compose_minecraft.platform.render.paint.toArgb
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.GeometryTessellator
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.GuiTriangleRenderState
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.MinecraftGuiTriangles
import moe.forpleuvoir.compose_minecraft.platform.render.renderer.MinecraftShadowRenderer
import moe.forpleuvoir.compose_minecraft.platform.render.text.*
import moe.forpleuvoir.compose_minecraft.platform.render.toMatrix3x2f
import moe.forpleuvoir.compose_minecraft.platform.render.toScreenRectangle
import moe.forpleuvoir.compose_minecraft.platform.render.util.BlendPipelines
import moe.forpleuvoir.compose_minecraft.platform.render.util.MinecraftImageTextureCache
import moe.forpleuvoir.compose_minecraft.platform.ui.text.fontOriginal
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toComponent
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.locale.Language
import net.minecraft.network.chat.FontDescription
import org.joml.Matrix3x2f
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 2D GUI 回放后端(P2,自 `MinecraftRenderContext.render()` 2D 分支原样搬移,2025-08)。
 *
 * 把 [DrawCommand] 提交为 MC 的 GuiElementRenderState(经 [GuiCommandSink]):
 * - 纯色矩形 → [BlitRenderState] 最快路径;其余几何 → [GeometryTessellator] CPU
 *   三角化 → [GuiTriangleRenderState](TRIANGLES pipeline);
 * - 文本 → [GuiTextRenderState];阴影 → [MinecraftShadowRenderer];图片 → blit 纹理;
 * - 三角形缓存([triangleCache],按几何指纹)与输出缓冲复用([triangleSink])
 *   保持原实现(行为零变更,P2 只搬移)。
 *
 * [sink] 为当前帧提交目标,由 [moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderContext]
 * 每帧设置(收集器实例稳定)。
 */
internal class GuiStateBackend : GeometryBackend {

    /** 当前帧提交目标(由 MinecraftRenderContext.render 每帧设置) */
    internal var sink: GuiCommandSink? = null

    /** 三角化输出缓冲(每帧复用,避免分配) */
    private val triangleSink = GeometryTessellator.Sink()

    /**
     * 三角化结果缓存:按「命令几何内容 + aaScale + Paint 参数」指纹复用顶点数组。
     * 重复图形(相同内容与缩放)命中缓存直接复用,不再每帧重新三角化;
     * 内容变化(坐标/参数/缩放)指纹随之变化 → 自动失效重建。
     * 缓存数组被 [GuiTriangleRenderState] 只读共享;膨胀超限时整体清空。
     */
    private val triangleCache = HashMap<Long, FloatArray>()

    // ── GeometryBackend ─────────────────────────────────────────────────

    override fun drawRect(cmd: DrawRectCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        // 平台适配点(T.33 修复):矩形必须按 paint.style 分流 —— Fill 走 blit
        // 实心四边形(最快路径);Stroke 走三角化描边带(GeometryTessellator.
        // roundRect radius=0 退化为矩形描边)。原实现无条件 blit,导致
        // border(1.dp, color) 的描边矩形被画成整块实心色块,覆盖内部内容。
        if (cmd.paint.style == PaintingStyle.Fill && cmd.paint.shader == null) {
            sink.addElement(
                blit(
                    cmd.matrix, scissor,
                    cmd.left, cmd.top, cmd.right, cmd.bottom,
                    cmd.paint,
                )
            )
        } else {
            addTriangles(sink, cmd, scissor) { sink ->
                val shader = cmd.paint.shader
                if (shader is LinearGradientShaderData && cmd.paint.style == PaintingStyle.Fill) {
                    val l = cmd.left
                    val t = cmd.top
                    val r = cmd.right
                    val b = cmd.bottom
                    val segs = (shader.colors.size - 1).coerceIn(1, 32)
                    val gradDx = shader.to.x - shader.from.x
                    val gradDy = shader.to.y - shader.from.y
                    // 沿梯度主方向细分
                    if (kotlin.math.abs(gradDx) >= kotlin.math.abs(gradDy)) {
                        for (i in 0 until segs) {
                            val x0 = l + (r - l) * i / segs
                            val x1 = l + (r - l) * (i + 1) / segs
                            sink.quad(x0, t, x1, t, x1, b, x0, b)
                        }
                    } else {
                        for (i in 0 until segs) {
                            val y0 = t + (b - t) * i / segs
                            val y1 = t + (b - t) * (i + 1) / segs
                            sink.quad(l, y0, r, y0, r, y1, l, y1)
                        }
                    }
                } else {
                    GeometryTessellator.roundRect(
                        cmd.left, cmd.top, cmd.right, cmd.bottom,
                        0f, 0f,
                        fill = cmd.paint.style == PaintingStyle.Fill,
                        strokeWidth = cmd.paint.strokeWidth,
                        sink = sink,
                    )
                }
            }
        }
    }

    override fun drawRoundRect(cmd: DrawRoundRectCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        // 圆角为 0(或小于 1px)时退化为矩形;带圆角走三角化。
        // Stroke 样式一律走三角化(描边带),不能走实心 blit。
        // 渐变着色器存在时强制走三角化(顶点色插值),不能走实心 blit。
        if (cmd.radiusX <= 1f && cmd.radiusY <= 1f &&
            cmd.paint.style == PaintingStyle.Fill && cmd.paint.shader == null
        ) {
            sink.addElement(
                blit(
                    cmd.matrix, scissor,
                    cmd.left, cmd.top, cmd.right, cmd.bottom,
                    cmd.paint,
                )
            )
        } else {
            addTriangles(sink, cmd, scissor) { sink ->
                GeometryTessellator.roundRect(
                    cmd.left, cmd.top, cmd.right, cmd.bottom,
                    cmd.radiusX, cmd.radiusY,
                    fill = cmd.paint.style == PaintingStyle.Fill,
                    strokeWidth = cmd.paint.strokeWidth,
                    sink = sink,
                )
            }
        }
    }

    override fun drawOval(cmd: DrawOvalCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        addTriangles(sink, cmd, scissor) { sink ->
            GeometryTessellator.oval(
                cmd.left, cmd.top, cmd.right, cmd.bottom,
                fill = cmd.paint.style == PaintingStyle.Fill,
                strokeWidth = cmd.paint.strokeWidth,
                sink = sink,
            )
        }
    }

    override fun drawCircle(cmd: DrawCircleCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        addTriangles(sink, cmd, scissor) { sink ->
            GeometryTessellator.circle(
                cmd.centerX, cmd.centerY, cmd.radius,
                fill = cmd.paint.style == PaintingStyle.Fill,
                strokeWidth = cmd.paint.strokeWidth,
                sink = sink,
            )
        }
    }

    override fun drawArc(cmd: DrawArcCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        addTriangles(sink, cmd, scissor) { sink ->
            GeometryTessellator.arc(
                cmd.left, cmd.top, cmd.right, cmd.bottom,
                cmd.startAngle, cmd.sweepAngle, cmd.useCenter,
                fill = cmd.paint.style == PaintingStyle.Fill,
                strokeWidth = cmd.paint.strokeWidth,
                sink = sink,
            )
        }
    }

    override fun drawLine(cmd: DrawLineCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        addTriangles(sink, cmd, scissor) { sink ->
            GeometryTessellator.line(
                cmd.p1x, cmd.p1y, cmd.p2x, cmd.p2y,
                cmd.paint.strokeWidth,
                cmd.paint.strokeCap,
                sink = sink,
            )
        }
    }

    override fun drawPath(cmd: DrawPathCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        addTriangles(sink, cmd, scissor) { sink ->
            GeometryTessellator.path(
                cmd.segments,
                fill = cmd.paint.style == PaintingStyle.Fill,
                strokeWidth = cmd.paint.strokeWidth,
                cap = cmd.paint.strokeCap,
                sink = sink,
            )
        }
    }

    override fun drawPoints(cmd: DrawPointsCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        addTriangles(sink, cmd, scissor) { sink ->
            GeometryTessellator.points(
                cmd.pointMode, cmd.points,
                cmd.paint.strokeWidth,
                cmd.paint.strokeCap,
                sink = sink,
            )
        }
    }

    override fun drawText(cmd: DrawTextCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        // T.TT 文本渲染分流(设计文档 §3.1,唯一拦截点):
        // - VANILLA:定向回退原版(组合期 LocalTextRenderBackend 盖章,P2);
        // - DEFAULT:全局开关开启且字体就绪 → 自研 TrueType 管线
        //   (TrueTypeTextWriter 拒绝的 run —— 缺字/未支持样式/渐变 —— 逐 run 回退);
        // - 其余(开关关闭 / 字体不可用)→ 原版 sink.addText(D4,与现状一致)。
        // P3 像素化路由不变量:像素模式(usePixelDefaultFont)下默认/fusion_pixel
        // 的 run 必须由自研管线渲染(度量=PixelFont 同源),enabled 对其不生效;
        // 非像素模式维持旧语义(enabled 关闭 → 全部原版)。
        val goingVanilla = cmd.backend == TextRenderBackend.VANILLA ||
            (!TextRenderConfig.enabled && !TextRenderConfig.usePixelDefaultFont) ||
            !TrueTypeTextWriter.trySubmit(cmd, scissor?.toScreenRectangle(), sink)
        // 文本路由观测([TextRenderConfig.debugTextBounds]):每个 run 的字体描述、
        // 粗体与最终分流 —— 排查「粗体/回退/混排走错渲染器」类问题的第一手证据
        if (TextRenderConfig.debugTextBounds) {
            println(
                "[TT] '" + cmd.text.take(10) + "' font=" + cmd.style.fontOriginal +
                    " bold=" + cmd.style.isBold + " backend=" + cmd.backend +
                    " usePixel=" + TextRenderConfig.usePixelDefaultFont +
                    " -> " + if (goingVanilla) "VANILLA" else "STB"
            )
        }
        // T.TT 诊断:TTF 模式下的回退 run 打印(行盒调试开关兼作回退诊断)
        if (goingVanilla && TextRenderConfig.enabled && TextRenderConfig.debugTextBounds) {
            println("[TT-FALLBACK] '${cmd.text.take(16)}' backend=${cmd.backend} hasShader=${cmd.shader != null}")
        }
        if (!goingVanilla) {
            if (TextRenderConfig.debugTextBounds) drawDebugTextBounds(cmd, scissor, sink)
            return
        }
        // T.TT:原版路径的渐变文本 —— GuiTextRenderState 一段只能一个颜色,
        // 整段采样会退化成纯色(实测反馈);按字符拆分、逐字符采样渐变色,
        // 视觉呈阶梯渐变,与原版位图渲染器兼容。
        if (cmd.shader != null) {
            splitGradientText(cmd, scissor, sink)
        } else {
            submitSegmentedVanillaText(cmd, scissor, sink)
        }
        if (TextRenderConfig.debugTextBounds) drawDebugTextBounds(cmd, scissor, sink)
    }

    /**
     * 调试:绘制文本 run 的行盒轮廓 + 基线标记。坐标为命令局部空间,
     * 经命令矩阵变换后与字形同步缩放 —— 两种渲染器画的是同一逻辑盒。
     *
     * 实现:白像素 quad 走 gui_text 浮点管线(非 ColoredRectangleRenderState ——
     * 后者把局部坐标取整,1/scale 厚度的细线在大字号下会整条消失,实测反馈)。
     * 厚度 = 精确 1 屏幕像素。
     */
    private fun drawDebugTextBounds(
        cmd: DrawTextCommand,
        scissor: Rect?,
        sink: GuiCommandSink,
    ) {
        MinecraftGuiText.ensureCompiled()
        val ms = max(0.05f, matrixScale(cmd.matrix))
        // P1:度量按命令样式解析(唯一决策点 FontResolver)
        val metrics = FontResolver.resolveNative(cmd.style).metrics
        var width = 0f
        var i = 0
        while (i < cmd.text.length) {
            val cp = cmd.text.codePointAt(i)
            i += Character.charCount(cp)
            width += metrics.advance(cp)
        }
        val height = metrics.lineHeight
        val baseline = cmd.y + metrics.baselineFromTop
        // 1 屏幕像素的局部厚度
        val t = 1f / ms
        val (page, wu, wv) = GlyphAtlas.whiteTexelUV()
        val scr = scissor?.toScreenRectangle()

        fun quad(l: Float, tp: Float, r: Float, b: Float, argb: Int) {
            sink.addElement(
                GuiGlyphRenderState(
                    pose = cmd.matrix.toMatrix3x2f(),
                    scissor = scr,
                    vertices = floatArrayOf(
                        l, tp, wu, wv,
                        r, tp, wu, wv,
                        l, b, wu, wv,
                        l, b, wu, wv,
                        r, tp, wu, wv,
                        r, b, wu, wv,
                    ),
                    colors = IntArray(6) { argb },
                    pageIndex = page,
                )
            )
        }

        // 行盒四边(绿)
        quad(cmd.x, cmd.y, cmd.x + width, cmd.y + t, DBG_BOUNDS_COLOR)
        quad(cmd.x, cmd.y + height - t, cmd.x + width, cmd.y + height, DBG_BOUNDS_COLOR)
        quad(cmd.x, cmd.y, cmd.x + t, cmd.y + height, DBG_BOUNDS_COLOR)
        quad(cmd.x + width - t, cmd.y, cmd.x + width, cmd.y + height, DBG_BOUNDS_COLOR)
        // 基线(红)
        quad(cmd.x, baseline - t, cmd.x + width, baseline, DBG_BASELINE_COLOR)
    }

    /**
     * 原版路径的渐变文本:按码点拆分为单字符 [GuiTextRenderState],每个字符
     * 以其中心点采样一次渐变色。字符推进用原版 splitter(与原版字形宽度同源),
     * 避免与原版字形错位。
     */
    private fun splitGradientText(
        cmd: DrawTextCommand,
        scissor: Rect?,
        sink: GuiCommandSink,
    ) {
        val font = mc.font
        val splitter = font.splitter
        val pose = cmd.matrix.toMatrix3x2f()
        // P3 基线补偿:布局基线(像素字体自然行盒)− 原版硬编码锚点 7
        // P1:度量按命令样式解析(唯一决策点 FontResolver)
        val metricsSrc = FontResolver.resolveNative(cmd.style).metrics
        val yBase = cmd.y + (metricsSrc.baselineFromTop - VANILLA_BASELINE_ANCHOR)
        var penX = 0f
        var i = 0
        val n = cmd.text.length
        while (i < n) {
            val cp = cmd.text.codePointAt(i)
            val cc = Character.charCount(cp)
            val chText = cmd.text.substring(i, i + cc)
            val advance = splitter.stringWidth(chText)
            // 采样点:字符中心(x)/ 当前行高中点(y);sampleGradient 已含命令 alpha
            val color = ColorEvaluator.sampleGradient(
                ColorEvaluator.gradientTAt(cmd.x + penX + advance / 2f, cmd.y + metricsSrc.lineHeight / 2f, cmd.shader!!),
                cmd.shader,
                cmd.alpha,
            )
            // P3 像素化缺字回退:像素字体缺的字符换回 minecraft:default 字形
            val segStyle = if (pixelFontCovers(cp)) cmd.style else cmd.style.withFont(FontDescription.DEFAULT)
            sink.addText(
                GuiTextRenderState(
                    font,
                    Language.getInstance().getVisualOrder(segStyle.toComponent(chText)),
                    pose,
                    (cmd.x + penX).roundToInt(),
                    yBase.roundToInt(),
                    color,
                    0,
                    false,
                    false,
                    scissor?.toScreenRectangle(),
                )
            )
            penX += advance
            i += cc
        }
    }

    // ── P3 像素化缺字回退(命名资源字体无跨字体回退,提交层切段换字体)──────

    /**
     * 原版字形基线锚点:原版把所有字形基线硬编码在 `行顶 + 7`
     * ([com.mojang.blaze3d.font.GlyphBitmap.getTop] = `7 - bearingTop`,
     * 7 = 原版位图字体 ascent)。布局侧基线来自像素字体自然行盒
     * (@pixelFontEmSp em ≈ 13px)—— 提交 y 必须补差值,否则整体上移 ≈6px
     * (实测反馈:渲染位置往上偏移)。
     */
    private val VANILLA_BASELINE_ANCHOR = 7f

    /** 像素字体码点覆盖缓存(true = fusion_pixel 有字形;false = 换原版字形) */
    private val pixelCoverageCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /**
     * 像素字体是否覆盖该码点(P1:经注册表字体的覆盖判定 —— 与原版 FreeType
     * 渲染端同读一份 glyf 表,判定一致;缓存避免逐帧查询)。
     */
    private fun pixelFontCovers(codepoint: Int): Boolean =
        pixelCoverageCache.computeIfAbsent(codepoint) { BuiltinFonts.fusionPixel.covers(it) }

    /**
     * 按码点覆盖切段提交原版渲染:fusion_pixel 覆盖的段保持其字体描述;
     * 未覆盖段(阿拉伯文/emoji 等)字体换回 [FontDescription.DEFAULT] ——
     * 由 minecraft:default 字形渲染,实现「Compose 内像素字体 + 缺字退回原版」。
     * 段内 x 偏移用命令样式的解析度量推进(与布局度量同源,含 kern)。
     */
    private fun submitSegmentedVanillaText(
        cmd: DrawTextCommand,
        scissor: Rect?,
        sink: GuiCommandSink,
    ) {
        val alphaByte = (cmd.alpha * 255f).roundToInt().coerceIn(0, 255)
        val baseColor = cmd.style.color?.value?.or(0xFF000000.toInt()) ?: 0xFFFFFFFF.toInt()
        val argb = (baseColor and 0x00FFFFFF) or (alphaByte shl 24)
        // P3 基线补偿:布局基线 − 原版硬编码锚点(见 VANILLA_BASELINE_ANCHOR 注释)
        // P1:度量按命令样式解析(唯一决策点 FontResolver)
        val metricsSrc = FontResolver.resolveNative(cmd.style).metrics
        val yBase = cmd.y + (metricsSrc.baselineFromTop - VANILLA_BASELINE_ANCHOR)
        val pose = cmd.matrix.toMatrix3x2f()
        val scr = scissor?.toScreenRectangle()

        var segStart = 0
        fun flush(endIdx: Int, startPen: Float, covered: Boolean) {
            if (endIdx <= segStart) return
            val segText = cmd.text.substring(segStart, endIdx)
            val segStyle = if (covered) cmd.style else cmd.style.withFont(FontDescription.DEFAULT)
            sink.addText(
                GuiTextRenderState(
                    mc.font,
                    Language.getInstance().getVisualOrder(segStyle.toComponent(segText)),
                    pose,
                    (cmd.x + startPen).roundToInt(),
                    yBase.roundToInt(),
                    argb,
                    0,
                    false,
                    false,
                    scr,
                )
            )
        }

        var i = 0
        var pen = 0f
        var segStartPen = 0f
        var prev = -1
        var cur: Boolean? = null
        val n = cmd.text.length
        while (i < n) {
            val cp = cmd.text.codePointAt(i)
            val cc = Character.charCount(cp)
            val covered = pixelFontCovers(cp)
            if (cur != null && covered != cur) {
                flush(i, segStartPen, cur)
                segStart = i
                segStartPen = pen
            }
            cur = covered
            pen += metricsSrc.advance(cp) + metricsSrc.kern(prev, cp)
            prev = cp
            i += cc
        }
        flush(n, segStartPen, cur ?: true)
    }

    override fun drawGradientRect(cmd: DrawGradientRectCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        // T.14 阴影(渐变保底):MC 原生双色垂直渐变矩形(GUI pipeline,与 blit 同排序组,
        // 阴影命令先记录先绘制,层级正确)
        sink.addElement(
            ColoredRectangleRenderState(
                RenderPipelines.GUI,
                TextureSetup.noTexture(),
                cmd.matrix.toMatrix3x2f(),
                cmd.left.roundToInt(),
                cmd.top.roundToInt(),
                cmd.right.roundToInt(),
                cmd.bottom.roundToInt(),
                cmd.topColorArgb,
                cmd.bottomColorArgb,
                scissor?.toScreenRectangle(),
            )
        )
    }

    override fun drawShadow(cmd: DrawShadowCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        // T.14 阴影(GPU 距离场):CPU 三角化 + 每顶点距离场,
        // gui_shadow shader 高斯模糊解析解生成软阴影(参照 Skia SkShadowUtils)
        MinecraftShadowRenderer.renderShadow(
            sink = sink,
            matrix = cmd.matrix,
            left = cmd.left,
            top = cmd.top,
            right = cmd.right,
            bottom = cmd.bottom,
            elevation = cmd.elevation,
            offsetX = cmd.offsetX,
            offsetY = cmd.offsetY,
            cornerRadius = cmd.cornerRadius,
            pathSegments = cmd.pathSegments,
            ambientColorArgb = cmd.ambientColorArgb,
            spotColorArgb = cmd.spotColorArgb,
            scissor = scissor?.toScreenRectangle(),
        )
    }

    override fun drawImageRect(cmd: DrawImageRectCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        sink.addElement(blitImage(cmd, scissor))
    }

    override fun drawVertices(cmd: DrawVerticesCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return
        addVertices(sink, cmd, scissor)
    }

    override fun drawCustom(cmd: DrawCustomCommand) {
        val sink = sink ?: return
        val scissor = scissorFor(cmd)
        if (cmd.clip != null && scissor == null) return

        // 重建绘制参数快照为 MinecraftPaint(Paint 接口,插件可读全部公开字段)
        val paint = cmd.paint?.let { snap ->
            MinecraftPaint(
                color = snap.color,
                alpha = snap.alpha,
                style = snap.style,
                strokeWidth = snap.strokeWidth,
                strokeCap = snap.strokeCap,
                filterQuality = snap.filterQuality,
                blendMode = snap.blendMode,
                shader = snap.shader,
            ).apply {
                nativeColorFilter = snap.colorFilter
            }
        }

        // 委托给已注册的插件(包括默认注册的 mc_texture 等)
        val ctx = CustomDrawContext(
            sink = sink,
            matrix = cmd.matrix,
            clip = cmd.clip,
            scissor = scissor,
            layer3D = cmd.layer3D,
            paint = paint,
        )
        MinecraftRenderPlugins.dispatch(cmd.tag, cmd.data, ctx)
    }

    // ── scissor(原 render() 循环内联逻辑,提取为单方法,P2)──────────────────

    /**
     * 平台适配点(T.9 兜底):MC 的 enableScissor 对 "宽高 <= 0" 直接抛
     * IllegalArgumentException("Scissor size must be >0")。两个来源:
     * 1. 退化裁剪(空、反转、NaN、亚像素高度)—— 用四舍五入后的整数尺寸判定;
     * 2. 裁剪矩形完全落在窗口外 —— MC 钳制后高/宽会变成 0 同样崩溃,
     *    因此先与窗口矩形相交,相交为空则整条命令跳过。
     */
    private fun scissorFor(command: DrawCommand): Rect? {
        val windowState = mc.gameRenderer.gameRenderState().windowRenderState
        // T.24:场景尺寸 = 窗口像素(1:1),不再除 guiScale
        val windowWidth = windowState.width.toFloat()
        val windowHeight = windowState.height.toFloat()
        return command.clip?.let { raw ->
            val clamped = raw.intersect(Rect(0f, 0f, windowWidth, windowHeight))
            val l = clamped.left.roundToInt()
            val t = clamped.top.roundToInt()
            val r = clamped.right.roundToInt()
            val b = clamped.bottom.roundToInt()
            if (r > l && b > t) Rect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()) else null
        }
    }

    // ── 提交辅助(自 MinecraftRenderContext 原样搬移)────────────────────────

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
        val shader = paint.shader
        val vertexColors = if (shader != null) {
            ColorEvaluator.gradientVertexColors(shader, vertices, paint.alpha, paint.colorFilter)
        } else null
        sink.addElement(
            GuiTriangleRenderState(
                pose = command.matrix.toMatrix3x2f(),
                colorArgb = paint.toArgb(),
                scissor = scissor?.toScreenRectangle(),
                vertices = vertices,
                stroke = paint.style == PaintingStyle.Stroke,
                blendMode = paint.blendMode,
                vertexColors = vertexColors,
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
     * - 3D 图层下走 PerspectiveBackend 的 CPU 透视路径。
     */
    private fun addVertices(
        sink: GuiCommandSink,
        command: DrawVerticesCommand,
        scissor: Rect?,
    ) {
        val paint = command.paint
        val vc = command.positions.size / 2
        if (vc < 3) return
        val alphaMul = paint.alpha
        val srcColors = command.colors
        // 逐顶点:alpha 叠加 + colorFilter(T.21)
        val vertColors = IntArray(vc) { i -> ColorEvaluator.applyColorFilter(ColorEvaluator.scaleAlpha(srcColors[i], alphaMul), paint.colorFilter) }
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
                VertexMode.Triangles     -> {
                    var i = 0
                    while (i + 2 < idx.size) {
                        emit(idx[i].toInt(), idx[i + 1].toInt(), idx[i + 2].toInt()); i += 3
                    }
                }

                VertexMode.TriangleStrip -> {
                    for (i in 0 until idx.size - 2) emit(idx[i].toInt(), idx[i + 1].toInt(), idx[i + 2].toInt())
                }

                VertexMode.TriangleFan   -> {
                    for (i in 1 until idx.size - 1) emit(idx[0].toInt(), idx[i].toInt(), idx[i + 1].toInt())
                }
            }
        } else {
            when (command.vertexMode) {
                VertexMode.Triangles     -> {
                    var i = 0
                    while (i + 2 < vc) {
                        emit(i, i + 1, i + 2); i += 3
                    }
                }

                VertexMode.TriangleStrip -> {
                    for (i in 0 until vc - 2) emit(i, i + 1, i + 2)
                }

                VertexMode.TriangleFan   -> {
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

    /**
     * 把一条矩形绘制转成 [BlitRenderState]。
     *
     * - pose:命令记录时的矩阵快照(列主序 4x4)→ JOML Matrix3x2f;
     * - 坐标:场景 px(密度 1)= GUI 单位,四舍五入为 int;
     * - 颜色:Compose Color → 0xAARRGGBB(alpha 叠加 Paint.alpha);
     * - scissor:命令记录时的裁剪矩形(记录时已换算为屏幕空间,MC scissor 即屏幕坐标)。
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

    // ── 指纹与矩阵工具(自 MinecraftRenderContext 原样搬移)──────────────────

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
                StrokeCap.Butt   -> 0
                StrokeCap.Round  -> 1
                StrokeCap.Square -> 2
                else             -> 0
            }
        )
        // 渐变类型影响 DrawRectCommand 的三角化策略(Linear 走条带细分,
        // Radial/Sweep 走网格细分),必须进缓存指纹,否则同一矩形换 brush
        // 时会复用错误的顶点几何 → 渲染「乱七八糟」。
        mix(
            when (paint.shader) {
                null -> 0
                is LinearGradientShaderData  -> 1
                is RadialGradientShaderData  -> 2
                is SweepGradientShaderData   -> 3
                else                         -> 4
            }
        )
        when (command) {
            is DrawCircleCommand    -> {
                mix(1); mix(command.centerX); mix(command.centerY); mix(command.radius)
            }

            is DrawOvalCommand      -> {
                mix(2); mix(command.left); mix(command.top); mix(command.right); mix(command.bottom)
            }

            is DrawArcCommand       -> {
                mix(3); mix(command.left); mix(command.top); mix(command.right); mix(command.bottom)
                mix(command.startAngle); mix(command.sweepAngle); mixB(command.useCenter)
            }

            is DrawRoundRectCommand -> {
                mix(4); mix(command.left); mix(command.top); mix(command.right); mix(command.bottom)
                mix(command.radiusX); mix(command.radiusY)
            }

            is DrawLineCommand      -> {
                mix(5); mix(command.p1x); mix(command.p1y); mix(command.p2x); mix(command.p2y)
            }

            is DrawPathCommand      -> {
                mix(6)
                for (seg in command.segments) {
                    mix(seg.type.ordinal)
                    mix(seg.points.size)
                    for (v in seg.points) mix(v)
                }
            }

            is DrawPointsCommand    -> {
                mix(7)
                mix(
                    when (command.pointMode) {
                        PointMode.Points  -> 0
                        PointMode.Lines   -> 1
                        PointMode.Polygon -> 2
                        else              -> 0
                    }
                )
                for (p in command.points) {
                    mix(p.x); mix(p.y)
                }
            }

            is DrawRectCommand      -> {
                mix(8); mix(command.left); mix(command.top); mix(command.right); mix(command.bottom)
            }

            else                    -> return 0L // 不缓存(非三角化命令)
        }
        return (h1 shl 1) xor h2
    }

    /** 命令矩阵(列主序 4x4)2D 部分的最大轴缩放 */
    private fun matrixScale(m: FloatArray): Float {
        val scaleX = sqrt(m[0] * m[0] + m[1] * m[1])
        val scaleY = sqrt(m[4] * m[4] + m[5] * m[5])
        return max(scaleX, scaleY)
    }

    private companion object {
        /** 顶点网格内部 coverage 大数(与 GeometryTessellator.OPAQUE 同值) */
        const val OPAQUE_COVERAGE = 1e4f

        /** 调试框颜色:行盒轮廓(绿)/ 基线(红) */
        val DBG_BOUNDS_COLOR = 0xFF00E676.toInt()
        val DBG_BASELINE_COLOR = 0xFFFF5252.toInt()
    }
}

/** Paint 快照 → 最终 0xAARRGGBB(alpha 叠加 + T.21 颜色滤镜)。 */
private fun PaintSnapshot.toArgb(): Int = ColorEvaluator.applyColorFilter(color.toArgb(alpha), colorFilter)

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

