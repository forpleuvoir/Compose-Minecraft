package moe.forpleuvoir.compose_minecraft.platform.render.plugins

import androidx.compose.ui.unit.IntSize
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import moe.forpleuvoir.compose_minecraft.platform.render.CustomDrawContext
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.paint.toArgb
import moe.forpleuvoir.compose_minecraft.platform.render.toMatrix3x2f
import moe.forpleuvoir.compose_minecraft.platform.render.toScreenRectangle
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.TiledBlitRenderState
import net.minecraft.resources.Identifier
import kotlin.math.absoluteValue

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

/** 一次解析结果:纹理采样设置 + 实际尺寸(UV 归一化用) */
private class ResolvedTexture(
    val textureSetup: TextureSetup,
    val width: Int,
    val height: Int,
)

/**
 * 内置纹理插件:直接从 MC TextureManager 获取纹理,不走光栅化。
 * 支持三种模式:
 * - 普通: [BlitRenderState](自定义 UV 拉伸);
 * - 九宫格: corner 指定时拆 9 段(角固定、中心拉伸,支持负值外扩);
 * - tile: tileSize 指定时 [TiledBlitRenderState] 平铺。
 * 调制色:白色 + paint.alpha(与图片管线 blitImage 语义一致,不染色)。
 */
object McTexturePlugin : MinecraftRenderPlugin {

    val TAG: Identifier = Identifier.fromNamespaceAndPath("compose_minecraft", "texture")

    override fun onDraw(tag: Identifier, data: Any?, context: CustomDrawContext): Boolean {
        if (tag != TAG) return false
        val td = data as? TextureDrawData ?: return false

        // 一次解析:纹理采样设置 + 实际尺寸(取不到则无法渲染)
        val resolved = resolve(td) ?: return false
        val textureSetup = resolved.textureSetup
        val tw = resolved.width
        val th = resolved.height

        val pose = context.matrix.toMatrix3x2f()
        // 色调色:取自 context.paint(MinecraftPaint,含 replayFrom 烘焙的 alphaMultiplier),
        // color 为着色器色彩调制器(默认白 = 纹理原色),alpha 为复合透明度
        val paint = context.paint
        val color = paint?.color?.toArgb(paint.alpha) ?: 0xFFFFFFFF.toInt()

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
            // 九宫格模式
            td.corner.isSpecified -> {
                pushNineSliced(td, pose, color, textureSetup, tw, th, context)
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

    /**
     * 九宫格 9 段绘制(参考 ibukigourd pushNineSlicedBlit):
     * corner 正值向内收缩、负值向区域外扩(绝对值),中心区域只减正值宽度,UV 同步外扩。
     */
    private fun pushNineSliced(
        td: TextureDrawData,
        pose: org.joml.Matrix3x2f,
        color: Int,
        textureSetup: TextureSetup,
        tw: Int,
        th: Int,
        context: CustomDrawContext,
    ) {
        val corner = td.corner
        val u0 = td.uv.uStart
        val v0 = td.uv.vStart
        val u1 = td.uv.width
        val v1 = td.uv.height
        val x = 0
        val y = 0
        val width = td.size.width
        val height = td.size.height

        val cl = corner.left.absoluteValue
        val cr = corner.right.absoluteValue
        val ct = corner.top.absoluteValue
        val cb = corner.bottom.absoluteValue

        // 中心区域:只减正值(负值外扩,中心覆盖整个区域)
        val cw = width - (corner.left.coerceAtLeast(0) + corner.right.coerceAtLeast(0))
        val ch = height - (corner.top.coerceAtLeast(0) + corner.bottom.coerceAtLeast(0))

        val leftX = if (corner.left >= 0) x else x - cl
        val centerX = if (corner.left >= 0) x + cl else x
        val rightX = if (corner.right >= 0) x + (width - corner.right) else x + width

        val topY = if (corner.top >= 0) y else y - ct
        val centerY = if (corner.top >= 0) y + ct else y
        val bottomY = if (corner.bottom >= 0) y + (height - corner.bottom) else y + height

        val leftU = if (corner.left >= 0) u0 else u0 - cl
        val centerU = if (corner.left >= 0) u0 + cl else u0
        val rightU = if (corner.right >= 0) u0 + (u1 - cr) else u0 + u1

        val topV = if (corner.top >= 0) v0 else v0 - ct
        val centerV = if (corner.top >= 0) v0 + ct else v0
        val bottomV = if (corner.bottom >= 0) v0 + (v1 - cb) else v0 + v1

        val leftUS = cl
        val centerUS = u1 - (corner.left.coerceAtLeast(0) + corner.right.coerceAtLeast(0))
        val rightUS = cr

        val topVS = ct
        val centerVS = v1 - (corner.top.coerceAtLeast(0) + corner.bottom.coerceAtLeast(0))
        val bottomVS = cb

        // 9 段:四个角原尺寸、四条边单向拉伸、中心双向拉伸
        segment(pose, td.pipeline, leftX, topY, cl, ct, leftU, topV, leftUS, topVS, color, tw, th, textureSetup, context)
        segment(pose, td.pipeline, centerX, topY, cw, ct, centerU, topV, centerUS, topVS, color, tw, th, textureSetup, context)
        segment(pose, td.pipeline, rightX, topY, cr, ct, rightU, topV, rightUS, topVS, color, tw, th, textureSetup, context)

        segment(pose, td.pipeline, leftX, centerY, cl, ch, leftU, centerV, leftUS, centerVS, color, tw, th, textureSetup, context)
        segment(pose, td.pipeline, centerX, centerY, cw, ch, centerU, centerV, centerUS, centerVS, color, tw, th, textureSetup, context)
        segment(pose, td.pipeline, rightX, centerY, cr, ch, rightU, centerV, rightUS, centerVS, color, tw, th, textureSetup, context)

        segment(pose, td.pipeline, leftX, bottomY, cl, cb, leftU, bottomV, leftUS, bottomVS, color, tw, th, textureSetup, context)
        segment(pose, td.pipeline, centerX, bottomY, cw, cb, centerU, bottomV, centerUS, bottomVS, color, tw, th, textureSetup, context)
        segment(pose, td.pipeline, rightX, bottomY, cr, cb, rightU, bottomV, rightUS, bottomVS, color, tw, th, textureSetup, context)
    }

    /** 九宫格单段:像素 UV 归一化后提交 [BlitRenderState] */
    private fun segment(
        pose: org.joml.Matrix3x2f,
        pipeline: RenderPipeline,
        x: Int, y: Int, w: Int, h: Int,
        u: Int, v: Int, uSize: Int, vSize: Int,
        color: Int, tw: Int, th: Int,
        textureSetup: TextureSetup,
        context: CustomDrawContext,
    ) {
        if (w <= 0 || h <= 0 || color and 0xFF000000.toInt() == 0) return
        context.sink.addElement(
            BlitRenderState(
                pipeline,
                textureSetup,
                pose,
                x, y, x + w, y + h,
                u.toFloat() / tw,
                (u + uSize).toFloat() / tw,
                v.toFloat() / th,
                (v + vSize).toFloat() / th,
                color,
                context.scissor?.toScreenRectangle(),
            )
        )
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