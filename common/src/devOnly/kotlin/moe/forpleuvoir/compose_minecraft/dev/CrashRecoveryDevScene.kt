package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreenDefaults
import moe.forpleuvoir.compose_minecraft.platform.screen.ScreenCrash
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 崩溃验证记录:崩溃钩子写入,本菜单显示。
 *
 * 三个观察点:
 * - 钩子是否被调用(回调次数)—— 未接入崩溃恢复时异常根本到不了这里;
 * - 现场是否完整(阶段 + 异常类型 + message)—— 用于确认收口发生在预期阶段;
 * - 崩溃屏的 `onClosed` 是否照常触发(崩溃关屏走与正常关闭同一出口)。
 */
object CrashProbeState {

    /** 最近一次崩溃的摘要(来源 · 阶段 · 异常)。 */
    var lastCrash: String? by mutableStateOf(null)

    /** 崩溃钩子累计调用次数。 */
    var hookCalls: Int by mutableStateOf(0)

    /** 崩溃屏的 onClosed 回调是否触发过。 */
    var lastClosedInvoked: Boolean by mutableStateOf(false)

    fun record(source: String, crash: ScreenCrash) {
        hookCalls++
        lastCrash = "$source · ${crash.phase} · ${crash.cause::class.java.simpleName}: ${crash.cause.message}"
    }
}

/** 崩溃触发方式。 */
private enum class CrashTrigger(val title: String, val hint: String) {

    /** 组合体直接抛:验证帧提取守卫(重组抛异常不再冒泡给游戏主循环)。 */
    Composition(
        "组合期抛异常",
        "点「触发崩溃」→ 状态变化触发重组,组合体抛 IllegalStateException",
    ),

    /** LaunchedEffect 内抛:验证场景协程异常处理器(此前这条会静默杀死渲染线程 → 画面卡死)。 */
    Effect(
        "内容协程抛异常",
        "开屏约 0.5s 后 LaunchedEffect 内抛异常",
    ),

    /** 点击回调抛:回调运行在指针输入协程里,同样经协程异常处理器收口。 */
    Click(
        "点击回调抛异常",
        "点「触发崩溃」→ onClick 在指针输入协程里抛异常",
    ),
}

/**
 * 崩溃恢复测试屏:列出各种"屏内抛异常"的触发方式,观察平台是否自动关屏并回调崩溃钩子。
 *
 * 观察要点:
 * - 触发后本菜单应立即回来(拆场景 + 跳过退出动画),游戏不崩、不卡死;
 * - 菜单顶部出现崩溃记录(来源 · 阶段 · 异常),且回调次数 +1;
 * - 「无逐屏钩子」一项走全局 [ComposeScreenDefaults.onScreenCrash],来源应显示"全局钩子"。
 */
@Composable
fun CrashRecoveryDevScene() {
    Box(Modifier.fillMaxSize().background(Color(0xE0101418))) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            BasicText(
                "崩溃恢复测试 (崩溃 → 自动关屏 + 钩子)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "最近一次崩溃:${CrashProbeState.lastCrash ?: "(尚未崩溃)"}",
                style = Style.EMPTY.withColor(Color(0xFFFFD54F)).toTextStyle(),
            )
            BasicText(
                "钩子调用次数 = ${CrashProbeState.hookCalls}," +
                        "崩溃屏 onClosed 已触发 = ${CrashProbeState.lastClosedInvoked}",
                style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
            )
            BasicText(
                "预期:任一触发后本菜单立即回来(不等退出动画),世界与输入照常;钩子在关屏前回调",
                style = Style.EMPTY.withColor(Color(0xFF80CBC4)).toTextStyle(),
            )

            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = {
                    if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() }
                },
            )

            DevMenuButton(
                title = "1. 组合期抛异常(逐屏钩子)",
                subtitle = "点「触发崩溃」→ 组合体重组时抛异常,应自动关屏并回调 onCrash",
                onClick = { openCrashProbe(CrashTrigger.Composition) },
            )
            DevMenuButton(
                title = "2. 内容协程抛异常(逐屏钩子)",
                subtitle = "开屏 0.5s 后 LaunchedEffect 内抛异常 —— 未接入异常处理器时这条会静默卡死画面",
                onClick = { openCrashProbe(CrashTrigger.Effect) },
            )
            DevMenuButton(
                title = "3. 点击回调抛异常(逐屏钩子)",
                subtitle = "点「触发崩溃」→ onClick 在其输入协程里抛异常,同样应收口为崩溃",
                onClick = { openCrashProbe(CrashTrigger.Click) },
            )
            DevMenuButton(
                title = "4. 无逐屏钩子(走全局钩子)",
                subtitle = "只设 ComposeScreenDefaults.onScreenCrash → 记录来源应显示「全局钩子」",
                onClick = { openCrashProbe(CrashTrigger.Composition, useGlobalHook = true) },
            )
        }
    }
}

/**
 * 打开一个"注定崩溃"的探针屏。
 *
 * 父屏是当前菜单:崩溃关屏后自动回到这里(可复活父屏保留组合状态),于是刚才的记录
 * 能直接显示在菜单顶部 —— 若平台没有收口,本菜单会因为渲染线程死亡而永远卡在上一帧。
 */
private fun openCrashProbe(trigger: CrashTrigger, useGlobalHook: Boolean = false) {
    if (useGlobalHook) {
        ComposeScreenDefaults.onScreenCrash = { crash -> CrashProbeState.record("全局钩子", crash) }
    }
    val screen = ComposeScreen.open(
        parent = mc.gui.screen(),
        onCrash = if (useGlobalHook) {
            null
        } else {
            { crash: ScreenCrash -> CrashProbeState.record("逐屏钩子", crash) }
        },
        content = { CrashProbeBody(trigger) },
    )
    // 崩溃关屏与正常关闭同一出口 → 这个回调应照常触发
    screen.onClosed { CrashProbeState.lastClosedInvoked = true }
}

/** 探针屏内容:按 [trigger] 在对应阶段制造一次未捕获异常。 */
@Composable
private fun CrashProbeBody(trigger: CrashTrigger) {
    var boom by remember { mutableStateOf(false) }

    // ① 组合期崩溃:重组时直接抛
    if (boom && trigger == CrashTrigger.Composition) {
        error("CrashRecoveryDevScene: composition failure")
    }
    // ② 内容协程崩溃:开屏即挂,延时后抛(模拟动画/加载过程中的异步失败)
    LaunchedEffect(Unit) {
        if (trigger == CrashTrigger.Effect) {
            delay(500.milliseconds)
            error("CrashRecoveryDevScene: effect failure")
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xCC101418)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(420.dp)
                .background(Color(0xFF1B2430))
                .padding(16.dp)
        ) {
            BasicText(
                "崩溃探针:${trigger.title}",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                trigger.hint,
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            BasicText(
                "触发后本屏应立刻消失并回到菜单,顶部出现崩溃记录",
                style = Style.EMPTY.withColor(Color(0xFFFFD54F)).toTextStyle(),
            )
            DevMenuButton(
                title = "触发崩溃",
                subtitle = "点下去不应崩游戏,也不应卡住画面",
            ) {
                when (trigger) {
                    CrashTrigger.Composition -> boom = true
                    CrashTrigger.Click -> error("CrashRecoveryDevScene: click failure")
                    // 异步触发:异常已在上面的 LaunchedEffect 里抛出
                    CrashTrigger.Effect -> Unit
                }
            }
            DevMenuButton(
                title = "关闭本屏(对照)",
                subtitle = "正常关闭路径:应播放退出动画后再回到菜单",
                onClick = { ComposeScreen.closeCurrent() },
            )
        }
    }
}
