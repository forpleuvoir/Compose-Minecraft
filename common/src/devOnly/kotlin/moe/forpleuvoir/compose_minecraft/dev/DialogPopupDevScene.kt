package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BasicTooltipBox
import androidx.compose.foundation.BasicTooltipState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.popup.AnchorBoundsPositionProvider
import moe.forpleuvoir.compose_minecraft.platform.ui.popup.AnchorPosition
import moe.forpleuvoir.compose_minecraft.platform.ui.popup.LocalPopupHost
import moe.forpleuvoir.compose_minecraft.platform.ui.popup.register
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/** 测试专用 CompositionLocal:验证 [register] 自动捕获调用处的 locals 并注入弹层。 */
val LocalTestAccentColor = staticCompositionLocalOf { Color(0xFF4FC3F7) }

/**
 * Dialog / Popup 测试屏幕(window 包场景图层实现,Android 样式)。
 *
 * 验证项:
 * - Popup:alignment 锚点定位、PositionProvider 定位、点击外部关闭、
 *   Esc 关闭(focusable=true, dismissOnBackPress)、focusable=false 非模态穿透;
 * - Dialog:scrim 遮罩(默认黑 60%)、屏幕居中 + 平台默认宽度、
 *   点击遮罩关闭(Release+主键)、Esc 关闭(dismissOnBackPress)、模态焦点圈定;
 * - 联动:BasicTooltipBox(悬停弹提示)内部走同一 Popup 图层。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialogPopupDevScene() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0121212))
    ) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            BasicText(
                "Dialog / Popup 测试",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "Popup = 非模态浮层(锚点定位/点击外部/Esc 关闭);\n" +
                        "Dialog = 模态弹窗(scrim 遮罩 + 居中 + 焦点圈定)。两者均在同一 Compose 场景内。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // ========== 1. Popup:alignment 锚点定位 ==========
            BasicText(
                "1. Popup alignment 锚点定位:相对按钮 TopEnd 放置,offset=(8,8);" +
                        "\n   点击外部或按 Esc 关闭",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            var alignVisible by remember { mutableStateOf(false) }
            Box(Modifier.padding(top = 4.dp)) {
                DevMenuButton(
                    title = if (alignVisible) "点击外部 / Esc 关闭此 Popup" else "打开 alignment Popup",
                    subtitle = "TopEnd + (8, 8) 相对本按钮",
                    onClick = { alignVisible = true },
                )
                if (alignVisible) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(8, 8),
                        onDismissRequest = { alignVisible = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Column(
                            Modifier
                                .background(Color(0xFF263238))
                                .padding(8.dp)
                        ) {
                            BasicText(
                                "alignment=TopEnd",
                                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
                            )
                            BasicText(
                                "offset=(8,8),应出现在按钮右上",
                                style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
                            )
                        }
                    }
                }
            }

            // ========== 2. Popup:PositionProvider + 外部/按键关闭 ==========
            BasicText(
                "2. Popup PositionProvider 定位:弹出位置固定 (200,100);" +
                        "\n   点击外部关闭(dismissOnClickOutside);focusable=true 时 Esc 关闭;" +
                        "\n   弹层内按钮点击不触发 outside dismiss",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            var providerVisible by remember { mutableStateOf(false) }
            DevMenuButton(
                title = if (providerVisible) "Popup 已打开(200,100)" else "打开 PositionProvider Popup",
                subtitle = "锚点左上 + (200, 100)",
                onClick = { providerVisible = true },
            )
            if (providerVisible) {
                Popup(
                    popupPositionProvider = object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset = IntOffset(200, 100)
                    },
                    onDismissRequest = { providerVisible = false },
                    properties = PopupProperties(focusable = true, dismissOnBackPress = true),
                ) {
                    Column(
                        Modifier
                            .background(Color(0xFF263238))
                            .padding(8.dp)
                    ) {
                        BasicText(
                            "PositionProvider Popup",
                            style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
                        )
                        BasicText(
                            "calculatePosition 返回 (200,100)。",
                            style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
                        )
                        // 弹层内部按钮:点击只消费,不应触发 outside dismiss
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .background(Color(0xFF455A64))
                                .clickable { }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            BasicText(
                                "内部按钮(不关闭)",
                                style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                            )
                        }
                    }
                }
            }

            // ========== 3. Popup:focusable=false 非模态穿透 ==========
            BasicText(
                "3. Popup focusable=false:非模态 —— 弹出后主场景仍可点击(下方按钮计数递增)",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            var nonModalVisible by remember { mutableStateOf(false) }
            var mainClicks by remember { mutableStateOf(0) }
            DevMenuButton(
                title = "切换非模态浮层(不阻断)",
                subtitle = if (nonModalVisible) "浮层已显示,主场景仍可操作" else "点击显示浮层",
                onClick = { nonModalVisible = !nonModalVisible },
            )
            DevMenuButton(
                title = "主场景按钮(穿透验证)",
                subtitle = "点击计数: $mainClicks",
                onClick = { mainClicks++ },
            )
            if (nonModalVisible) {
                Popup(
                    alignment = Alignment.BottomCenter,
                    onDismissRequest = null,
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                ) {
                    Box(
                        Modifier
                            .background(Color(0xFF4E342E))
                            .padding(8.dp)
                    ) {
                        BasicText(
                            "非模态浮层(不阻断主场景)",
                            style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                        )
                    }
                }
            }

            // ========== 4. Dialog:模态 + scrim + 居中 + 平台宽度 ==========
            BasicText(
                "4. Dialog 模态弹窗:scrim 遮罩(黑 60%)+ 屏幕居中 + 平台默认宽度;" +
                        "\n   点击遮罩关闭(Release+主键);Esc 关闭;打开时下方按钮被遮罩阻断",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            var dialogVisible by remember { mutableStateOf(false) }
            var dialogDismissCount by remember { mutableStateOf(0) }
            DevMenuButton(
                title = if (dialogVisible) "Dialog 已打开" else "打开模态 Dialog",
                subtitle = "模态:scrim + 居中 + 焦点圈定",
                onClick = { dialogVisible = true },
            )
            if (dialogVisible) {
                Dialog(
                    onDismissRequest = {
                        dialogVisible = false
                        dialogDismissCount++
                    },
                    properties = DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                    ),
                ) {
                    Column(
                        Modifier
                            .widthIn(min = 220.dp)
                            .background(Color(0xFF263238))
                            .padding(16.dp)
                    ) {
                        BasicText(
                            "Dialog 模态弹窗",
                            style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
                        )
                        BasicText(
                            "居中显示,scrim 遮罩背后主场景不可点击;\n" +
                                    "点击遮罩 或 按 Esc 关闭(已关闭 $dialogDismissCount 次)。",
                            style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
                        )
                        Row(Modifier.padding(top = 8.dp)) {
                            Box(
                                Modifier
                                    .background(Color(0xFF455A64))
                                    .clickable {
                                        dialogVisible = false
                                        dialogDismissCount++
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                BasicText(
                                    "确定",
                                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .background(Color(0xFF37474F))
                                    .clickable {
                                        dialogVisible = false
                                        dialogDismissCount++
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                BasicText(
                                    "取消",
                                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                                )
                            }
                        }
                    }
                }
            }
            DevMenuButton(
                title = "Dialog 打开时此按钮应被遮罩阻断",
                subtitle = "模态验证:点不到 = 正确",
                onClick = { mainClicks++ },
            )

            // ========== 5. Tooltip 联动(内部走同一 Popup 图层) ==========
            BasicText(
                "5. 联动:BasicTooltipBox 悬停/点击弹出提示(内部 Popup,non-focusable)",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            val tooltipState = remember { BasicTooltipState(isPersistent = true) }
            val scope = rememberCoroutineScope()
            BasicTooltipBox(
                positionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.bottom + 4)
                },
                tooltip = {
                    Box(
                        Modifier.background(Color(0xFF00695C)).padding(6.dp)
                    ) {
                        BasicText(
                            "tooltip 提示(anchor 下方)",
                            style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                        )
                    }
                },
                state = tooltipState,
                focusable = false,
            ) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .background(Color(0xFF2E7D32))
                        .clickable { scope.launch { tooltipState.show() } }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    BasicText(
                        "悬停/点击显示 tooltip",
                        style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                    )
                }
            }

            // ========== 6. PopupHost:锚点挂业务组件,零布局污染 ==========
            BasicText(
                "6. PopupHost 模式:弹层注册进场景根 host,由根 PopupHostOverlay 渲染;\n" +
                        "   锚点用 onGloballyPositioned 捕获(零新增布局节点),业务父布局完全无感",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            PopupHostTestSection()
        }
    }
}

/**
 * PopupHost 模式验证区:
 * - 锚点组件用 `Modifier.onGloballyPositioned` 捕获 boundsInRoot,经 [register]
 *   注册进 `LocalPopupHost`(场景根,MinecraftComposeScene.setContent 自动挂载);
 * - 弹层内容由根 PopupHostOverlay 渲染为标准 Popup —— EmptyLayout 锚点节点
 *   落在根场景而非本 Row 中,Row 的 SpaceBetween 分布不受影响;
 * - 自动 CompositionLocal 注入:外层 CompositionLocalProvider 提供非默认值的
 *   测试专用 [LocalTestAccentColor],弹层内容里读取到的应是被提供的值
 *   (背景/色块呈注入色);若注入失效则呈默认色 (0xFF4FC3F7)。
 */
@Composable
private fun PopupHostTestSection() {
    val popupHost = LocalPopupHost.current
    val popupKey = remember { Any() }

    // 锚点:业务组件自身捕获(与 Tooltip 思路一致,零新增布局节点)
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    var hostVisible by remember { mutableStateOf(false) }

    // 提供非默认值,验证 register 自动捕获并注入弹层内容
    CompositionLocalProvider(LocalTestAccentColor provides Color(0xFFFF7043)) {
        if (hostVisible && popupHost != null) {
            popupHost.register(
                key = popupKey,
                positionProvider = AnchorBoundsPositionProvider(
                    anchorBounds = { anchorBounds },
                    position = AnchorPosition.Below,
                    spacing = 4,
                ),
                onDismissRequest = { hostVisible = false },
                properties = PopupProperties(focusable = true, dismissOnBackPress = true),
            ) {
                val accent = LocalTestAccentColor.current
                val injected = accent == Color(0xFFFF7043)
                Column(Modifier.background(accent).padding(8.dp)) {
                    BasicText(
                        "PopupHost 弹层(锚点下方,注入色背景)",
                        style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                    )
                    BasicText(
                        if (injected) {
                            "LocalTestAccentColor = FF7043 ✓ 自动注入生效"
                        } else {
                            "LocalTestAccentColor = $accent ✗ 注入未生效(应 FF7043)"
                        },
                        style = Style.EMPTY.withColor(Color.White).withColor(Color(0xFFFFEBEE)).toTextStyle(),
                    )
                }
            }
        }

        // 对照:Row SpaceBetween 分布 —— host 弹层弹出前后按钮位置应完全不变
        // (直接 Popup 的 EmptyLayout 0 尺寸锚点会多占一个子项,SpaceBetween 分布被破坏)
        Row(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .background(Color(0xFF1A237E))
                .padding(6.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .background(Color(0xFF3949AB))
                    .onGloballyPositioned { anchorBounds = it.boundsInRoot() }
                    .clickable { hostVisible = !hostVisible }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                BasicText(
                    if (hostVisible) "点击关闭 host 弹层" else "点击弹出 host 弹层(锚点)",
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .background(Color(0xFF5C6BC0))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                BasicText(
                    "右块(SpaceBetween 验证)",
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
            }
        }
        BasicText(
            "验证点:①弹层出现/消失时上方两色块间距不变;\n" +
                "       ②弹层背景与色值应为 FF7043(Provider 注入值),而非默认 4FC3F7",
            modifier = Modifier.padding(top = 4.dp),
            style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
        )
    }
}