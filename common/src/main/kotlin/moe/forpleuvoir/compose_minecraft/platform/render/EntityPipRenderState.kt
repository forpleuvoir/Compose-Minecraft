package moe.forpleuvoir.compose_minecraft.platform.render

import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState
import org.joml.Matrix3x2f

/**
 * 实体画中画渲染状态(T.37):包装原版 [GuiEntityRenderState] + 色调色 + 叠加 pose + 稳定缓存 key。
 * 经 [GuiCommandSink.addEntity] 提交,由 [ComposeOversizedEntityRenderer] 离屏渲染。
 *
 * [GuiEntityRenderState] 是 vanilla record,其 [GuiEntityRenderState.pose] 恒为恒等矩阵
 * (无位置信息),故在其外套一层携带 Compose 布局矩阵(与物品的 [ItemRenderState.pose]
 * 同模式)—— blit 时经 [pose] 变换到实际屏幕位置,否则所有实体叠在原点。
 */
class EntityPipRenderState(
    val state: GuiEntityRenderState,
    /** Compose 布局矩阵(位置/缩放/旋转),blit 时把局部矩形变换到实际屏幕位置。 */
    val pose: Matrix3x2f,
    /** 色调色(0xAARRGGBB),-1 = 无调制(默认白色)。仅作用于 PIP 输出纹理 blit。 */
    val color: Int = -1,
    /**
     * 离屏渲染器缓存的稳定 key(如 [System.identityHashCode] 实体实例标识);
     * 同类型多实体实例各自独立纹理,防止同帧互相覆盖。
     */
    val identityKey: Any,
)
