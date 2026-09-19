package moe.forpleuvoir.compose_minecraft.platform.render.plugins

import androidx.compose.ui.unit.IntSize
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import moe.forpleuvoir.compose_minecraft.platform.render.CustomDrawContext
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.ext.blitSprite
import moe.forpleuvoir.compose_minecraft.platform.render.paint.toArgb
import moe.forpleuvoir.compose_minecraft.platform.render.ext.pushNineSliced
import moe.forpleuvoir.compose_minecraft.platform.render.toMatrix3x2f
import moe.forpleuvoir.compose_minecraft.platform.render.toScreenRectangle
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.TiledBlitRenderState
import net.minecraft.resources.Identifier
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink

/**
 * 纹理子区域(像素坐标,相对纹理左上角)。[uStart]..[uEnd] 为水平范围、[vStart]..[vEnd] 为垂直范围,
 * 半开区间,渲染时除以纹理实际宽高归一化。
 */
data class UVMapping(
    val uStart: Int = 0,
    val vStart: Int = 0,
    val uEnd: Int = Int.MAX_VALUE,
    val vEnd: Int = Int.MAX_VALUE,
) {
    val width: Int get() = uEnd - uStart
    val height: Int get() = vEnd - vStart

    companion object {
        /** 全图(渲染时按纹理实际尺寸闭合) */
        val Full = UVMapping()

        /** 便捷构造:(起点, 起点, 尺寸, 尺寸) */
        fun uv(u: Int, v: Int, uSize: Int, vSize: Int) = UVMapping(u, v, u + uSize, v + vSize)
    }
}

/**
 * 九宫格角(像素)。支持负值:
 * - 正值:角从纹理子区域向内收缩指定像素,中心区域减小;
 * - 负值:角向区域外扩(绝对值)指定像素,中心区域不减该宽度,UV 同步向纹理外扩张。
 */
data class Corner(
    val left: Int = 0,
    val right: Int = 0,
    val top: Int = 0,
    val bottom: Int = 0,
) {
    constructor(vertical: Int = 0, horizontal: Int = 0) : this(
        left = horizontal, right = horizontal, top = vertical, bottom = vertical,
    )

    constructor(corner: Int = 0) : this(corner, corner, corner, corner)

    val width: Int get() = right + left
    val height: Int get() = bottom + top

    val isSpecified: Boolean get() = this != Unspecified

    companion object {
        val Unspecified = Corner(0)
    }
}

data class TextureDrawData(
    val textureId: Identifier,
    val size: IntSize,
    val pipeline: RenderPipeline,
    val filterMode: FilterMode? = null,
    /** 纹理子区域(像素坐标);[UVMapping.Full] = 整张纹理 */
    val uv: UVMapping = UVMapping.Full,
    /** 九宫格角(像素);[Corner.Unspecified] = 普通拉伸 */
    val corner: Corner = Corner.Unspecified,
    /** 非 null = tile 平铺模式(每块 [tileSize] 像素) */
    val tileSize: IntSize? = null,
)

/**
 * 原版精灵图绘制数据(R.1):经 [McTexturePlugin] 按原版 `GuiSpriteScaling`
 * (stretch / tile / nine_slice)渲染一个 GUI atlas sprite,由下沉到
 * [GuiCommandSink] 的
 * sprite 缩放扩展方法处理。与 [TextureDrawData](原生纹理 + 手动 UV/corner)正交。
 */
data class SpriteDrawData(
    val location: Identifier,
    val size: IntSize,
    val pipeline: RenderPipeline = RenderPipelines.GUI_TEXTURED,
)

/** 一次解析结果:纹理采样设置 + 实际尺寸(UV 归一化用) */
private class ResolvedTexture(
    val textureSetup: TextureSetup,
    val width: Int,
    val height: Int,
)

/**
 * 内置纹理插件:直接从 MC TextureManager 获取纹理,不走光栅化。
 * 支持两种正交输入(用 [data] 实际类型区分,共用同一 [TAG]):
 * - [TextureDrawData]:原生纹理 + 可选九宫格 / tile。九宫格([pushNineSliced]
 *   扩展,支持负值外扩)、tile([TiledBlitRenderState])、普通拉伸([BlitRenderState]);
 * - [SpriteDrawData]:原版 GUI atlas sprite,按原版 `GuiSpriteScaling` 缩放(即
 *   tooltip 背景 / bundle 进度条同一套原版 sprite 语义)。
 * 调制色:白色 + paint.alpha(与图片管线 blitImage 语义一致,不染色)。
 */
object McTexturePlugin : MinecraftRenderPlugin {

    val TAG: Identifier = Identifier.fromNamespaceAndPath("compose_minecraft", "texture")

    override fun onDraw(tag: Identifier, data: Any?, context: CustomDrawContext): Boolean {
        return tag == TAG && when (data) {
            is SpriteDrawData -> drawSprite(data, context)
            is TextureDrawData -> drawTexture(data, context)
            else -> false
        }
    }

    // ── 原版精灵图分支(SpriteDrawData) ───────────────────────────────

    private fun drawSprite(sd: SpriteDrawData, context: CustomDrawContext): Boolean {
        val w = sd.size.width
        val h = sd.size.height
        if (w <= 0 || h <= 0) return false
        context.sink.blitSprite(
            sd.pipeline, sd.location, 0, 0, w, h,
            tintColor(context),
            context.matrix.toMatrix3x2f(),
            context.scissor?.toScreenRectangle(),
        )
        return true
    }

    // ── 原生纹理分支(TextureDrawData) ────────────────────────────────

    private fun drawTexture(td: TextureDrawData, context: CustomDrawContext): Boolean {
        // 一次解析:纹理采样设置 + 实际尺寸(取不到则无法渲染)
        val resolved = resolve(td) ?: return false
        val textureSetup = resolved.textureSetup
        val tw = resolved.width
        val th = resolved.height

        val pose = context.matrix.toMatrix3x2f()
        // 色调色:取自 context.paint(MinecraftPaint,含 replayFrom 烘焙的 alphaMultiplier),
        // color 为着色器色彩调制器(默认白 = 纹理原色),alpha 为复合透明度
        val color = tintColor(context)

        val w = td.size.width
        val h = td.size.height
        if (w <= 0 || h <= 0) return false

        when {
            // tile 平铺模式
            td.tileSize != null   -> {
                val ts = td.tileSize
                context.sink.addElement(
                    TiledBlitRenderState(
                        td.pipeline, textureSetup, pose,
                        ts.width.coerceAtLeast(1), ts.height.coerceAtLeast(1),
                        0, 0, w, h,
                        u0(td, tw), u1(td, tw), v0(td, th), v1(td, th),
                        color,
                        context.scissor?.toScreenRectangle(),
                    )
                )
            }
            // 九宫格模式:下沉到 GuiCommandSink.pushNineSliced 扩展(负值外扩语义保留)
            td.corner.isSpecified -> {
                context.sink.pushNineSliced(
                    td.pipeline,
                    td.uv, td.corner,
                    td.size,
                    color, textureSetup,
                    tw, th,
                    pose,
                    context.scissor?.toScreenRectangle(),
                )
            }
            // 普通拉伸
            else                  -> {
                context.sink.addElement(
                    BlitRenderState(
                        td.pipeline, textureSetup, pose,
                        0, 0, w, h,
                        u0(td, tw), u1(td, tw), v0(td, th), v1(td, th),
                        color,
                        context.scissor?.toScreenRectangle(),
                    )
                )
            }
        }
        return true
    }

    /** 统一色调色:取自 context.paint(默认白色 + alpha,不染色)。 */
    private fun tintColor(context: CustomDrawContext): Int {
        val paint = context.paint
        return paint?.color?.toArgb(paint.alpha) ?: 0xFFFFFFFF.toInt()
    }

    // ── UV 归一化(像素 → [0,1],按纹理实际尺寸) ─────────────────────────

    private fun u0(td: TextureDrawData, tw: Int): Float = td.uv.uStart.coerceAtLeast(0).toFloat() / tw

    private fun u1(td: TextureDrawData, tw: Int): Float = td.uv.uEnd.coerceIn(0, tw).toFloat() / tw

    private fun v0(td: TextureDrawData, th: Int): Float = td.uv.vStart.coerceAtLeast(0).toFloat() / th

    private fun v1(td: TextureDrawData, th: Int): Float = td.uv.vEnd.coerceIn(0, th).toFloat() / th

    /**
     * 跟随原版:直接复用 [AbstractTexture.getTextureView](纹理自带 view,生命周期由
     * AbstractTexture 管理,资源重载自动重建);sampler 同理用纹理自带或指定过滤模式。
     */
    private fun resolve(data: TextureDrawData): ResolvedTexture? {
        val mc = Minecraft.getInstance()
        return try {
            val texture = mc.textureManager.getTexture(data.textureId)
            val gpu = texture.getTexture()
            // filterMode 为空 → 使用纹理自带的 sampler;非空 → 使用指定过滤模式
            val sampler = if (data.filterMode != null) {
                RenderSystem.getSamplerCache().getClampToEdge(data.filterMode)
            } else {
                texture.getSampler()
            }
            ResolvedTexture(
                TextureSetup.singleTexture(texture.textureView, sampler),
                gpu.getWidth(0),
                gpu.getHeight(0),
            )
        } catch (_: Exception) {
            null
        }
    }
}
