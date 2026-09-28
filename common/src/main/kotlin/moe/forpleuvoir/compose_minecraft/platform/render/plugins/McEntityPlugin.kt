package moe.forpleuvoir.compose_minecraft.platform.render.plugins

import moe.forpleuvoir.compose_minecraft.platform.render.CustomDrawContext
import moe.forpleuvoir.compose_minecraft.platform.render.pip.EntityPipRenderState
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.paint.toArgb
import moe.forpleuvoir.compose_minecraft.platform.render.toMatrix3x2f
import moe.forpleuvoir.compose_minecraft.platform.render.toScreenRectangle
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import org.joml.Quaternionf
import org.joml.Vector3f
import moe.forpleuvoir.compose_minecraft.platform.render.pip.ComposeOversizedEntityRenderer

/** 实体渲染数据:经 [EntityPipRenderState] 离屏 PIP 渲染(1:1 像素通道)。 */
data class EntityDrawData(
    val entity: Entity,
    val size: Int,
    /** 色调色(0xAARRGGBB),-1 = 无调制(默认白色)。仅作用于 PIP 输出纹理 blit。 */
    val color: Int = -1,
    /** X 轴旋转(弧度,正 = 前倾)。 */
    val rotationX: Float = 0f,
    /** Y 轴旋转(弧度,正 = 向右转)。 */
    val rotationY: Float = 0f,
    /** 模型占渲染方块高度的比例(缺省 0.85,其余留白):调小可给方块顶部腾出空间。 */
    val fill: Float = 0.85f,
    /** 模型在方块内的纵向位移(格,负值下沉),与压缩 [fill] 后的留白配合定位。 */
    val offsetY: Float = 0f,
    /** 覆写相机角度(缺省 null = 不覆写):用于整体倾斜视角。 */
    val cameraAngle: Quaternionf? = null,
    /** 直接使用调用方准备好的渲染状态(缺省 null = 由 [entity] 现场抽取):调用方可先清空世界中的朝向/缩放。 */
    val renderState: EntityRenderState? = null,
    /** 覆写整体旋转四元数(缺省 null = 用 [rotationX] / [rotationY] 现拼)。 */
    val rotation: Quaternionf? = null,
)

/**
 * 内置实体插件:把实体经 [EntityPipRenderState] 提交到 Compose 1:1 像素管线,
 * 由 [ComposeOversizedEntityRenderer]
 * 离屏 PIP 渲染后再 blit。与 [McItemPlugin] 同通道
 * (不依赖原版 GuiGraphicsExtractor 的 guiScale 通道),坐标/缩放 1:1。
 *
 * TAG: [Identifier]("compose_minecraft", "entity")
 */
object McEntityPlugin : MinecraftRenderPlugin {

    val TAG: Identifier = Identifier.fromNamespaceAndPath("compose_minecraft", "entity")

    override fun onDraw(tag: Identifier, data: Any?, context: CustomDrawContext): Boolean {
        if (tag != TAG) return false
        val dd = data as? EntityDrawData ?: return false
        if (dd.size <= 0) return false
        val mc = Minecraft.getInstance()

        // 抽取实体渲染数据(每帧 fresh,partialTicks=0:GUI 内不随逻辑 tick 插值)
        val entityRenderState: EntityRenderState = dd.renderState ?: mc.entityRenderDispatcher.extractEntity(dd.entity, 0f)

        // 实体按实际高度适配格子:模型 1 单位高度 = scale 像素,目标为实体占格子 ~85% 高度留边
        // (玩家 1.8 格高 → size/1.8*0.85≈55% 更合理的视觉比例;过大的全尺寸 96 会让 1.8 格
        // 玩家渲染出 173px,溢出格子)。boundingBoxHeight 由 extractEntity 填充(entity.getBbHeight)。
        val scale: Float = run {
            val h = entityRenderState.boundingBoxHeight
            if (h > 0f) dd.size / h * dd.fill else dd.size.toFloat()
        }

        // 色调色取自 data(与物品/纹理一致:pipeline 预乘 alpha),无则白(-1)
        val color = if (dd.color == -1) context.paint?.let { p -> p.color.toArgb(p.alpha) } ?: -1 else dd.color

        // 绕 Y 轴旋转:与原版 GuiEntityRenderer 的 rotation 语义一致。
        // 叠加 X 轴前倾(rotationX):先绕 X 后绕 Y(与  内旋 XYZ 语义对齐)。
        val baseRotation = dd.rotation ?: Quaternionf().rotateX(dd.rotationX).rotateY(dd.rotationY)

        // 提交为 EntityPipRenderState(sink.addEntity 接收),ComposeGuiRenderer 在
        // prepare 阶段分发到 ComposeOversizedEntityRenderer(离屏 PIP → blit)。
        // pose = Compose 布局矩阵(定位实体到实际屏幕位置,x0/y0 为局部原点);
        // bounds 用 (x0,y0,x1,y1)+ scissor 钳制,与原版 PictureInPictureRenderState.getBounds 语义一致。
        context.sink.addEntity(
            EntityPipRenderState(
                state = GuiEntityRenderState(
                    entityRenderState,
                    Vector3f(0f, dd.offsetY, 0f),
                    baseRotation,
                    dd.cameraAngle, // null = 不覆写相机角度
                    0, 0, dd.size, dd.size,
                    scale, // scale:实体 1 单位高度 = scale 像素,按 boundingBoxHeight 归一化留边
                    context.scissor?.toScreenRectangle(),
                ),
                pose = context.matrix.toMatrix3x2f(),
                color = color,
                // 稳定缓存 key:实体实例标识(跨帧复用纹理,同类型多实例互不覆盖)
                identityKey = System.identityHashCode(dd.entity),
            )
        )
        return true
    }
}