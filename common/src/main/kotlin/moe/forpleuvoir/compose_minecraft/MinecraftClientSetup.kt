package moe.forpleuvoir.compose_minecraft

import java.util.ServiceLoader

/**
 * 客户端初始化入口(各 loader 的客户端初始化时调用)。
 *
 * 通过 ServiceLoader 加载 [MinecraftInitializer] 服务 —— devOnly source set
 * 提供的开发/测试初始化(如开发验证 Scene)只在 dev run classpath 上存在,
 * 发布 jar 不包含,因此服务为空时不做任何事。
 *
 * Compose 场景经原版 [ComposeScreen](Screen 桥接)渲染,不需要任何帧钩子。
 */
object MinecraftClientSetup {

    /** 客户端初始化(主线程,mod 初始化时调用) */
    fun initialize() {
        ServiceLoader.load(MinecraftInitializer::class.java).forEach { it.init() }
    }
}
