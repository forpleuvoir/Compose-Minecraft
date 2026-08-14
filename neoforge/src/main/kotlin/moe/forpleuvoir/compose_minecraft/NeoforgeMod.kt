package moe.forpleuvoir.compose_minecraft

import moe.forpleuvoir.compose_minecraft.minecraft.MinecraftClientSetup
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment

@Mod("compose_minecraft")
class NeoforgeMod {
    init {
        // 仅客户端:注册帧渲染回调 + 加载 devOnly 初始化服务。
        // 帧渲染不依赖事件,统一由通用 Java mixin(GuiRendererMixin,common 共享)
        // 注入 GuiRenderer.render() 驱动;mixin 配置见 neoforge.mods.toml 的 [[mixins]]。
        if (FMLEnvironment.getDist().isClient) {
            MinecraftClientSetup.initialize()
        }
    }
}
