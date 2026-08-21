package moe.forpleuvoir.compose_minecraft.platform.render.util
import moe.forpleuvoir.compose_minecraft.mc

import net.minecraft.client.Minecraft

/**
 * 原版 guiScale 工具(T.39):读取当前窗口 guiScale,或按原版算法独立计算。
 *
 * 原版链路(Minecraft.tick → `window.calculateScale(options.guiScale, enforceUnicode)` → `window.setGuiScale`):
 * `calculateScale` 从 1 起自增,受 maxScale(设置值,0 = 自动)与最小逻辑分辨率
 * (320x240)约束;enforceUnicode 且 scale 为奇数时 +1。最终 `window.guiScale()`
 * 即每帧计算好的当前值。
 */
object MinecraftGuiScale {

    /** 原版约束:逻辑分辨率下限(calculateScale 循环条件)。 */
    const val MIN_GUI_WIDTH = 320
    const val MIN_GUI_HEIGHT = 240

    /**
     * 当前窗口的实际 guiScale(每帧由原版计算好),至少 1(安全下限)。
     */
    fun current(): Float = mc.window.guiScale.toFloat().coerceAtLeast(1f)

    /**
     * 复刻原版 [com.mojang.blaze3d.platform.Window.calculateScale]:
     * @param maxScale 用户设置(guiScale 选项;0 = 自动)。
     * @param enforceUnicode 强制 Unicode(原版 isEnforceUnicode,为 true 时 scale 取偶数)。
     * @param framebufferWidth 帧缓冲像素宽。
     * @param framebufferHeight 帧缓冲像素高。
     */
    fun computeScale(
        maxScale: Int,
        enforceUnicode: Boolean,
        framebufferWidth: Int,
        framebufferHeight: Int,
    ): Int {
        var scale = 1
        while (
            scale != maxScale
            && scale < framebufferWidth
            && scale < framebufferHeight
            && framebufferWidth / (scale + 1) >= MIN_GUI_WIDTH
            && framebufferHeight / (scale + 1) >= MIN_GUI_HEIGHT
        ) {
            scale++
        }
        if (enforceUnicode && scale % 2 != 0) {
            scale++
        }
        return scale
    }

    /**
     * 从当前窗口计算缩放后的逻辑(GUI 单位)尺寸:`framebuffer / scale`,
     * 与原版 `Window.setGuiScale` 的 guiScaledWidth/Height 语义一致
     * (除不尽时向上取整 +1 修正)。
     */
    fun scaledSize(framebufferWidth: Int, framebufferHeight: Int, scale: Int): Pair<Int, Int> {
        val width = framebufferWidth / scale
        val height = framebufferHeight / scale
        return Pair(
            if (framebufferWidth.toDouble() / scale > width) width + 1 else width,
            if (framebufferHeight.toDouble() / scale > height) height + 1 else height,
        )
    }
}