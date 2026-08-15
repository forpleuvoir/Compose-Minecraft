package moe.forpleuvoir.compose_minecraft

/**
 * 平台初始化服务(参考 ibuki_gourd 的 devOnly 模式)。
 *
 * 通过 `META-INF/services/moe.forpleuvoir.compose_minecraft.MinecraftInitializer`
 * 注册实现;devOnly source set 中的开发/测试初始化只在 dev run classpath 上生效,
 * 发布 jar 不包含,服务列表为空时 [MinecraftClientSetup.initialize] 不做任何事。
 */
fun interface MinecraftInitializer {

    /** 客户端初始化(主线程,mod 初始化时调用) */
    fun init()
}
