package moe.forpleuvoir.compose_minecraft

import moe.forpleuvoir.compose_minecraft.MinecraftClientSetup
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment

@Mod("compose_minecraft")
class NeoforgeMod {
    init {
        // 仅客户端:经 MinecraftClientSetup.initialize() 加载 devOnly 初始化服务
        // (ServiceLoader)。渲染不经事件/mixin —— Compose 场景经 ComposeScreen 的
        // extractRenderState 每帧驱动(见 AGENTS.md「无帧钩子 mixin、无渲染注入 mixin」）。
        // mixin 配置见 neoforge.mods.toml 的 [[mixins]],只有 StyleAccessor。
        if (FMLEnvironment.getDist().isClient) {
            MinecraftClientSetup.initialize()
        }
    }
}
