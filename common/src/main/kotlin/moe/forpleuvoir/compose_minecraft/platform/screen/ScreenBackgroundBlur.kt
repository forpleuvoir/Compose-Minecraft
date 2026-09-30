package moe.forpleuvoir.compose_minecraft.platform.screen

/**
 * Compose 屏幕的背景模糊策略。
 *
 * 模糊对象是**提交 Compose 内容之前**已画入主渲染目标的内容(世界、原版父屏、原版 GUI);
 * 模糊之后提交的 Compose 内容保持锐利。链路与原版
 * [net.minecraft.client.gui.screens.Screen] 的背景模糊一致(blur 后处理链,
 * 半径取 Globals UBO 的 `MenuBlurRadius`)。
 *
 * 逐屏 [ComposeScreen.backgroundBlur] 可在运行时切换;新建屏的默认值取
 * [ComposeScreenDefaults.backgroundBlur],对话框取 [DialogComposeScreenDefaults.backgroundBlur]。
 */
sealed interface ScreenBackgroundBlur {

    /** 不模糊背景。 */
    object None : ScreenBackgroundBlur

    /**
     * 跟随原版「菜单背景模糊度」设置
     * ([net.minecraft.client.Options.getMenuBackgroundBlurriness]):设置值 < 1 时不模糊,
     * 否则按该值作为模糊半径(原版 [net.minecraft.client.gui.screens.Screen.extractBackground]
     * 同款判定)。
     */
    object Vanilla : ScreenBackgroundBlur

    /**
     * 忽略原版设置,始终按 [radius] 模糊。
     *
     * @param radius 模糊半径(采样像素数);小于 1 视为不模糊,大于 [MAX_RADIUS] 按上限处理
     */
    data class Fixed(val radius: Int = DEFAULT_RADIUS) : ScreenBackgroundBlur {

        companion object {
            /** 原版「菜单背景模糊度」默认值。 */
            const val DEFAULT_RADIUS: Int = 5

            /** 半径上限(原版取值域上限;box_blur 的采样次数随半径线性增长)。 */
            const val MAX_RADIUS: Int = 10
        }
    }
}
