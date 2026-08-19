package moe.forpleuvoir.compose_minecraft.platform.render.plugins

import moe.forpleuvoir.compose_minecraft.platform.render.CustomDrawContext
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderPlugins
import moe.forpleuvoir.compose_minecraft.platform.render.toMatrix3x2f
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.roundToInt

/** 实体渲染数据:经 GuiGraphicsExtractor.entity() 渲染。 */
data class EntityDrawData(
    val entity: Entity,
    val size: Int,
)

/**
 * 内置实体插件:经 GuiGraphicsExtractor.entity() 渲染实体。
 * TAG: [Identifier]("compose_minecraft", "entity")
 */
object McEntityPlugin : MinecraftRenderPlugin {

    val TAG: Identifier = Identifier.fromNamespaceAndPath("compose_minecraft", "entity")

    override fun onDraw(tag: Identifier, data: Any?, context: CustomDrawContext): Boolean {
        if (tag != TAG) return false
        val dd = data as? EntityDrawData ?: return false
        val g = MinecraftRenderPlugins.currentGraphics ?: return false
        val mc = Minecraft.getInstance()
        val pose = context.matrix.toMatrix3x2f()
        val x = pose.m20.roundToInt()
        val y = pose.m21.roundToInt()
        val s = dd.size
        val renderState = mc.entityRenderDispatcher.extractEntity(dd.entity, 1f)
        val translation = Vector3f(0f, 0f, 0f)
        val rotation = Quaternionf().identity()
        g.entity(renderState, s.toFloat(), translation, rotation, null, x, y, x + s, y + s)
        return true
    }
}