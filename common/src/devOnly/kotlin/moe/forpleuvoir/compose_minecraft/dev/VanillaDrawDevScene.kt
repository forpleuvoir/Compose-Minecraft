package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.postVanillaDraw
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.vanillaDraw
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style
import kotlin.math.roundToInt

/**
 * 原版绘制修饰符测试(T.38,独立 ComposeScreen,由总菜单按钮打开)。
 *
 * 验证项:
 * - `Modifier.vanillaDraw`(前渲染,垫底)与 `Modifier.postVanillaDraw`(后渲染,置顶)
 *   两条渲染顺序;
 * - `guiScaleEnabled` 双通道开关:
 *   - `false`(默认)1:1 像素桥 —— 不受原版 guiScale,与 Compose 场景同空间
 *     (像素级对齐,容器内元素与 Compose 尺寸一致);
 *   - `true` 原版通道 —— 走当前帧真
 *     [net.minecraft.client.gui.GuiGraphicsExtractor] / 原版 GuiRenderState,
 *     坐标按 GUI 单位(÷guiScale)换算、随原版投影整体放大(guiScale = 大时
 *     内容同比放大);注意原版 GUI 画在 Compose 之下,该模式下内容不在 Compose 上。
 * - 坐标换算 `guiX` / `guiY` / `guiRect`(滚动时色块跟随;guiScale 模式产出
 *   GUI 单位,原版内容随投影放大);
 * - scissor 像素级裁剪;原版文字(行顶 y)。
 *
 * 操作:点击「切换 guiScale 通道」按钮实时切换全部案例的通道,观察色块/文字
 * 尺寸与位置变化;窗口右上角为 postVanillaDraw 演示(滚动列表时保持置顶)。
 */
@Composable
fun VanillaDrawDevScene() {
    // 根背景必须透明:vanillaDraw 内容先画、位于所有 Compose 内容之下,任何
    // 不透明/高不透明度的 Compose 背景都会把它盖住。此处根 Box 只做布局。
    var guiScaleEnabled by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            BasicText(
                "原版绘制修饰符测试 (vanillaDraw / postVanillaDraw / T.38)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "双通道开关:当前 guiScaleEnabled = $guiScaleEnabled\n" +
                    "false = 1:1 像素桥(与 Compose 同空间,不受原版 guiScale);\n" +
                    "true = 原版通道(真 GuiGraphicsExtractor,坐标 ÷guiScale,随原版投影放大,画在 Compose 之下)。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            DevMenuButton(
                title = "切换 guiScale 通道",
                subtitle = if (!guiScaleEnabled) "当前 1:1 像素桥 → 切到原版通道" else "当前原版通道 → 切到 1:1 像素桥",
                onClick = { guiScaleEnabled = !guiScaleEnabled },
            )

            // 1. 前渲染 vanillaDraw(Compose 半透明灰底透出;随通道开关切换空间)
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Color(0x80606060))
                    .vanillaDraw(guiScaleEnabled = guiScaleEnabled) {
                        val r = guiRect(0f, 0f, size.width, size.height)
                        enableScissor(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                        // 半透明红底 + 顶部高亮条
                        fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0x60804040.toInt())
                        fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), (r.top + 3).roundToInt(), 0xFFDDDDDD.toInt())
                        // 原版文字(行顶 y)
                        text(
                            mc.font,
                            "vanillaDraw pre 通道=${if (guiScaleEnabled) "guiScale" else "1:1"} guiOrigin=(${guiOrigin.x},${guiOrigin.y})",
                            (r.left + 8).roundToInt(),
                            (r.top + 12).roundToInt(),
                            0xFFE0E0E0.toInt(),
                        )
                        disableScissor()
                    }
                    .padding(12.dp)
            ) {
                BasicText(
                    "Compose 内容(半透明灰底,原版红填充/高亮条/文字从下方透出)",
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
            }

            // 2. 三个独立本地位置的 vanilla 色块:坐标随滚动/布局位移
            for (i in 0..2) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .height(48.dp)
                        .vanillaDraw(guiScaleEnabled = guiScaleEnabled) {
                            val r = guiRect(0f, 0f, size.width, size.height)
                            val inset = 6 + i * 8
                            fill(
                                (r.left + inset).roundToInt(),
                                (r.top + inset).roundToInt(),
                                (r.right - inset).roundToInt(),
                                (r.bottom - inset).roundToInt(),
                                when (i) {
                                    0 -> 0x8033E0FF.toInt()
                                    1 -> 0x8077FF33.toInt()
                                    else -> 0x80FF9933.toInt()
                                },
                            )
                            text(
                                mc.font,
                                "block #$i  guiOrigin=(${guiOrigin.x},${guiOrigin.y}) 空间=${if (guiScaleEnabled) "GUI单位" else "像素"}",
                                (r.left + 12).roundToInt(),
                                (r.top + 20).roundToInt(),
                                0xFFFFFFFF.toInt(),
                            )
                        }
                ) {}
            }

            BasicText(
                "postVanillaDraw(后渲染)演示:窗口右上角固定红框,滚动列表时仍置顶。",
                modifier = Modifier.padding(top = 12.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
        }

        // 3. 后渲染 postVanillaDraw:覆盖层 Box 是 Compose 内容;红框/文字经
        //    postVanillaDraw 注入收集器尾部,画在**全部 Compose 内容之上**
        //    (1:1 通道;guiScale 通道走原版 GUI,画在 Compose 之下,见提示文字)。
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
                .width(340.dp)
                .height(96.dp)
                .postVanillaDraw(guiScaleEnabled = guiScaleEnabled) {
                    val r = guiRect(0f, 0f, size.width, size.height)
                    fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0x80FF0000.toInt())
                    fill(r.left.roundToInt(), r.top.roundToInt(), (r.left + 3).roundToInt(), r.bottom.roundToInt(), 0xFFFFFFFF.toInt())
                    fill(r.right.roundToInt() - 3, r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0xFFFFFFFF.toInt())
                    text(
                        mc.font,
                        if (guiScaleEnabled) "postVanillaDraw(guiScale 通道:画在 Compose 之下)" else
                            "postVanillaDraw(1:1):ON TOP of ALL Compose",
                        (r.left + 10).roundToInt(),
                        (r.top + 14).roundToInt(),
                        0xFFFFFFFF.toInt(),
                    )
                    text(
                        mc.font,
                        "guiOrigin=(${guiOrigin.x},${guiOrigin.y}) size=${size.width.toInt()}x${size.height.toInt()}",
                        (r.left + 10).roundToInt(),
                        (r.top + 60).roundToInt(),
                        0xFFFFFFFF.toInt(),
                    )
                }
        ) {
            BasicText(
                "Compose 右上角覆盖层内容(post 红框/文字应画在其上,不随滚动移动)",
                modifier = Modifier.padding(12.dp),
                style = Style.EMPTY.withColor(Color(0xFFFFFFFF)).toTextStyle(),
            )
        }
    }
}