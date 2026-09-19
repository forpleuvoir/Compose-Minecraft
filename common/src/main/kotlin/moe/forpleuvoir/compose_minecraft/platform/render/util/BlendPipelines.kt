package moe.forpleuvoir.compose_minecraft.platform.render.util
import moe.forpleuvoir.compose_minecraft.mc

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.BlendFactor
import com.mojang.blaze3d.platform.BlendOp
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.logging.LogUtils
import androidx.compose.ui.graphics.BlendMode
import moe.forpleuvoir.compose_minecraft.platform.render.paint.ColorEvaluator
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// 平台自定义 blend pipeline
//
// 背景:GraphicsLayer/Paint 的 blendMode(≠ SrcOver)在  只透传不生效,
// 因为 MC 26.2 的 blend 函数在 pipeline 编译期固定(ColorTargetState.blendFunction),
// draw 级无法逐命令切换。 为每个可表达的 BlendMode 自建一个 pipeline
// (与官方 RenderPipelines.GUI_INVERT = GUI_SNIPPET + INVERT 同款思路):
// - blit 组:core/gui shader + POSITION_COLOR + QUADS(与 RenderPipelines.GUI 同构,
//   BlitRenderState 可直接用);
// - 三角化组:自写 core/gui_triangles(+stroke)shader + POSITION_COLOR_LINE_WIDTH
//   + TRIANGLES(与 MinecraftGuiTriangles 同构,只换 BlendFunction)。
// 不注册进 RenderPipelines,渲染时直接使用对象本身;不触碰 AGENTS.md 约束 #4
// (仍直接画到主渲染目标,只是 blend 函数不同)。
//
// 可表达的 17 种(Porter-Duff 12 + 数学混合 5,因子表见 [blendFunction]):
// 直通(straight)alpha 语义,与现有 TRANSLUCENT(SRC_ALPHA/ONE_MINUS_SRC_ALPHA)一致;
// Darken/Lighten 用 BlendOp.MIN/MAX —— 逐通道 min/max,与 Skia 带 alpha 加权的
// kDarken/kLighten 为近似(文档标注)。
// 其余 12 种(Overlay/Hardlight/Softlight/ColorDodge/ColorBurn/Difference/
// Exclusion/Multiply/Hue/Saturation/Color/Luminosity)需 GL_KHR_blend_equation_advanced
// 扩展或 shader 方案,回退 SrcOver(见 docs §1.6)。
// ─────────────────────────────────────────────────────────────────────────────

/** 平台 blend pipeline 管理与编译 */
internal object BlendPipelines {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** 原版纹理着色器(顶点 / 片元同名,输出直通 alpha) */
    private val VANILLA_TEXTURED_SHADER =
        Identifier.fromNamespaceAndPath("minecraft", "core/position_tex_color")

    /** 朝单位元(白)插值的纹理片元着色器;顶点着色器复用 [VANILLA_TEXTURED_SHADER] */
    private val FADE_FRAGMENT_SHADER =
        Identifier.fromNamespaceAndPath("compose_minecraft", "core/position_tex_color_fade")

    /**
     * 淡出策略 —— **唯一一张表**(模式 → alpha 作为透明度时的源色解算)。
     *
     * alpha 只落在颜色 alpha 通道上,而自定义 blend pipeline 的因子在 pipeline 编译期写死、
     * 并按 Skia 的预乘语义定义。要让「源贡献随 alpha 与背景交叉淡入淡出」(而不是把源色乘暗
     * 后留成黑块),每个模式都要朝自己的「单位元」收敛:
     * - [PREMULTIPLY]:加性 / 取亮族 —— 源色预乘 alpha(dst 项随之收敛到背景);
     * - [WHITE_LERP]:乘 / 取暗族 —— 源色朝**白**插值(白是这两族的单位元);
     * - [SRC_OVER]:替换族 —— 等价于按 alpha 与背景做 SrcOver;
     * - [BLACK_ALPHA] / [BLACK_ALPHA_SELF] / [BLACK_ALPHA_SQUARE]:擦除族 —— 提交黑源 +
     *   按模式修正的 alpha,等价于把 dst 按 (1−α') 保留;
     * - [NONE]:SrcOver(原生直通管线)与 12 种回退模式,无需解算。
     *
     * 三处消费方([fadeBlendMode] / [fadeColor] / [texturedFor])都从本表派生,不各自列模式。
     * alpha = 255 时全部退化为原值(不透明元素行为不变)。
     */
    private enum class Fade {
        NONE, PREMULTIPLY, WHITE_LERP, SRC_OVER, BLACK_ALPHA, BLACK_ALPHA_SELF, BLACK_ALPHA_SQUARE
    }

    private fun fade(mode: BlendMode): Fade = when (mode) {
        BlendMode.Plus, BlendMode.Screen, BlendMode.Lighten, BlendMode.SrcAtop -> Fade.PREMULTIPLY
        BlendMode.Darken, BlendMode.Modulate -> Fade.WHITE_LERP
        BlendMode.Src, BlendMode.SrcIn -> Fade.SRC_OVER
        BlendMode.Clear, BlendMode.SrcOut -> Fade.BLACK_ALPHA
        BlendMode.DstIn, BlendMode.DstAtop -> Fade.BLACK_ALPHA_SELF
        BlendMode.DstOut, BlendMode.Xor -> Fade.BLACK_ALPHA_SQUARE
        else -> Fade.NONE
    }

    /** 实际用于选管线的模式:擦除 / 替换族退化为原版 SrcOver 管线 */
    fun fadeBlendMode(mode: BlendMode): BlendMode = when (fade(mode)) {
        Fade.SRC_OVER, Fade.BLACK_ALPHA, Fade.BLACK_ALPHA_SELF, Fade.BLACK_ALPHA_SQUARE -> BlendMode.SrcOver
        else -> mode
    }

    /** 按 [fadeBlendMode] 选定的管线语义,给出实际应提交的 0xAARRGGBB(直通语义) */
    fun fadeColor(mode: BlendMode, argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        if (a == 255) return argb
        return when (fade(mode)) {
            Fade.PREMULTIPLY -> ColorEvaluator.premultiplyRgb(argb)

            // 单位元为白:rgb' = 255 − (255 − rgb) × a/255
            Fade.WHITE_LERP -> (a shl 24) or lerpToWhiteRgb(argb, a)

            Fade.BLACK_ALPHA -> a shl 24
            Fade.BLACK_ALPHA_SELF -> (a * (255 - a) / 255) shl 24
            Fade.BLACK_ALPHA_SQUARE -> (a * a / 255) shl 24

            Fade.NONE, Fade.SRC_OVER -> argb
        }
    }

    /** rgb 朝白插值(乘 / 取暗族的单位元) */
    private fun lerpToWhiteRgb(argb: Int, a: Int): Int {
        fun ch(v: Int) = 255 - ((255 - v) * a + 127) / 255
        return (ch(argb shr 16 and 0xFF) shl 16) or (ch(argb shr 8 and 0xFF) shl 8) or ch(argb and 0xFF)
    }

    /** SrcOver 用现成 pipeline,不建变体;不可表达的 12 种也返回 null(回退 SrcOver) */
    fun blendFunction(mode: BlendMode): BlendFunction? = when (mode) {
        BlendMode.Clear -> BlendFunction(BlendFactor.ZERO, BlendFactor.ZERO, BlendFactor.ZERO, BlendFactor.ZERO)
        BlendMode.Src -> BlendFunction(BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE, BlendFactor.ZERO)
        BlendMode.Dst -> BlendFunction(BlendFactor.ZERO, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE)
        BlendMode.SrcOver -> null // 现成 TRANSLUCENT
        BlendMode.DstOver -> BlendFunction(
            BlendFactor.ONE_MINUS_DST_ALPHA, BlendFactor.ONE,
            BlendFactor.ONE_MINUS_DST_ALPHA, BlendFactor.ONE,
        )
        BlendMode.SrcIn -> BlendFunction(BlendFactor.DST_ALPHA, BlendFactor.ZERO, BlendFactor.DST_ALPHA, BlendFactor.ZERO)
        BlendMode.DstIn -> BlendFunction(BlendFactor.ZERO, BlendFactor.SRC_ALPHA, BlendFactor.ZERO, BlendFactor.SRC_ALPHA)
        BlendMode.SrcOut -> BlendFunction(
            BlendFactor.ZERO, BlendFactor.ONE_MINUS_DST_ALPHA,
            BlendFactor.ZERO, BlendFactor.ONE_MINUS_DST_ALPHA,
        )
        BlendMode.DstOut -> BlendFunction(
            BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO,
            BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO,
        )
        BlendMode.SrcAtop -> BlendFunction(
            BlendFactor.DST_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
            BlendFactor.DST_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
        )
        BlendMode.DstAtop -> BlendFunction(
            BlendFactor.ONE_MINUS_DST_ALPHA, BlendFactor.SRC_ALPHA,
            BlendFactor.ONE_MINUS_DST_ALPHA, BlendFactor.SRC_ALPHA,
        )
        BlendMode.Xor -> BlendFunction(
            BlendFactor.ONE_MINUS_DST_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
            BlendFactor.ONE_MINUS_DST_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
        )
        BlendMode.Plus -> BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ONE)
        // Modulate:color = src×dst(DST_COLOR/ZERO);alpha = src.a×dst.a(DST_ALPHA/ZERO)
        BlendMode.Modulate -> BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ZERO, BlendFactor.DST_ALPHA, BlendFactor.ZERO)
        // Screen:color = src + dst×(1-src);alpha = src.a + dst.a×(1-src.a)
        BlendMode.Screen -> BlendFunction(
            BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_COLOR,
            BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA,
        )
        // 逐通道 min/max(Skia kDarken/kLighten 的 alpha 加权近似)
        BlendMode.Darken -> BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MIN)
        BlendMode.Lighten -> BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX)
        // 12 种高级模式:GL 固定功能不可表达,回退 SrcOver
        BlendMode.Overlay, BlendMode.Hardlight, BlendMode.Softlight,
        BlendMode.ColorDodge, BlendMode.ColorBurn,
        BlendMode.Difference, BlendMode.Exclusion, BlendMode.Multiply,
        BlendMode.Hue, BlendMode.Saturation, BlendMode.Color, BlendMode.Luminosity,
        -> null
        // 兜底(移植版 value class 无法穷尽)
        else -> null
    }

    private val guiPipelines = ConcurrentHashMap<BlendMode, RenderPipeline>()
    private val trianglePipelines = ConcurrentHashMap<BlendMode, RenderPipeline>()
    private val strokePipelines = ConcurrentHashMap<BlendMode, RenderPipeline>()
    private val texturedPipelines = ConcurrentHashMap<BlendMode, RenderPipeline>()
    private val texturedFadePipelines = ConcurrentHashMap<BlendMode, RenderPipeline>()

    /**
     * blit 组 blend pipeline:core/gui shader + POSITION_COLOR + QUADS,
     * 与 RenderPipelines.GUI 同构只换 BlendFunction;SrcOver/不支持返回 null。
     */
    fun guiFor(mode: BlendMode): RenderPipeline? {
        val fn = blendFunction(mode) ?: return null
        return guiPipelines.computeIfAbsent(mode) {
            RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("compose_minecraft", "pipeline/gui_blend_${mode.toString().lowercase()}"))
                .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/gui"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("minecraft", "core/gui"))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withColorTargetState(ColorTargetState(fn))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build()
        }
    }

    /**
     * 纹理组 blend pipeline:与 [RenderPipelines.GUI_TEXTURED] 同构
     * (GLOBALS + MATRICES_PROJECTION + SAMPLER0 + `core/position_tex_color` + POSITION_TEX_COLOR
     * + QUADS),只把 ColorTargetState 换成该模式的混合函数 —— 混合函数属于 pipeline 层,
     * 与着色器无关,所以每个模式**不需要各自写着色器**。
     *
     * 唯一的着色器差异:[Fade.WHITE_LERP] 族(乘 / 取暗)的淡出需要对**逐像素**源色做朝白插值
     * (纯色路径由 [fadeColor] 在 CPU 侧完成,纹理的源色在纹理里),故该族换用
     * `compose_minecraft:core/position_tex_color_fade`(顶点着色器仍是原版),其余模式用原版片元着色器。
     *
     * SrcOver 与 12 种不可表达模式返回 null(调用方保持原 pipeline)。
     */
    fun texturedFor(mode: BlendMode): RenderPipeline? {
        val fn = blendFunction(mode) ?: return null
        val policy = fade(mode)
        return if (policy == Fade.WHITE_LERP) {
            texturedFadePipelines.computeIfAbsent(mode) { buildTextured(mode, fn, FADE_FRAGMENT_SHADER) }
        } else {
            texturedPipelines.computeIfAbsent(mode) { buildTextured(mode, fn, VANILLA_TEXTURED_SHADER) }
        }
    }

    /**
     * 纹理路径提交的调制色:[Fade.WHITE_LERP] 族保持 rgb 不变(插值在着色器里做,alpha 仍携带
     * 透明度),其余族与纯色路径同一解算([fadeColor])。
     */
    fun fadeColorForTexture(mode: BlendMode, argb: Int): Int =
        if (fade(mode) == Fade.WHITE_LERP) argb else fadeColor(mode, argb)

    private fun buildTextured(mode: BlendMode, fn: BlendFunction, fragmentShader: Identifier): RenderPipeline =
        RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("compose_minecraft", "pipeline/gui_textured_blend_${mode.toString().lowercase()}"))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withVertexShader(VANILLA_TEXTURED_SHADER)
            .withFragmentShader(fragmentShader)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withColorTargetState(ColorTargetState(fn))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build()

    /**
     * 三角化组 blend pipeline(填充):自写 gui_triangles shader + TRIANGLES,
     * 与 MinecraftGuiTriangles.pipeline 同构只换 BlendFunction。
     */
    fun trianglesFor(mode: BlendMode): RenderPipeline? {
        val fn = blendFunction(mode) ?: return null
        return trianglePipelines.computeIfAbsent(mode) {
            RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("compose_minecraft", "pipeline/gui_triangles_blend_${mode.toString().lowercase()}"))
                .withVertexShader(Identifier.fromNamespaceAndPath("compose_minecraft", "core/gui_triangles"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("compose_minecraft", "core/gui_triangles"))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withColorTargetState(ColorTargetState(fn))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build()
        }
    }

    /** 三角化组 blend pipeline(描边):gui_triangles_stroke shader */
    fun trianglesStrokeFor(mode: BlendMode): RenderPipeline? {
        val fn = blendFunction(mode) ?: return null
        return strokePipelines.computeIfAbsent(mode) {
            RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("compose_minecraft", "pipeline/gui_triangles_stroke_blend_${mode.toString().lowercase()}"))
                .withVertexShader(Identifier.fromNamespaceAndPath("compose_minecraft", "core/gui_triangles_stroke"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("compose_minecraft", "core/gui_triangles_stroke"))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withColorTargetState(ColorTargetState(fn))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build()
        }
    }

    /** 已编译的 pipeline 集合(懒创建后首次 ensureCompiled 才编译,幂等) */
    private val compiledPipelines = java.util.Collections.newSetFromMap(ConcurrentHashMap<RenderPipeline, Boolean>())

    /**
     * 确保所有已创建的 blend pipeline 的 shader 已编译(幂等,每个 pipeline 只编译一次)。
     * 必须在主线程、渲染上下文活跃时调用(与 MinecraftGuiTriangles.ensureCompiled
     * 相同约束);blend pipeline 懒创建(首次绘制对应 blend 才存在),因此每次调用
     * 都扫描当前集合,编译其中尚未编译的 —— 首次绘制的帧由 MC 的 setPipeline
     * 自动编译兜底,次帧起本方法接管。
     */
    fun ensureCompiled() {
        val device = RenderSystem.getDevice()
        val resourceProvider = mc.resourceManager
        val shaderSource = ShaderSource { id, type ->
            val location = type.idConverter().idToFile(id)
            try {
                resourceProvider.getResourceOrThrow(location).openAsReader().use { it.readText() }
            } catch (e: Exception) {
                LOGGER.error("Compose-Minecraft: failed to read shader {}", location, e)
                null
            }
        }
        val pipelines = mutableListOf<RenderPipeline>()
        pipelines += guiPipelines.values
        pipelines += trianglePipelines.values
        pipelines += strokePipelines.values
        pipelines += texturedPipelines.values
        pipelines += texturedFadePipelines.values
        for (p in pipelines) {
            if (!compiledPipelines.add(p)) continue
            try {
                device.precompilePipeline(p, shaderSource)
            } catch (e: Exception) {
                LOGGER.error("Compose-Minecraft: failed to compile blend pipeline {}", p.getLocation(), e)
            }
        }
    }
}
