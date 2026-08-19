package moe.forpleuvoir.compose_minecraft.platform.render

import androidx.compose.ui.unit.IntSize
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import net.minecraft.client.renderer.state.gui.ScreenArea
import net.minecraft.util.Mth
import org.joml.Matrix3x2f

/**
 * 物品渲染状态(T.37):照抄原版 [GuiItemRenderState](ScreenArea 行为:oversizedBounds/bounds 计算),
 * 增加 [size](目标槽位尺寸,宽高可分离,替代写死 16)与 [color](0xAARRGGBB 调制色,-1 = 白色不调制)。
 * public —— 外部 mod 可构造,经 [GuiCommandSink.addItem] 提交。
 */
class ItemRenderState(
    val pose: Matrix3x2f,
    val itemStackRenderState: TrackingItemStackRenderState,
    val x: Int,
    val y: Int,
    val size: IntSize = IntSize(16, 16),
    val color: Int = -1,
    val scissorArea: ScreenRectangle? = null,
    /**
     * 离屏渲染器缓存的稳定 key(如 [ItemStack.item] 单例);缺省回退 [modelIdentity]。
     * [modelIdentity](List)由 [updateForTopItem] 每帧重建,hashCode 不稳定会导致
     * 缓存每帧 miss → 每帧新建纹理爆显存,故需要稳定 key。
     */
    val identityKey: Any? = null,
) : ScreenArea {

    /** 原件 [GuiItemRenderState] 构造:仅当 [isOversizedInGui](26.2 恒 false)时走 PIP;否则走 atlas。 */
    private val oversizedItemBounds: ScreenRectangle? =
        if (itemStackRenderState.isOversizedInGui) calculateOversizedItemBounds() else null

    private val bounds: ScreenRectangle =
        calculateBounds(oversizedItemBounds ?: ScreenRectangle(x, y, size.width, size.height))

    /** 原件:模型 bounding box 超槽位时返回实际命中框(基于 [size] 缩放),否则 null。 */
    private fun calculateOversizedItemBounds(): ScreenRectangle? {
        val aabb = itemStackRenderState.modelBoundingBox
        val actualXSize = Mth.ceil(aabb.xsize * size.width)
        val actualYSize = Mth.ceil(aabb.ysize * size.height)
        if (actualXSize <= size.width && actualYSize <= size.height) return null
        val xOffset = (aabb.minX * size.width).toFloat()
        val yOffset = (aabb.maxY * size.height).toFloat()
        return ScreenRectangle(
            x + Mth.floor(xOffset) + size.width / 2,
            y - Mth.floor(yOffset) + size.height / 2,
            actualXSize,
            actualYSize,
        )
    }

    /** 原件:经 pose 变换 + scissor 相交。 */
    private fun calculateBounds(itemBounds: ScreenRectangle): ScreenRectangle {
        val transformed = itemBounds.transformMaxBounds(pose)
        return scissorArea?.intersection(transformed) ?: transformed
    }

    fun oversizedItemBounds(): ScreenRectangle? = oversizedItemBounds

    override fun bounds(): ScreenRectangle = bounds

    override fun toString(): String =
        "ItemRenderState(pose=$pose, itemStackRenderState=$itemStackRenderState, x=$x, y=$y, size=$size, color=$color)"
}