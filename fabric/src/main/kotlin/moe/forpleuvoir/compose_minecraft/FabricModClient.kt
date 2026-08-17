package moe.forpleuvoir.compose_minecraft

import net.fabricmc.api.ClientModInitializer

/**
 * Fabric 客户端入口:经 [MinecraftClientSetup.initialize] 加载 devOnly
 * 初始化服务(ServiceLoader)。渲染不经事件/mixin —— Compose 场景经
 * [moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen] 的
 * `extractRenderState` 每帧驱动(见 AGENTS.md「无帧钩子 mixin、无渲染注入 mixin」）。
 */
class FabricModClient : ClientModInitializer {

    override fun onInitializeClient() {
        MinecraftClientSetup.initialize()
    }
}
