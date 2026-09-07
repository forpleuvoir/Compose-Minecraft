package moe.forpleuvoir.compose_minecraft.platform.screen

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.pointer.MinecraftPointerIcon
import androidx.compose.ui.input.pointer.MinecraftPointerIconKind
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.IntSize
import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes
import kotlinx.coroutines.awaitCancellation
import moe.forpleuvoir.compose_minecraft.platform.textinput.MinecraftTextInputService
import org.lwjgl.glfw.GLFW

/**
 * Minecraft 平台的 [PlatformContext] 实现(自 [MinecraftComposeScene] 内联匿名对象迁移)。
 *
 * 集中承载 Compose 场景的平台接入点:
 * - [windowInfo]:窗口聚焦与容器尺寸(Dialog 居中/Popup 裁剪的基础),尺寸读
 *   [MinecraftComposeScene.sceneContainerSize](独立字段,由 renderFrame 每帧同步);
 * - [textInputService]:MC IME 服务(自持实例,替代 EmptyPlatformTextInputService 默认值);
 * - [startInputMethod]:新版输入会话(BasicTextField(state))绑定/解绑 request;
 * - [setPointerIcon]:I9 指针图标 —— Compose 光标请求 → MC 原版光标类型,
 *   写入 [MinecraftComposeScene.desiredCursorType](经原版 per-frame 管线生效);
 * - [semanticsOwnerListener]:复述系统 —— 捕获场景/图层语义树所有者,
 *   语义变化时通知 [MinecraftComposeScene.onSemanticsChanged]。
 *
 * 注意:所有状态访问仅发生在 CanvasLayersComposeScene 构造与后续运行时,
 * 构造期只持有 [scene] 引用,不读取任何字段(避免属性初始化顺序 NPE)。
 *
 * 平台开放点:公开可继承 —— 依赖方模组可经 [MinecraftComposeScene] 的
 * platformContextFactory 注入子类(覆写部分成员)或完全自建实现。
 * 子类可通过 [scene] 访问场景公开状态(textInputService / sceneContainerSize /
 * desiredCursorType / onSemanticsChanged / getSemanticsOwners 等),
 * 并复用 [toMinecraftCursorType] 映射。
 */
@OptIn(InternalComposeUiApi::class)
open class MinecraftPlatformContext(
    protected val scene: MinecraftComposeScene,
) : PlatformContext.Empty() {

    companion object {
        val RESIZE_NWSE = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, "resize_nwse", CursorType.DEFAULT)
        val RESIZE_NESW = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR, "resize_nesw", CursorType.DEFAULT)

        /**
         * Compose [PointerIcon] → MC 原版 [CursorType] 映射(I9 指针图标):
         * - Default → 标准箭头;Crosshair → 十字;Text → I 形(文本);Hand → 手型;
         * - 未知/自定义图标实现回退默认箭头(与原占位行为一致,不触发错误)。
         */
        fun toMinecraftCursorType(pointerIcon: PointerIcon): CursorType =
            when ((pointerIcon as? MinecraftPointerIcon)?.kind) {
                MinecraftPointerIconKind.Crosshair  -> CursorTypes.CROSSHAIR
                MinecraftPointerIconKind.Text       -> CursorTypes.IBEAM
                MinecraftPointerIconKind.Hand       -> CursorTypes.POINTING_HAND
                MinecraftPointerIconKind.ResizeNS   -> CursorTypes.RESIZE_NS
                MinecraftPointerIconKind.ResizeNWSE -> RESIZE_NWSE
                MinecraftPointerIconKind.ResizeEW   -> CursorTypes.RESIZE_EW
                MinecraftPointerIconKind.ResizeNESW -> RESIZE_NESW
                MinecraftPointerIconKind.ResizeAll  -> CursorTypes.RESIZE_ALL
                MinecraftPointerIconKind.NotAllowed -> CursorTypes.NOT_ALLOWED
                else                                -> CursorTypes.ARROW
            }
    }

    // 平台适配点(Dialog/Popup 定位):官方桌面实现的 WindowInfo.containerSize 来自
    // 场景实时尺寸;移植默认 WindowInfoImpl 恒为 IntSize.Zero,会导致 Dialog 居中/
    // Popup 裁剪基于 0 尺寸容器,这里改为独立字段 [MinecraftComposeScene.sceneContainerSize],
    // 由 renderFrame 每帧同步窗口像素尺寸。
    // 注意:此处不能读取 scene.scene —— CanvasLayersComposeScene
    // 构造期间(RootNodeOwner.<init> → updatePositionCacheAndDispatch)即会查询
    // containerSize,而 scene 字段此刻尚未赋值完成(属性初始化顺序),会 NPE。
    override val windowInfo: WindowInfo
        get() = object : WindowInfo {
            // 始终视作聚焦(MC 全屏窗口即前台);官方默认亦为 true
            override val isWindowFocused: Boolean
                get() = true

            override val containerSize: IntSize by scene::sceneContainerSize
        }

    // 注入 MC IME 服务(替代 EmptyPlatformTextInputService 默认值)
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val textInputService: MinecraftTextInputService = MinecraftTextInputService()

    // 新版 API 会话(BasicTextField(state)):绑定 request 的 onEditCommand/value,
    // 使 preedit 事件能写入编辑缓冲;会话取消时解绑(IME 停止由 RootNodeOwner 的
    // textInputService.stopInput() 负责)
    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        textInputService.bindRequest(request)
        try {
            awaitCancellation()
        } finally {
            textInputService.unbindRequest(request)
        }
    }

    // I9 指针图标:Compose 光标请求 → MC 原版光标类型(经原版 per-frame 管线生效,
    // 由 RootNodeOwner.PointerIconServiceImpl 在指针 Enter/Exit 时调用)
    override fun setPointerIcon(pointerIcon: PointerIcon) {
        scene.desiredCursorType = toMinecraftCursorType(pointerIcon)
    }

    // 复述系统:捕获语义树所有者(mainOwner 与每个图层 owner),语义变化时
    // 通知 ComposeScreen(焦点变化 → 补触发原版朗读调度)
    override val semanticsOwnerListener: PlatformContext.SemanticsOwnerListener
        get() = object : PlatformContext.SemanticsOwnerListener {
            override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                scene.addSemanticsOwner(semanticsOwner)
            }

            override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                scene.removeSemanticsOwner(semanticsOwner)
            }

            override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
                scene.onSemanticsChanged?.invoke()
            }

            override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
                // 位置/尺寸变化不触发朗读;朗读文本来源是语义属性,非几何
            }
        }
}