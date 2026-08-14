package moe.forpleuvoir.compose_minecraft

import moe.forpleuvoir.compose_minecraft.minecraft.MinecraftClientSetup
import net.fabricmc.api.ClientModInitializer

/**
 * Fabric 客户端入口:注册帧渲染回调 + 加载 devOnly 初始化服务。
 * 帧渲染由通用 Java mixin(GuiRendererMixin,common 共享)注入 GuiRenderer.render() 驱动。
 */
class FabricModClient : ClientModInitializer {

    override fun onInitializeClient() {
        MinecraftClientSetup.initialize()
    }
}
