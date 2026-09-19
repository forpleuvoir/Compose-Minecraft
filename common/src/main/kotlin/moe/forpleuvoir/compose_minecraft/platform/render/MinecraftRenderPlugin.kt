package moe.forpleuvoir.compose_minecraft.platform.render
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.GuiCommandSink

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McEntityPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McItemPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McTexturePlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McTooltipPlugin
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.MinecraftCanvas
import androidx.compose.ui.graphics.MinecraftPaint
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen

/**
 * 自定义渲染的上下文,透传给 [MinecraftRenderPlugin.onDraw]。
 */
class CustomDrawContext(
    val sink: GuiCommandSink,
    val matrix: FloatArray,
    val clip: Rect?,
    val scissor: Rect?,
    val layer3D: FloatArray?,
    /**
     * 绘制参数快照([MinecraftPaint] 实现,经 [MinecraftCanvas.PaintSnapshot] 重建):
     * 颜色/滤镜/混合模式/描边/着色器等,插件可读 [Paint] 接口全部公开字段。
     * null = 无独立绘制参数。
     */
    val paint: Paint?,
) {
    /** 整体透明度(0..1),便捷访问 */
    val alpha: Float get() = paint?.alpha ?: 1f
}

/**
 * 自定义渲染插件:mod 开发者通过实现本接口并注册到 [MinecraftRenderPlugins],
 * 在 Compose 1:1 像素管线中渲染 MC 原生内容。
 *
 * [onDraw] 参数 [tag] 标识渲染类型、[data] 携带渲染数据。
 * 返回 `true` 表示已处理,`false` 则继续传递。
 */
fun interface MinecraftRenderPlugin {

    /**
     * 处理自定义绘制命令。
     * @return true 表示已消费,false 让给下一个插件。
     */
    fun onDraw(tag: Identifier, data: Any?, context: CustomDrawContext): Boolean
}

/**
 * 自定义渲染插件注册表。后注册的插件优先级更高(后进先出)。
 *
 * 默认已注册内置插件:
 * - [McTexturePlugin]: 渲染 MC 原生纹理
 * - [McItemPlugin]: 渲染 MC 物品
 * - [McEntityPlugin]: 渲染 MC 实体
 */
object MinecraftRenderPlugins {

    private val plugins = mutableListOf<MinecraftRenderPlugin>()

    /**
     * 当前帧的 [GuiGraphicsExtractor],由 [ComposeScreen.extractRenderState] 在每帧
     * extract 阶段设置。供 [MinecraftRenderPlugin] 在 onDraw 中用于原版绘制。
     */
    var currentGraphics: GuiGraphicsExtractor? = null

    init {
        register(McTexturePlugin)
        register(McItemPlugin)
        register(McEntityPlugin)
        register(McTooltipPlugin)
    }

    fun register(plugin: MinecraftRenderPlugin) {
        plugins.add(plugin)
    }

    fun unregister(plugin: MinecraftRenderPlugin) {
        plugins.remove(plugin)
    }

    /**
     * 按后进先出的顺序分派给已注册的插件,直到某个插件返回 true。
     */
    fun dispatch(tag: Identifier, data: Any?, context: CustomDrawContext): Boolean {
        for (i in plugins.indices.reversed()) {
            if (plugins[i].onDraw(tag, data, context)) return true
        }
        return false
    }
}

// ── 扩展函数 ──────────────────────────────────────────────────────

/**
 * 命令矩阵(列主序 4x4)→ JOML [Matrix3x2f](列主序 3x2)。
 *
 * androidx `Matrix.values` 为列主序:values[0]=m00, values[1]=m10, values[4]=m01,
 * values[5]=m11, values[12]=m20, values[13]=m21;而 JOML 构造器参数序为
 * (m00, m01, m10, m11, m20, m21),两者顺序不同,故按 this[0], this[1], this[4],
 * this[5] 传入,等价于交换 m01/m10(对 2x2 预转置)。
 *
 * 为什么必须交换:MC 26.2 运行时打包的 JOML,`transformPosition` 是**行主序**实现
 * (x' = m00·x + m10·y + m20,与标准列主序 x' = m00·x + m01·y + m20 相反)。若按列主序
 * 直接传入,2x2 旋转矩阵会被**转置**:旋转方向反转,且绕 pivot 旋转时中心随角度摆动
 * (幅度 2·|sinθ|·|p|,表现为"公转")。纯缩放/平移不受影响(对角矩阵转置不变),
 * 因此"只有旋转异常、缩放平移正常"时应优先怀疑这里的 2x2 行列语义。
 */
fun FloatArray.toMatrix3x2f(): Matrix3x2f =
    Matrix3x2f(this[0], this[1], this[4], this[5], this[12], this[13])

fun Rect.toScreenRectangle(): ScreenRectangle = ScreenRectangle(
    left.roundToInt(),
    top.roundToInt(),
    width.roundToInt(),
    height.roundToInt(),
)