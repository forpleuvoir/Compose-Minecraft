package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreenDefaults
import moe.forpleuvoir.compose_minecraft.platform.screen.DialogAnimation
import moe.forpleuvoir.compose_minecraft.platform.screen.ScreenAnimation
import moe.forpleuvoir.compose_minecraft.platform.screen.ScreenExitEffect
import moe.forpleuvoir.compose_minecraft.platform.screen.WorldBackdrop
import moe.forpleuvoir.compose_minecraft.platform.screen.WorldBackdropWhileAnimating
import moe.forpleuvoir.compose_minecraft.platform.screen.openDialogComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.screen.rememberScreenVisibilityState
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 屏幕生命周期测试屏:世界渲染开关 + 关闭动画(三层 API) + 对话框。
 *
 * 观察要点:
 * - 世界是否在画,用**半透明背景**判断最简单:背景里的世界在动 = 正在画;
 *   停住不动 = 已停画(跳过了 `GameRenderer.renderLevel`);
 * - 关闭动画是否播完才关屏:看内容是否完整演完退场,再回到 dev 菜单;
 * - 对话框关闭回调:关掉后本屏顶部会显示"上一个对话框关闭回调"的时间戳 ——
 *   本屏作为可复活父屏,场景与状态都被保留,所以这条能作为 T.25 的顺便验证。
 */
@Composable
fun ScreenLifecycleDevScene() {
    // 上个对话框的关闭回调结果(父屏可复活 → 关掉对话框后能在这里看到)
    var lastDialogClosed by remember { mutableStateOf("(尚未关闭过对话框)") }

    Box(Modifier.fillMaxSize().background(Color(0xCC101418))) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            BasicText(
                "屏幕生命周期测试 (世界渲染 / 关闭动画 / 对话框)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "全局 ComposeScreenDefaults.disableWorldRenderByDefault = " +
                        "${ComposeScreenDefaults.disableWorldRenderByDefault}",
                style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
            )
            BasicText(
                "上一个对话框关闭回调: $lastDialogClosed",
                style = Style.EMPTY.withColor(Color(0xFFFFD54F)).toTextStyle(),
            )

            DevSectionTitle("① 世界渲染开关 (WorldBackdrop / disableWorldRender)")
            DevMenuButton(
                title = "1. 运行时切换世界渲染",
                subtitle = "屏内点「切换」→ 开关值翻 true 后,下一帧平台借用计数应变 false 且世界停画",
                onClick = {
                    ComposeScreen.open(parent = mc.gui.screen()) { WorldRenderSwitchProbe() }
                },
            )
            DevMenuButton(
                title = "2. 开屏即停画世界 (disableWorldRender = true)",
                subtitle = "开屏时借用计数就应是 false(世界从第一帧起不画);点「切换」可对比",
                onClick = {
                    ComposeScreen.open(
                        parent = mc.gui.screen(),
                        disableWorldRender = true,
                    ) { WorldRenderSwitchProbe() }
                },
            )
            DevMenuButton(
                title = "3. 入场动画期间借回世界 (WorldBackdropWhileAnimating)",
                subtitle = "屏本身禁用世界渲染,但入场动画 1.2s 内世界在画,动画一 idle 立刻停画",
                onClick = {
                    ComposeScreen.open(
                        parent = mc.gui.screen(),
                        disableWorldRender = true,
                    ) { EnterAnimationBorrowProbe() }
                },
            )

            DevSectionTitle("② 关闭动画:三层 API(点关闭后看退场是否演完)")
            DevMenuButton(
                title = "4. 第 2 层:rememberScreenVisibilityState(推荐)",
                subtitle = "exit = 淡出 + 缩小 600ms;动画播完才回到本菜单",
                onClick = {
                    ComposeScreen.open(parent = mc.gui.screen()) {
                        CloseAnimationLayerTwoProbe()
                    }
                },
            )
            DevMenuButton(
                title = "5. 第 3 层:ScreenExitEffect(挂起式,任意动画)",
                subtitle = "先淡遮罩 400ms、再把面板推出 300ms(共约 700ms)后才关屏",
                onClick = {
                    ComposeScreen.open(parent = mc.gui.screen()) {
                        CloseAnimationLayerThreeProbe()
                    }
                },
            )
            DevMenuButton(
                title = "6. 零动画参与:自动判定(帧静默)",
                subtitle = "屏动画设 None 且内容不接任何动画 API → 平台按「帧静默」自动判定并立即关屏,不应卡住",
                onClick = {
                    ComposeScreen.open(
                        parent = mc.gui.screen(),
                        animation = ScreenAnimation.None,
                    ) { PlainCloseProbe() }
                },
            )

            DevSectionTitle("③ 对话框 (DialogComposeScreen)")
            DevMenuButton(
                title = "7. 默认动画对话框(Esc / 点遮罩关闭)",
                subtitle = "scrim + 面板 fade/scale;Esc 与点遮罩都应「播完出场动画再关」",
                onClick = {
                    openDialogComposeScreen(parent = mc.gui.screen(), onDismiss = {
                        lastDialogClosed = "默认动画对话框(约 ${System.currentTimeMillis()})"
                    }) { DialogProbeBody("默认动画"); }
                },
            )
            DevMenuButton(
                title = "8. Preset:更慢更大",
                subtitle = "duration 600ms / initialScale 0.5 —— 出场过程应明显变慢",
                onClick = {
                    openDialogComposeScreen(
                        parent = mc.gui.screen(),
                        animation = DialogAnimation.Preset(durationMillis = 600, initialScale = 0.5f),
                        onDismiss = { lastDialogClosed = "Preset 对话框" },
                    ) { DialogProbeBody("Preset 600ms / 0.5") }
                },
            )
            DevMenuButton(
                title = "9. Custom:自定义包装器 + ScreenExitEffect",
                subtitle = "包装器里用挂起式退出(遮罩淡出后对话框才关)",
                onClick = {
                    openDialogComposeScreen(
                        parent = mc.gui.screen(),
                        animation = DialogAnimation.Custom { content ->
                            CustomAnimatedDialogWrapper(content)
                        },
                        onDismiss = { lastDialogClosed = "Custom 对话框" },
                    ) { DialogProbeBody("Custom") }
                },
            )
            DevMenuButton(
                title = "10. None:无动画",
                subtitle = "请求关闭即关屏(无淡出过程)",
                onClick = {
                    openDialogComposeScreen(
                        parent = mc.gui.screen(),
                        animation = DialogAnimation.None,
                        onDismiss = { lastDialogClosed = "None 对话框" },
                    ) { DialogProbeBody("None") }
                },
            )
            DevMenuButton(
                title = "11. 禁用 Esc 与遮罩关闭",
                subtitle = "dismissOnBackPress = false、dismissOnClickOutside = false —— 只能点面板内按钮关闭",
                onClick = {
                    openDialogComposeScreen(
                        parent = mc.gui.screen(),
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                        onDismiss = { lastDialogClosed = "仅按钮可关的对话框" },
                    ) { DialogProbeBody("仅按钮可关") }
                },
            )

            DevSectionTitle("④ 普通屏进出场动画 (ScreenAnimation)")
            DevMenuButton(
                title = "12. 默认屏动画 (ScreenAnimation.Default)",
                subtitle = "打开:父屏(本菜单)同时退场;关闭:本屏退场时父屏入场 —— 两个方向都是交叉过渡",
                onClick = {
                    ComposeScreen.open(parent = mc.gui.screen()) { ScreenAnimationProbe("Default") }
                },
            )
            DevMenuButton(
                title = "13. Preset:更慢更大 (600ms / slide 0.25)",
                subtitle = "同一动画,时长与位移约 3 倍 —— 用来确认数值来自全局配置",
                onClick = {
                    ComposeScreen.open(
                        parent = mc.gui.screen(),
                        animation = ScreenAnimation.Preset(durationMillis = 600, slideFraction = 0.25f),
                    ) { ScreenAnimationProbe("Preset 600ms / 0.25") }
                },
            )
            DevMenuButton(
                title = "14. None:无动画对照",
                subtitle = "开屏即显示、请求关闭即关屏(不应有任何过渡过程)",
                onClick = {
                    ComposeScreen.open(
                        parent = mc.gui.screen(),
                        animation = ScreenAnimation.None,
                    ) { ScreenAnimationProbe("None") }
                },
            )
        }
    }
}

@Composable
private fun DevSectionTitle(text: String) {
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true).toTextStyle(),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** 半透明面板:背景能透出世界,便于判断"世界是否在画"。 */
@Composable
private fun ProbePanel(title: String, lines: List<String>, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(340.dp)
            .background(Color(0x99101418))
            .padding(12.dp)
    ) {
        BasicText(title, style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle())
        lines.forEach {
            BasicText(it, style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle())
        }
        content()
    }
}

/** ⑫⑬⑭ 屏动画对照:关闭时应看到反向动画播完之后才回到菜单。 */
@Composable
private fun ScreenAnimationProbe(title: String) {
    // 交叉过渡的前提:父屏必须是 ComposeScreen(否则只会被静态露出,没有入场动画可交叉)
    val parentDesc = when (val p = currentScreen()?.parent) {
        null -> "null(无父屏 → 没有交叉)"
        is ComposeScreen -> "ComposeScreen ✓(关闭时应与父屏入场交叉)"
        else -> "${p.javaClass.simpleName}(原版屏 → 只被静态露出)"
    }
    // 逐帧读进度,用于诊断交叉:关闭时父屏进度应"在子屏退场期间"就由 1 降 0 再升 1
    var selfProgress by remember { mutableStateOf(1f) }
    var parentProgress by remember { mutableStateOf(-1f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            selfProgress = currentScreen()?.animationProgress ?: 1f
            parentProgress = (currentScreen()?.parent as? ComposeScreen)?.animationProgress ?: -1f
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xCC101418)), contentAlignment = Alignment.Center) {
        ProbePanel(
            title = "屏动画:$title",
            lines = listOf(
                "父屏:$parentDesc",
                "打开本屏时:父屏应同时退场(下滑淡出),不应瞬间消失",
                "本屏进度 = %.2f".format(selfProgress),
                "父屏进度 = %.2f".format(parentProgress) +
                        "(打开时应 1→0,关闭时应 0→1,均与本屏对穿)",
                "全局数值:ScreenAnimationDefaults(时长 / 位移 / 缓动 / 是否淡入淡出)",
            ),
        ) {
            DevMenuButton(title = "关闭本屏", subtitle = "Esc 同效;边关边看父屏进度") {
                currentScreen()?.requestClose()
            }
        }
    }
}

/** 当前屏(未打开的屏不在此列) —— 探针一律操作它,避免捕获到过期实例。 */
private fun currentScreen(): ComposeScreen? = mc.gui.screen() as? ComposeScreen

/**
 * ① 世界渲染对照探针。
 *
 * **不捕获屏实例**:每帧从 `mc.gui.screen()` 取当前屏读写 —— 早期版本把
 * `ComposeScreen.open()` 的返回值存进数组再传进来,而 `open` 是 `setScreen` 之后才返回的,
 * 组合期读到的可能是上一个屏,表现为"显示的值与实际生效的屏不一致"。
 *
 * 面板上同时给出平台侧真值:世界渲染是**引用计数**驱动的,`isRequested = true` 就一定
 * 会画世界。于是"翻了开关但世界没停"能立刻分辨是开关没落到本屏、还是借用没释放。
 */
@Composable
private fun WorldRenderSwitchProbe() {
    var disableFlag by remember { mutableStateOf(currentScreen()?.disableWorldRender == true) }
    var requested by remember { mutableStateOf(WorldBackdrop.isRequested) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            disableFlag = currentScreen()?.disableWorldRender == true
            requested = WorldBackdrop.isRequested
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ProbePanel(
            title = "世界渲染对照",
            lines = listOf(
                "本屏 disableWorldRender = $disableFlag",
                "平台借用计数 isRequested = $requested",
                "预期:disableWorldRender=true → 下一帧 isRequested=false → 世界停画",
                "世界是否在画:看面板外的世界有没有在动(云 / 粒子 / 实体)",
            ),
        ) {
            DevMenuButton(
                title = "切换世界渲染(作用于当前屏)",
                subtitle = "翻转本屏 disableWorldRender,下一帧生效",
            ) {
                currentScreen()?.let { it.disableWorldRender = !it.disableWorldRender }
            }
            DevMenuButton(title = "关闭本屏", subtitle = "Esc 同效;关闭时世界会先回来") {
                currentScreen()?.requestClose()
            }
        }
    }
}

/** ③ 入场动画期间借回世界(动画 idle 后自动停画)。 */
@Composable
private fun EnterAnimationBorrowProbe() {
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visible.targetState = true }

    // 非 idle 期间持有世界渲染 —— 入场动画一结束就释放
    WorldBackdropWhileAnimating(visible)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(1200)) + slideInVertically(tween(1200)) { it / 4 },
            exit = fadeOut(tween(200)),
        ) {
            ProbePanel(
                title = "入场借用世界 (1.2s)",
                lines = listOf(
                    "入场期间:世界在画(背景世界是活的)",
                    "动画结束:世界停画(背景世界冻住)",
                    "本屏 disableWorldRender = true",
                ),
            ) {
                DevMenuButton(title = "关闭本屏", subtitle = "Esc 同效") { ComposeScreen.closeCurrent() }
            }
        }
    }
}

/** ④ 第 2 层:可见性状态驱动 exit 动画,动画 idle 后平台才放行关屏。 */
@Composable
private fun CloseAnimationLayerTwoProbe() {
    val visible = rememberScreenVisibilityState()

    Box(Modifier.fillMaxSize().background(Color(0x88101418)), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.9f, animationSpec = tween(120)),
            exit = fadeOut(tween(600)) + scaleOut(targetScale = 0.85f, animationSpec = tween(600)),
        ) {
            ProbePanel(
                title = "第 2 层:rememberScreenVisibilityState",
                lines = listOf(
                    "点关闭 → 淡出 + 缩小 600ms",
                    "动画播完才真正关屏(约 600ms 后回到菜单)",
                ),
            ) {
                DevMenuButton(title = "关闭本屏", subtitle = "Esc 同效") { ComposeScreen.closeCurrent() }
            }
        }
    }
}

/** ⑤ 第 3 层:挂起式退出逻辑(任意动画序列都能接)。 */
@Composable
private fun CloseAnimationLayerThreeProbe() {
    val scrimAlpha = remember { Animatable(0.35f) }
    val panelOffset = remember { Animatable(0f) }

    ScreenExitEffect {
        // 先淡遮罩,再把面板推出屏幕 —— 平台等这个块结束才关屏
        scrimAlpha.animateTo(0f, tween(400))
        panelOffset.animateTo(-260f, tween(300))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha.value)),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.offset(y = panelOffset.value.dp)) {
            ProbePanel(
                title = "第 3 层:ScreenExitEffect",
                lines = listOf(
                    "关闭 → 遮罩淡出 400ms + 面板上移 300ms",
                    "块结束(约 700ms)才关屏",
                ),
            ) {
                DevMenuButton(title = "关闭本屏", subtitle = "Esc 同效") { ComposeScreen.closeCurrent() }
            }
        }
    }
}

/** ⑥ 零 API:不接任何动画,验证平台的"帧静默"自动判定不会卡住。 */
@Composable
private fun PlainCloseProbe() {
    Box(Modifier.fillMaxSize().background(Color(0xCC101418)), contentAlignment = Alignment.Center) {
        ProbePanel(
            title = "零 API 关闭",
            lines = listOf(
                "本屏没有注册任何退出动画",
                "关闭应由平台自动判定并立即生效(约 2 帧)",
            ),
        ) {
            DevMenuButton(title = "关闭本屏", subtitle = "Esc 同效") { ComposeScreen.closeCurrent() }
        }
    }
}

/** ⑨ 自定义包装器:自己接挂起式退出。 */
@Composable
private fun CustomAnimatedDialogWrapper(content: @Composable () -> Unit) {
    val scrimAlpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.7f) }

    LaunchedEffect(Unit) {
        scrimAlpha.animateTo(0.35f, tween(200))
        scale.animateTo(1f, tween(200))
    }
    ScreenExitEffect {
        scale.animateTo(0.7f, tween(250))
        scrimAlpha.animateTo(0f, tween(250))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha.value)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset(y = ((scale.value - 1f) * 40f).dp)) {
            content()
        }
    }
}

/** 对话框面板内容:自带一个"关闭"按钮,便于验证仅按钮可关的场景。 */
@Composable
private fun DialogProbeBody(title: String) {
    Column(
        Modifier
            .width(300.dp)
            .background(Color(0xFF1B2430))
            .padding(16.dp)
    ) {
        BasicText(title, style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle())
        BasicText(
            "关闭方式:Esc / 点遮罩 / 下面这个按钮",
            style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
        )
        DevMenuButton(
            title = "关闭对话框",
            subtitle = "走 requestClose → 播完出场动画 → 关屏 → onDismiss",
        ) {
            ComposeScreen.closeCurrent()
        }
    }
}
