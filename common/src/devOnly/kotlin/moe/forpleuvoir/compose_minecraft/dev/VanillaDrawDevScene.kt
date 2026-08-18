@file:Suppress("DEPRECATION") // T.38 已弃用:本场景为弃用功能的留存测试,有意使用

package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.vanillaDraw
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Style
import kotlin.math.roundToInt

/**
 * 原版绘制修饰符测试(T.38,独立 ComposeScreen,由总菜单按钮打开)。
 *
 * 验证项:
 * - `Modifier.vanillaDraw` 在 draw 阶段拿到当前帧的 1:1 像素绘制桥
 *   [moe.forpleuvoir.compose_minecraft.platform.ui.draw.VanillaGuiGraphics]
 *   (原版 GuiGraphicsExtractor 形态,内容经 Compose 收集器 1:1 像素通道绘制);
 * - 坐标换算:`VanillaDrawScope` 的 guiX/guiY/guiRect 把节点本地坐标换算为
 *   窗口像素(含滚动偏移 —— 下方色块在滚轮滚动时应跟随移动);
 * - 渲染空间:桥内容**不受原版 guiScale 影响**,与 Compose 场景同坐标系
 *   (1:1 像素,任意窗口 guiScale 下像素级对齐);
 * - 渲染层级:桥元素先收集、位于本场景所有 Compose 内容**之下**,
 *   Compose 背景(此处为半透明)透出 vanilla 内容;不透明 Compose 背景会盖住它;
 * - scissor:enableScissor/disableScissor 像素级裁剪。
 */
@Composable
fun VanillaDrawDevScene() {
    // 根背景必须透明:vanilla 内容先画、位于本场景所有 Compose 内容
    // 之下,任何不透明/高不透明度的 Compose 背景都会把它盖住(视觉上
    // "不生效")。此处根 Box 只做布局,不设背景。
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
                "原版绘制修饰符测试 (vanillaDraw / T.38)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "1:1 像素桥(不受原版 guiScale 影响)、位于 Compose 之下;下方案例用\n" +
                        "半透明背景透出原生 fill/text。滚动列表时色块应跟随节点位置移动。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // 1. vanilla fill + text + scissor(顶栏),Compose 半透明灰底透出
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Color(0x80606060))
                    .vanillaDraw {
                        val r = guiRect(0f, 0f, size.width, size.height)
                        graphics.enableScissor(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                        // 半透明红底 + 顶部高亮条
                        graphics.fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0x60804040.toInt())
                        graphics.fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), (r.top + 3).roundToInt(), 0xFFDDDDDD.toInt())
                        // 原版文字(行顶 y)
                        graphics.text(
                            Minecraft.getInstance().font,
                            "vanilla fill + text (under Compose)",
                            (r.left + 8).roundToInt(),
                            (r.top + 12).roundToInt(),
                            0xFFE0E0E0.toInt(),
                        )
                        graphics.disableScissor()
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
//                        .background(Color(0x60FFFFFF))
                        .vanillaDraw {
                            val r = guiRect(0f, 0f, size.width, size.height)
                            val inset = 6 + i * 8
                            graphics.fill(
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
                            graphics.text(
                                Minecraft.getInstance().font,
                                "block #$i  origin=(${guiOrigin.x},${guiOrigin.y}) size=${size.width.toInt()}px",
                                (r.left + 12).roundToInt(),
                                (r.top + 20).roundToInt(),
                                0xFFFFFFFF.toInt(),
                            )
                        }
                ) {}
            }
        }
    }
}