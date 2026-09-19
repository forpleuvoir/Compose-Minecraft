package moe.forpleuvoir.compose_minecraft.dev
import moe.forpleuvoir.compose_minecraft.mc

import com.mojang.blaze3d.platform.InputConstants
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.MinecraftInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import kotlin.concurrent.thread
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

/**
 * devOnly 开发验证 Scene 初始化(参考 ibuki_gourd 的 devOnly 模式)。
 *
 * 通过 `META-INF/services/moe.forpleuvoir.compose_minecraft.MinecraftInitializer`
 * 注册;本类位于 common 的 devOnly source set,只出现在 dev run classpath,
 * 发布 jar 不包含。
 *
 * 打开原版 [ComposeScreen](Screen 桥接)显示开发验证场景:
 * - mod 初始化时 Minecraft 可能尚未构造(NeoForge @Mod 构造时机较早),
 *   用守护线程轮询等待客户端就绪;
 * - 就绪后切到主线程轮询,游戏加载完成且处于标题界面时打开一次;
 * - 打开后退出轮询循环(一次性打开,退出测试再回标题不会自动重开)。
 */
class DevSceneInitializer : MinecraftInitializer {

    override fun init() {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("compose_minecraft", "dev"))

        val key = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.compose_minecraft.test",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_NUMPAD1,
                category
            )
        )
        ClientTickEvents.END_CLIENT_TICK.register {
            while (key.consumeClick()) {
                it.gui.setScreen(ComposeScreen { MinecraftDevSceneContent() })
            }
        }

        LOGGER.info("[dev] DevSceneInitializer loaded, waiting for Minecraft client...")
        thread(name = "Compose-Minecraft-DevScene", isDaemon = true) {
            while (true) {
                val mc = runCatching { mc }.getOrNull()
                if (mc != null && mc.isGameLoadFinished && mc.gui.screen() is TitleScreen) {
                    LOGGER.info("[dev] Title screen reached, opening ComposeScreen")
                    // 只向主线程提交一次;绝不能在主线程任务里递归 execute——
                    // BlockableEventLoop.runAllTasks 会执行到队列空,任务内再入队会造成死循环卡死游戏。
                    mc.execute {
                        if (mc.gui.screen() is TitleScreen) {
                            // 默认打开 dev 主菜单(各专项测试从菜单里进)
                            mc.gui.setScreen(ComposeScreen { MinecraftDevSceneContent() })
                        }
                    }
                    break
                }
                Thread.sleep(100)
            }
        }
    }

    private companion object {
        private val LOGGER = LoggerFactory.getLogger("ComposeMinecraft.Dev")
    }
}
