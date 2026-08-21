package moe.forpleuvoir.compose_minecraft.platform.render.state

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling
import net.minecraft.data.AtlasIds
import net.minecraft.resources.Identifier
import kotlin.jvm.optionals.getOrElse

/**
 * GUI atlas sprite 解析结果(T.39):sprite 本体 + 采样设置 + 缩放模式。
 *
 * [textureSetup] 取自 sprite 所属 atlas 纹理(`sprite.atlasLocation()` → TextureManager,
 * 与原版 `GuiGraphicsExtractor.blitTiledSprite/innerBlit` 一致 —— atlas 纹理由
 * AtlasManager 在资源重载时重建,view/sampler 生命周期由 AbstractTexture 管理)。
 */
class GuiSprite(
    val sprite: TextureAtlasSprite,
    val textureSetup: TextureSetup,
    val scaling: GuiSpriteScaling,
    /** atlas 纹理实际尺寸(像素),交叉核对 UV 用 */
    val atlasWidth: Int = 0,
    val atlasHeight: Int = 0,
) {
    /** 归一化 UV([0,1],来自 sprite 在 atlas 中的位置,无需按纹理尺寸除) */
    val u0: Float get() = sprite.u0
    val u1: Float get() = sprite.u1
    val v0: Float get() = sprite.v0
    val v1: Float get() = sprite.v1
}

/**
 * GUI atlas sprite 解析器(T.39):从 `AtlasIds.GUI` 图集取 sprite 并解析其
 * [GuiSpriteScaling](stretch / tile / nine_slice,数据来自 sprite meta `gui.scaling`)。
 *
 * 与原版 `GuiGraphicsExtractor.blitSprite` 的解析链路一致,但只做只读解析,
 * 不接触原版 GuiRenderState —— 绘制由 1:1 渲染管线([moe.forpleuvoir.compose_minecraft.platform.render.renderer.MinecraftTooltipRenderer])提交。
 */
object GuiSpriteResolver {

    /**
     * 解析 [identifier] 对应的 GUI sprite。
     * @return null 表示取不到(atlas 未就绪 / sprite 不存在 / 资源异常)。
     */
    fun resolve(identifier: Identifier): GuiSprite? {
        val mc = Minecraft.getInstance()
        return try {
            val sprite = mc.atlasManager.getAtlasOrThrow(AtlasIds.GUI).getSprite(identifier)
            val texture = mc.textureManager.getTexture(sprite.atlasLocation())
            val scaling = sprite.contents()
                .getAdditionalMetadata(GuiMetadataSection.TYPE)
                .getOrElse { GuiMetadataSection.DEFAULT }
                .scaling()
            GuiSprite(
                sprite = sprite,
                textureSetup = TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()),
                scaling = scaling,
                atlasWidth = texture.getTexture().getWidth(0),
                atlasHeight = texture.getTexture().getHeight(0),
            )
        } catch (_: Exception) {
            null
        }
    }
}