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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.vanillaDraw
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style
import kotlin.math.roundToInt

/**
 * vanillaDraw 遮挡矩阵 / 弹层遮挡测试。
 *
 * 本场景验证「vanillaDraw 内容(1:1 像素桥)与 Compose 元素互相遮盖」的层级关系:
 *
 * ## 预期层级(自底向上,1:1 通道默认)
 * ```text
 * ┌ 顶层 ┐
 * │ postVanillaDraw 元素(后渲染,注入列表尾部,盖全部 Compose)  │
 * │ Dialog / Popup 的 Compose 内容(scrim 在其上层的栈顶)  │
 * │ 主场景 Compose 内容(不透明盖住其下,半透明透出)          │
 * │ 全部 vanillaDraw 元素(前渲染,注入列表头部;注册序垫全局最底)┤
 * └ 底层 ┘
 * ```
 * 所有 vanillaDraw(前渲染)回调都注入 ComposeGuiRenderer.items 列表**头部**,因此:
 * - 任何不透明 Compose 元素都会盖住它;半透明则透出;
 * - **弹层(Popup/Dialog)内的 vanillaDraw 同样垫全局最底** —— 若主场景有不透明
 *   内容,弹层的 vanilla 会被主场景内容盖住(全局底,非"弹层底"),这是当前
 *   单收集器架构的固有语义;弹层内 vanilla 坐标以弹层原点为基准(未叠加弹层
 *   窗口位移)。
 *
 * 本场景固定使用 **1:1 通道**(`guiScaleEnabled = false`,不受原版 guiScale)。
 * `guiScaleEnabled = true` 的原版通道走当前帧真
 * [net.minecraft.client.gui.GuiGraphicsExtractor] / 原版 GuiRenderState ——
 * 原版 GUI 画在 Compose 之下,不受本矩阵约束(相关演示见 VanillaDrawDevScene)。
 *
 * 验证区:
 * - A1 半透明 Compose 盖 vanilla(应透出) / A2 不透明 Compose 盖 vanilla(应盖死);
 * - A3 无 Compose 背景(vanilla 全可见) / A4 双 vanilla 节点重叠(后注册在上);
 * - A5 vanilla 文字与 Compose 文字同位置叠加;
 * - B Popup 内 vanillaDraw:半透/不透明两层,观察是否被主场景内容盖住;
 * - C Dialog 内 vanillaDraw:scrim(黑色 60%)盖主场景(含其 vanilla),弹层内透出;
 * - D 滚动列表:色块随滚动跟随节点位置(坐标换算含滚动偏移)。
 */
@Composable
fun VanillaDrawLayerDevScene() {
    // 根必须透明:vanilla 全垫底,任何不透明背景都会盖住它
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
                "vanillaDraw 遮挡矩阵 / 弹层测试 (T.38)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "当前窗口 guiScale=${mc.window.guiScale}(vanilla 1:1 桥不受其影响)。\n" +
                    "层级预期(1:1 通道):[vanillaDraw 垫底] → [主场景 Compose] → [Popup/Dialog] → [postVanillaDraw 置顶]。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // ========== A1:半透明 Compose 盖 vanilla → 应透出 ==========
            BasicText(
                "A1 半透明 Compose 盖 vanilla:红块应从灰底透出",
                modifier = Modifier.padding(top = 12.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0x8055AADD))
                    .vanillaDraw {
                        val r = guiRect(0f, 0f, size.width, size.height)
                        fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0xFFFF3300.toInt())
                        text(
                            mc.font,
                            "vanilla RED under semi-transparent compose",
                            (r.left + 6).roundToInt(),
                            (r.top + 20).roundToInt(),
                            0xFFFFFFFF.toInt(),
                        )
                    }
            ) {}

            // ========== A2:不透明 Compose 盖 vanilla → 应完全盖住 ==========
            BasicText(
                "A2 不透明 Compose 盖 vanilla:绿块应被完全盖住(只看得到深灰底)",
                modifier = Modifier.padding(top = 12.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF263238))
                    .vanillaDraw {
                        val r = guiRect(0f, 0f, size.width, size.height)
                        fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0xFF00FF66.toInt())
                        text(
                            mc.font,
                            "vanilla GREEN hidden by opaque compose",
                            (r.left + 6).roundToInt(),
                            (r.top + 20).roundToInt(),
                            0xFFFFFFFF.toInt(),
                        )
                    }
            ) {}

            // ========== A3:无 Compose 背景 → vanilla 全可见 ==========
            BasicText(
                "A3 无 Compose 背景:蓝块应完整可见(vanilla 垫底直接露出来)",
                modifier = Modifier.padding(top = 12.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .vanillaDraw {
                        val r = guiRect(0f, 0f, size.width, size.height)
                        fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0xFF3399FF.toInt())
                        text(
                            mc.font,
                            "vanilla BLUE fully visible (no compose bg)",
                            (r.left + 6).roundToInt(),
                            (r.top + 20).roundToInt(),
                            0xFFFFFFFF.toInt(),
                        )
                    }
            ) {}

            // ========== A4:双 vanilla 节点重叠 → 后注册(下方节点)在上 ==========
            BasicText(
                "A4 双 vanilla 重叠:半透明红(先)+ 半透明绿(后)同位置 → 绿应叠在红上",
                modifier = Modifier.padding(top = 12.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .vanillaDraw {
                        val r = guiRect(0f, 0f, size.width, size.height)
                        fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0x80FF3300.toInt())
                        text(mc.font, "red first", (r.left + 4).roundToInt(), (r.top + 4).roundToInt(), 0xFFFFFFFF.toInt())
                    }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 40.dp, end = 0.dp)
                        .vanillaDraw {
                            val r = guiRect(0f, 0f, size.width, size.height)
                            fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0x8000FF66.toInt())
                            text(mc.font, "green later", (r.left + 4).roundToInt(), (r.top + 30).roundToInt(), 0xFFFFFFFF.toInt())
                        }
                ) {}
            }

            // ========== A5:vanilla 文字 vs Compose 文字 ==========
            BasicText(
                "A5 同位置叠加文字:Compose(半透明白)在上、vanilla(纯白)在下 → 双层可见",
                modifier = Modifier.padding(top = 12.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .vanillaDraw {
                        val r = guiRect(0f, 0f, size.width, size.height)
                        text(mc.font, "VANILLA TEXT (bottom)", (r.left + 4).roundToInt(), (r.top + 10).roundToInt(), 0xFFFFFFFF.toInt())
                    }
            ) {
                BasicText(
                    "Compose text (top, semi-transparent)",
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                    style = Style.EMPTY.withColor(Color(0x80FFFFFF)).toTextStyle(),
                )
            }

            // ========== B:Popup 内 vanillaDraw ==========
            BasicText(
                "B Popup 内 vanillaDraw(针对弹层图层层级验证)",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "观察点:弹层 vanilla 垫**全局最底** —— 主场景及本弹层的不透明 Compose 内容\n" +
                    "都会盖住它;半透明处应透出紫色 fill。弹层内坐标以弹层原点为基准。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            var popupVisible by remember { mutableStateOf(false) }
            DevMenuButton(
                title = if (popupVisible) "Popup 已打开(点外部/Esc 关闭)" else "打开 Popup(vanillaDraw 在弹层内)",
                subtitle = "半透明白底 Popup,内含 vanilla 紫块 + 不透明白块",
                onClick = { popupVisible = true },
            )
            if (popupVisible) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(180, 60),
                    onDismissRequest = { popupVisible = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(Modifier.background(Color(0x80263836)).padding(12.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .vanillaDraw {
                                    val r = guiRect(0f, 0f, size.width, size.height)
                                    fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0xFFAA00FF.toInt())
                                    text(mc.font, "popup vanilla purple", (r.left + 4).roundToInt(), (r.top + 20).roundToInt(), 0xFFFFFFFF.toInt())
                                }
                        ) {}
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                                .background(Color(0xFFFFFFFF))
                                .vanillaDraw {
                                    val r = guiRect(0f, 0f, size.width, size.height)
                                    fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0xFF00AAFF.toInt())
                                }
                        ) {}
                        BasicText(
                            "Popup 内容(半透明底)",
                            style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                        )
                    }
                }
            }

            // ========== C:Dialog 内 vanillaDraw ==========
            BasicText(
                "C Dialog 内 vanillaDraw(scrim 遮罩 vs vanilla)",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "观察点:scrim(黑 60%)是 Compose 元素、位于栈顶图层 —— 打开 Dialog 后\n" +
                    "主场景的 vanilla(A1/A3 等)应被 scrim 整体压暗;Dialog 内黄块(半透明底)透出。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            var dialogVisible by remember { mutableStateOf(false) }
            DevMenuButton(
                title = if (dialogVisible) "Dialog 已打开(点遮罩/Esc 关闭)" else "打开 Dialog(vanillaDraw 在弹窗内)",
                subtitle = "模态弹窗:scrim 盖主场景",
                onClick = { dialogVisible = true },
            )
            if (dialogVisible) {
                Dialog(
                    onDismissRequest = { dialogVisible = false },
                    properties = DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                    ),
                ) {
                    Column(Modifier.background(Color(0x80263836)).padding(16.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .vanillaDraw {
                                    val r = guiRect(0f, 0f, size.width, size.height)
                                    fill(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), 0x80FFFF00.toInt())
                                    text(mc.font, "dialog vanilla yellow", (r.left + 4).roundToInt(), (r.top + 16).roundToInt(), 0xFF000000.toInt())
                                }
                        ) {}
                        BasicText(
                            "Dialog 内容(半透明底)。scrim 盖住主场景(含其 vanilla)。",
                            style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                        )
                        DevMenuButton(
                            title = "关闭 Dialog",
                            subtitle = "dismiss",
                            onClick = { dialogVisible = false },
                        )
                    }
                }
            }

            // ========== D:滚动跟随 ==========
            BasicText(
                "D 滚动跟随:下方色块随滚动移动(vanilla 坐标含滚动偏移)",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            for (i in 0..8) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(30.dp)
                        .vanillaDraw {
                            val r = guiRect(0f, 0f, size.width, size.height)
                            fill(r.left.roundToInt(), r.top.roundToInt(), (r.left + 60).roundToInt(), r.bottom.roundToInt(), if (i % 2 == 0) 0x8033E0FF.toInt() else 0x80FF9933.toInt())
                            text(
                                mc.font,
                                "row $i origin=(${guiOrigin.x},${guiOrigin.y})",
                                (r.left + 68).roundToInt(),
                                (r.top + 10).roundToInt(),
                                0xFFFFFFFF.toInt(),
                            )
                        }
                ) {}
            }
        }
    }
}