package moe.forpleuvoir.compose_minecraft.platform.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McEntityPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McItemPlugin
import moe.forpleuvoir.compose_minecraft.platform.render.plugins.McTexturePlugin
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import kotlin.math.roundToInt

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
 * 自定义渲染插件(T.37):mod 开发者通过实现本接口并注册到 [MinecraftRenderPlugins],
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

fun FloatArray.toMatrix3x2f(): Matrix3x2f =
    Matrix3x2f(this[0], this[1], this[4], this[5], this[12], this[13])

fun Rect.toScreenRectangle(): ScreenRectangle = ScreenRectangle(
    left.roundToInt(),
    top.roundToInt(),
    width.roundToInt(),
    height.roundToInt(),
)