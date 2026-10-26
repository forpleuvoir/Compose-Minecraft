package moe.forpleuvoir.compose_minecraft.platform.render.text

import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.ComposePipelineCache
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.resources.Identifier

// ─────────────────────────────────────────────────────────────────────────────
// 平台 TrueType 文本渲染管线(设计文档 §3「gui_text RenderPipeline」)
//
// 参照 MinecraftGuiTriangles 先例(自建 pipeline + 仓库内自写 core shader +
// 懒编译):拓扑 TRIANGLES、POSITION_TEX_COLOR 顶点格式(位置/图集 UV/顶点色)、
// TRANSLUCENT 混合;绑定布局 MATRICES_PROJECTION + SAMPLER0(与原版
// GUI_TEXTURED 同构,R8 图集经 TextureSetup.singleTexture 绑定到 Sampler0)。
//
// 片元语义:采样图集 R 通道得字形 coverage,乘顶点色 alpha → SrcOver 混合。
// ─────────────────────────────────────────────────────────────────────────────

/** 平台 gui_text 渲染管线与编译管理(结构对齐 MinecraftGuiTriangles) */
internal object MinecraftGuiText {

    /**
     * 文本 quad 管线:TRIANGLES 拓扑、POSITION_TEX_COLOR、TRANSLUCENT 混合,
     * shader 为自写的 core/gui_text(R8 coverage × 顶点色 tint)。
     * 不注册进 RenderPipelines(GuiRenderer 直接使用对象本身,无需注册表)。
     */
    val pipeline: RenderPipeline by lazy {
        RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("compose_minecraft", "pipeline/gui_text"))
            .withVertexShader(Identifier.fromNamespaceAndPath("compose_minecraft", "core/gui_text"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("compose_minecraft", "core/gui_text"))
            .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build()
    }

    @Volatile
    private var compiled = false

    /** 确保 pipeline 的 shader 已编译(幂等;主线程、渲染上下文活跃时调用) */
    fun ensureCompiled() {
        if (compiled) return
        ComposePipelineCache.compiled(pipeline)
        compiled = true
    }
}
