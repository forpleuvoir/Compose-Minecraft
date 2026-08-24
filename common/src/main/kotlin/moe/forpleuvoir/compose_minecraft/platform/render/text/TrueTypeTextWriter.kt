package moe.forpleuvoir.compose_minecraft.platform.render.text

import androidx.compose.ui.graphics.MinecraftCanvas.DrawTextCommand
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.render.paint.ColorEvaluator
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink
import moe.forpleuvoir.compose_minecraft.platform.render.toMatrix3x2f
import moe.forpleuvoir.compose_minecraft.platform.ui.text.fontOriginal
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toComponent
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.state.gui.GuiTextRenderState
import net.minecraft.locale.Language
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * TrueType 文本绘制执行器(T.TT):把一条 [DrawTextCommand] 光栅化并组装为
 * [GuiGlyphRenderState] quad 批次提交给 [GuiCommandSink]。
 *
 * 原生排版(用户拍板):字符推进与字形 bearing 全部使用字体自身度量
 * (与布局层 `cumFloatWidths` 严格同源)—— 字符间距由字体设计保证,任何
 * 字体文件都自然协调;代价是切换渲染器时文本宽度不同(测试场景整树重建)。
 *
 * 光栅化字号 = baseSizePx × 姿态矩阵缩放(量化 0.25px)—— 位图像素与
 * 屏幕像素 1:1,任意缩放下锐利(矢量光栅化红利);quad 以 1x 布局坐标
 * 记录、由姿态矩阵统一变换。
 *
 * 性能:组装缓冲为预分配原始数组 + 游标写入(零装箱、零逐元素分配),
 * 缓冲经对象池跨帧复用;字形缓存键打包进单个 Long(零分配查询)。
 */
internal object TrueTypeTextWriter {

    /** 斜体剪切斜率:水平偏移 = 斜率 × (基线 − 角点 y),对齐原版 0.25 总斜率 */
    internal const val ITALIC_SHEAR = 0.25f

    /** 位图最小光栅化系数:避免亚像素字号光栅化出糊图(极端缩小场景仍可读) */
    internal const val MIN_RASTER_SCALE = 0.25f

    /** 拒绝原因观测([TextRenderConfig.debugTextBounds] 开启时打印) */
    private fun reject(cmd: DrawTextCommand, reason: String): Boolean {
        if (TextRenderConfig.debugTextBounds) {
            println("[TT] reject '" + cmd.text.take(10) + "' font=" + cmd.style.fontOriginal + " reason=" + reason)
        }
        return false
    }

    /**
     * 尝试用自研管线绘制一条文本命令。返回 true 表示已提交;
     * false 表示拒绝(调用方回退 `sink.addText` 原版渲染)。
     *
     * 架构边界(P3 定案):本管线**只服务矢量字体**(minecraft:default 的 run,
     * 经系统字体链)。像素字体(compose_minecraft:fusion_pixel)归原版 FreeType
     * 渲染器 —— 由 `font/fusion_pixel.json`(size=12)声明,经 GuiStateBackend
     * 的分段回退路径渲染;度量由 [PixelFont] 提供。
     */
    fun trySubmit(
        cmd: DrawTextCommand,
        scissor: ScreenRectangle?,
        sink: GuiCommandSink,
        font: ResolvedFont? = null,
    ): Boolean {
        // P2-B4(A3):字体绑定随命令到达;仅服务 STB_VECTOR 通道绑定(A2 单点分流)
        val binding = font ?: FontResolver.resolveNative(cmd.style)
        if (binding.font.channel != FontChannel.STB_VECTOR) return reject(cmd, "channel=${binding.font.channel}")
        // 常规回退链(首 = 主字体,供字形查找与混淆池);粗体 run 先走粗体链
        val regularChain = TrueTypeFontManager.regularChain()
        if (regularChain.isEmpty()) return reject(cmd, "chain empty")
        // 原生排版:布局/推进度量 = 绑定度量(与布局层 cumFloatWidths 严格同源,I2)
        val metrics = binding.metrics
        val style = cmd.style

        // 基础色:与 GuiStateBackend.text 原版路径同一取色语义(样式色补 alpha);
        // 渐变 run:按各 quad 中心点逐个采样(见 colorAt)
        val shader = cmd.shader
        val alphaByte = (cmd.alpha * 255f).roundToInt().coerceIn(0, 255)
        val baseColor = style.color?.value?.or(0xFF000000.toInt()) ?: 0xFFFFFFFF.toInt()
        val solidColor = (baseColor and 0x00FFFFFF) or (alphaByte shl 24)
        fun colorAt(x: Float, y: Float): Int = shader?.let {
            ColorEvaluator.sampleGradient(ColorEvaluator.gradientTAt(x, y, it), it, cmd.alpha)
        } ?: solidColor

        // 光栅化放大系数:姿态矩阵最大轴缩放(位图按屏幕实际像素密度生成)
        val rasterScale = max(MIN_RASTER_SCALE, matrixScale(cmd.matrix))
        val sizePx = GlyphCache.quantize(binding.emPx * rasterScale)

        val baselineY = cmd.y + binding.baselineFromTopPx
        var penX = cmd.x

        // 混淆:确定性随机槽位 + 字符序号种子(同槽内稳定,跨槽重掷);
        // 间隔可配(TextRenderConfig.obfuscatedUpdateIntervalMs,默认 16ms)
        val obfuscated = style.isObfuscated
        val obfuscateSlot = System.currentTimeMillis() /
            TextRenderConfig.obfuscatedUpdateIntervalMs.coerceAtLeast(1L)

        // 粗体链优先;膨胀合成仅用于落在常规链上的粗体字形
        val bold = style.isBold
        val italic = style.isItalic
        val boldChainFonts = if (bold) TrueTypeFontManager.boldChain() else emptyList()

        // 阴影(MC Style shadowColor):字形在偏移处先画一份阴影色再画主字形。
        // 光源统一在「左上」:偏移 = (+1,+1)×行高比例(右下投影)。
        // alpha==0 的阴影色按不透明处理;渐变 run 的阴影取阴影原色。装饰线暂不带阴影。
        val shadowRaw = style.shadowColor
        val hasShadow = shadowRaw != null
        val shadowOffset = if (hasShadow) metrics.vanillaDecorThickness else 0f
        val shadowArgb: Int = if (shadowRaw != null) {
            val base = if ((shadowRaw ushr 24) == 0) shadowRaw or 0xFF000000.toInt() else shadowRaw
            (((base ushr 24) * alphaByte / 255) shl 24) or (base and 0x00FFFFFF)
        } else 0

        // 按图集页分组组装 quad(一个 run 可能跨页);批次缓冲经对象池复用
        val batches = HashMap<Int, QuadBatch>()
        fun batch(page: Int) = batches.getOrPut(page) { QuadBatch.get() }

        var charIndex = 0
        var i = 0
        val n = cmd.text.length
        var prevCp = -1
        // 缺字内联(T.TT):连续缺字字符累积为一段,交原版字形渲染(unifont 兜底),
        // 其余字符保持 TTF —— 单个缺字不再拖垮整个 run
        var vanillaStart = -1f
        val vanillaText = StringBuilder()
        // P3 基线补偿:原版把字形基线硬编码在 行顶+7(GlyphBitmap.getTop),
        // 像素/系统字体布局基线更高 —— 回退段提交 y 需补差值,否则上移
        // 位图终端绑定(unifont,网格 9):回退段 pose 比与其布局宽度缩放严格一致
        val bitmapTerminal = FontResolver.resolve(
            BuiltinFonts.uniFontTerminal.id,
            moe.forpleuvoir.compose_minecraft.platform.render.text.MeasureSpec(binding.emPx, style = cmd.style),
        )
        fun flushVanilla() {
            if (vanillaStart < 0) return
            val seg = vanillaText.toString()
            // 布局已按「缺字回退原版度量」为该段预留了精确宽度(混合度量源),
            // 原版字形按自身宽度渲染恰好填满 —— 无需任何缩放适配
            val segColor = colorAt(vanillaStart + (penX - vanillaStart) / 2f, cmd.y + binding.lineHeightPx / 2f)
            // 回退段交位图终端(minecraft:default 字形集);锚点补偿/pose 比
            // 由 VanillaBitmapSubmitter 内部统一完成(I4 唯一实现)
            VanillaBitmapSubmitter.submitFor(
                cmd = cmd, sink = sink, scissor = scissor,
                text = seg, font = bitmapTerminal,
                fontId = net.minecraft.network.chat.FontDescription.DEFAULT,
                xBaseline = vanillaStart,
                yBaseline = cmd.y + binding.baselineFromTopPx,
                colorArgb = segColor,
            )
            vanillaStart = -1f
            vanillaText.clear()
        }
        while (i < n) {
            val cp = cmd.text.codePointAt(i)
            i += Character.charCount(cp)
            charIndex++

            // 混淆替换(空格除外,原版语义):advance 仍按原字符 —— 布局不抖
            var drawCp = cp
            if (obfuscated && cp != ' '.code) {
                regularChain.first().randomObfuscationCandidate(cp, charIndex * 1000003L + obfuscateSlot)
                    ?.let { drawCp = it }
            }

            // 字形获取(缺字回退链):粗体 run 先沿粗体链、再沿常规链;
            // 全链缺字 → 该字符交原版字形内联渲染(见 flushVanilla)。
            // 膨胀合成仅用于落在常规链上的粗体字形;真粗体链字形不膨胀
            val renderFont = boldChainFonts.firstOrNull { it.hasGlyph(drawCp) }
                ?: regularChain.firstOrNull { it.hasGlyph(drawCp) }
            if (renderFont == null) {
                if (vanillaStart < 0) vanillaStart = penX
                vanillaText.appendCodePoint(cp)
                penX += metrics.advance(cp)
                continue
            }
            flushVanilla()
            val syntheticBold = bold && renderFont !in boldChainFonts
            val glyph = GlyphCache.getOrCreate(
                renderFont, drawCp, sizePx, syntheticBold,
                // P2-B4 修正:布局已是最终像素空间,位图→命令坐标的除数 =
                // 光栅化时附加的外部矩阵缩放(无变换即 1);旧公式 sizePx/base
                // 是网格坐标系残留,会把位图压小 provEm/em 倍
                rasterScale,
            )
            if (glyph == null) {
                // 极端情况(字形超图集页):该字符交原版字形内联渲染
                if (vanillaStart < 0) vanillaStart = penX
                vanillaText.appendCodePoint(cp)
                penX += metrics.advance(cp)
                continue
            }

            if (charIndex == 1 && TextRenderConfig.debugTextBounds) {
                // [TT-S] 临时探针:首字形实测链路值(定位后移除)
                println(
                    "[TT-S] '" + cmd.text.take(6) + "' em=" + binding.emPx +
                        " mScale=" + rasterScale + " sizePx=" + sizePx +
                        " div=" + (sizePx / renderFont.baseSizePx) +
                        " wLocal=" + glyph.widthLocal + " hLocal=" + glyph.heightLocal +
                        " baseY=" + baselineY + " y=" + cmd.y +
                        " topLocal=" + glyph.bearingTopLocal
                )
            }
            if (glyph.hasBitmap) {
                // stb yoff 为屏幕 y-down 约定(负值 = 位图顶在基线上方),直接加到基线上
                val leftRaw = penX + glyph.bearingXLocal
                val topRaw = baselineY + glyph.bearingTopLocal
                // 屏幕像素对齐:只吸附原点(左/上),宽高保持精确值 ——
                // 四边独立吸附会让每个字形宽度抖动 ±1px(实测:间距忽近忽远);
                // 原点对齐 + 1:1 纹理映射下,仅尾列/行有轻微灰度过渡
                val left = snap(leftRaw, rasterScale)
                val top = snap(topRaw, rasterScale)
                val right = left + glyph.widthLocal
                val bottom = top + glyph.heightLocal
                val color = colorAt(left + glyph.widthLocal * 0.5f, top + glyph.heightLocal * 0.5f)
                // 阴影:同字形偏移一份阴影色,先于主字形入批(与原版逐字符
                // 「先影子后本体」的遮盖顺序一致);渐变 run 取阴影原色
                if (hasShadow) {
                    val so = shadowOffset
                    batch(glyph.page).addGlyphQuad(
                        snap(left + so, rasterScale), snap(top + so, rasterScale),
                        snap(right + so, rasterScale), snap(bottom + so, rasterScale),
                        baselineY, italic, glyph.u0, glyph.v0, glyph.u1, glyph.v1, shadowArgb,
                    )
                }
                batch(glyph.page).addGlyphQuad(
                    left, top, right, bottom,
                    baselineY, italic, glyph.u0, glyph.v0, glyph.u1, glyph.v1, color,
                )
            }
            // 推进笔位:advance + 字偶距(与布局前缀和严格一致)
            penX += metrics.advance(cp) + metrics.kern(prevCp, cp)
            prevCp = cp
        }

        flushVanilla()

        // 装饰线(下划线/删除线):白像素 quad × 顶点色,几何对齐原版 Font
        // (原版 1x:下划线 [y+8, y+9]、删除线中心 y+4.5,均 1px 高 —— 按行高等比缩放)
        if (penX > cmd.x && (style.isUnderlined || style.isStrikethrough)) {
            val (page, whiteU, whiteV) = GlyphAtlas.whiteTexelUV()
            val decorBatch = batch(page)
            val thickness = metrics.vanillaDecorThickness
            val decorColor = colorAt((cmd.x + penX) * 0.5f, baselineY)
            if (style.isUnderlined) {
                val top = cmd.y + metrics.lineHeight - thickness
                decorBatch.addQuad(cmd.x, top, penX, top + thickness, whiteU, whiteV, whiteU, whiteV, decorColor)
            }
            if (style.isStrikethrough) {
                val center = cmd.y + metrics.lineHeight * 0.5f
                decorBatch.addQuad(
                    cmd.x, center - thickness * 0.5f, penX, center + thickness * 0.5f,
                    whiteU, whiteV, whiteU, whiteV, decorColor,
                )
            }
        }

        if (batches.isEmpty()) return true // 空白 run:无可见输出但已正确处理
        MinecraftGuiText.ensureCompiled()
        for ((page, batch) in batches) {
            sink.addElement(
                GuiGlyphRenderState(
                    pose = cmd.matrix.toMatrix3x2f(),
                    scissor = scissor,
                    vertices = batch.vertices.copyOf(batch.vCount),
                    colors = batch.colors.copyOf(batch.cCount),
                    pageIndex = page,
                )
            )
            QuadBatch.recycle(batch)
        }
        return true
    }

    /** 命令矩阵(列主序 4x4)2D 部分的最大轴缩放 */
    private fun matrixScale(m: FloatArray): Float {
        val scaleX = sqrt(m[0] * m[0] + m[1] * m[1])
        val scaleY = sqrt(m[4] * m[4] + m[5] * m[5])
        return max(scaleX, scaleY)
    }

    /**
     * 屏幕像素对齐:把局部坐标吸附到「1/矩阵缩放」网格,使变换后的四边形
     * 边界落在整数屏幕像素上 —— 消除浮点落点 + LINEAR 采样造成的半像素模糊。
     * internal:P3③ RasterBackend CPU 快照路径共用。
     */
    internal fun snap(v: Float, grid: Float): Float = round(v * grid) / grid

    /**
     * 单页 quad 组装缓冲:预分配原始数组 + 游标写入(零装箱、零逐元素分配),
     * 实例经对象池跨帧复用容量;输出时按游标拷贝为精确长度数组交给渲染状态持有。
     */
    private class QuadBatch private constructor(
        var vertices: FloatArray,
        var colors: IntArray,
    ) {
        var vCount = 0
            private set
        var cCount = 0
            private set

        constructor() : this(FloatArray(256), IntArray(48))

        fun reset() {
            vCount = 0
            cCount = 0
        }

        fun addGlyphQuad(
            left: Float, top: Float, right: Float, bottom: Float,
            baselineY: Float, italic: Boolean,
            u0: Float, v0: Float, u1: Float, v1: Float,
            color: Int,
        ) {
            val shearTop = if (italic) ITALIC_SHEAR * (baselineY - top) else 0f
            val shearBottom = if (italic) ITALIC_SHEAR * (baselineY - bottom) else 0f
            putQuad(
                left + shearTop, top, right + shearTop, top,
                left + shearBottom, bottom, right + shearBottom, bottom,
                u0, v0, u1, v1, color,
            )
        }

        /** 装饰线/普通矩形 quad(不剪切) */
        fun addQuad(
            left: Float, top: Float, right: Float, bottom: Float,
            u0: Float, v0: Float, u1: Float, v1: Float,
            color: Int,
        ) {
            putQuad(left, top, right, top, left, bottom, right, bottom, u0, v0, u1, v1, color)
        }

        /** 两三角形(TL,BL,BR)+(TL,BR,TR);cull 关闭,绕序不约束 */
        private fun putQuad(
            tlx: Float, tly: Float, trx: Float, try_: Float,
            blx: Float, bly: Float, brx: Float, bry: Float,
            u0: Float, v0: Float, u1: Float, v1: Float,
            color: Int,
        ) {
            ensure(vCount + 24, cCount + 6)
            val o = vCount
            vertices[o] = tlx; vertices[o + 1] = tly; vertices[o + 2] = u0; vertices[o + 3] = v0
            vertices[o + 4] = blx; vertices[o + 5] = bly; vertices[o + 6] = u0; vertices[o + 7] = v1
            vertices[o + 8] = brx; vertices[o + 9] = bry; vertices[o + 10] = u1; vertices[o + 11] = v1
            vertices[o + 12] = tlx; vertices[o + 13] = tly; vertices[o + 14] = u0; vertices[o + 15] = v0
            vertices[o + 16] = brx; vertices[o + 17] = bry; vertices[o + 18] = u1; vertices[o + 19] = v1
            vertices[o + 20] = trx; vertices[o + 21] = try_; vertices[o + 22] = u1; vertices[o + 23] = v0
            repeat(6) { colors[cCount++] = color }
            vCount += 24
        }

        private fun ensure(extraV: Int, extraC: Int) {
            if (vertices.size < extraV) vertices = vertices.copyOf(maxOf(extraV, vertices.size * 2))
            if (colors.size < extraC) colors = colors.copyOf(maxOf(extraC, colors.size * 2))
        }

        companion object {
            private val POOL = ArrayList<QuadBatch>(4)

            /** 取一个缓冲并重置游标(容量复用,数据作废) */
            fun get(): QuadBatch = (POOL.removeLastOrNull() ?: QuadBatch()).apply { reset() }

            /** 输出拷贝完成后回收缓冲 */
            fun recycle(batch: QuadBatch) {
                if (POOL.size < 8) POOL.add(batch)
            }
        }
    }
}
