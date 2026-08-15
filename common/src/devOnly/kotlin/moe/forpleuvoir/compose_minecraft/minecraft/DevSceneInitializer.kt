package moe.forpleuvoir.compose_minecraft.minecraft

import kotlin.concurrent.thread
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import org.slf4j.LoggerFactory

/**
 * devOnly 开发验证 Scene 初始化(参考 ibuki_gourd 的 devOnly 模式)。
 *
 * 通过 `META-INF/services/moe.forpleuvoir.compose_minecraft.minecraft.MinecraftInitializer`
 * 注册;本类位于 common 的 devOnly source set,只出现在 dev run classpath,
 * 发布 jar 不包含。
 *
 * 打开原版 [ComposeScreen](Screen 桥接)显示开发验证场景,可复用:
 * - mod 初始化时 Minecraft 可能尚未构造(NeoForge @Mod 构造时机较早),
 *   用守护线程轮询等待客户端就绪;
 * - 就绪后切到主线程轮询,游戏加载完成且处于标题界面时打开;
 * - 打开后不退出循环:等待 ComposeScreen 被关闭(退回标题界面)后
 *   重新武装,下次回到标题界面再次打开 —— 退出测试后无需重启即可复用。
 */
class DevSceneInitializer : MinecraftInitializer {

    override fun init() {
        LOGGER.info("[dev] DevSceneInitializer loaded, waiting for Minecraft client...")
        thread(name = "Compose-Minecraft-DevScene", isDaemon = true) {
            while (true) {
                val mc = runCatching { Minecraft.getInstance() }.getOrNull()
                if (mc != null && mc.isGameLoadFinished && mc.gui.screen() is TitleScreen) {
                    LOGGER.info("[dev] Title screen reached, opening ComposeScreen")
                    // 只向主线程提交一次;绝不能在主线程任务里递归 execute——
                    // BlockableEventLoop.runAllTasks 会执行到队列空,任务内再入队会造成死循环卡死游戏。
                    mc.execute {
                        if (mc.gui.screen() is TitleScreen) {
                            mc.gui.setScreen(ComposeScreen { MinecraftDevSceneContent() })
                        }
                    }
                    // 等待 ComposeScreen 关闭(退回标题界面),再回到外层循环重新武装
                    while (true) {
                        val screen = runCatching { mc.gui.screen() }.getOrNull()
                        if (screen == null || screen !is ComposeScreen) break
                        Thread.sleep(100)
                    }
                }
                Thread.sleep(100)
            }
        }
    }

    private companion object {
        private val LOGGER = LoggerFactory.getLogger("ComposeMinecraft.Dev")
    }
}
