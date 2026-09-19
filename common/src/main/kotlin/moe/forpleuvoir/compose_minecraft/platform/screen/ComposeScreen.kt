package moe.forpleuvoir.compose_minecraft.platform.screen
import moe.forpleuvoir.compose_minecraft.mc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorType
import moe.forpleuvoir.compose_minecraft.platform.textinput.ComposeInputBridge.scrollDelta
import moe.forpleuvoir.compose_minecraft.platform.textinput.ComposeInputBridge.toCompose
import moe.forpleuvoir.compose_minecraft.platform.textinput.ComposeInputBridge.toPointerKeyboardModifiers
import moe.forpleuvoir.compose_minecraft.platform.textinput.MinecraftTextInputService
import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.ComposeGuiRenderer
import moe.forpleuvoir.compose_minecraft.platform.render.MinecraftRenderPlugins
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.network.chat.Component
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import net.minecraft.client.input.KeyEvent as MCKeyEvent

/**
 * 原版 [Screen] 桥接(参考 ibuki_gourd 的 ComposeScreen 模式):
 * Compose 场景通过一个原版 Screen 挂入 Minecraft,渲染/层级/输入生命周期
 * 全部走原版屏幕机制,不依赖任何 loader 事件或 mixin 帧钩子。
 *
 * - 渲染:[extractRenderState] 每帧被调用,把场景绘制命令提交进当前帧 GuiRenderState;
 * - 输入:鼠标(点击/释放/移动/拖拽/滚轮)、键盘(按下/释放)与字符(charTyped,含中文
 *   输入法上屏)从原版 Screen 转发到 Compose 场景(阶段 F + 文本输入 I.1);
 *   场景坐标 = 像素(密度默认 1f,1dp == 1 像素;[density] 可配置放大,见构造);
 * - 生命周期:[removed] 时关闭场景(任何被替换/关闭路径都会触发)。
 *
 * IME 支持(实施计划已随实现落地移除):[preeditUpdated] 把系统输入法组合态
 * (preedit)转发到 Compose 编辑缓冲(下划线组合文本);[charTyped] 保持提交文本上屏
 * 并通知 service 计数(组合结束时定位光标)。
 *
 * 父屏幕能力:
 * - [parent]:打开本屏之前的 Screen。**关闭流程走完后**自动 `setScreen(parent)` 返回父屏 ——
 *   与原版各 Screen 的 lastScreen 约定一致(原版 [Screen.onClose] 默认 `setScreen(null)`,
 *   [net.minecraft.client.gui.Gui.setScreen] 无自动记忆,lastScreen 是子类约定);
 * - [renderParentScreen]:本屏打开期间是否把父屏内容渲染在 Compose 之下(半透明背景可透出父屏)。
 *   原版父屏走原版 GuiRenderState(原版 guiRenderer 先画);Compose 父屏由其
 *   [MinecraftComposeScene.renderFrame] 收集后经 [ComposeGuiRenderer.absorbAndClear] 并入
 *   本屏渲染器(顺序在前)。**除本开关外,关闭流程中、以及本屏入场动画进行中也会渲染父屏**
 *   (父子交叉过渡,见 [shouldRenderParent]);运行期由 false 改为 true 时会把父屏"叫回来"
 *   —— 它可能已播过退场动画(进度停在 0),不叫回来画出来是全透明的;
 * - [reopenable]:父屏为 ComposeScreen 时由 [open] 自动标记 —— [removed] 只复位状态、保留场景
 *   (不 close),返回时 [added] 复活(场景与组合状态保留,滚动位置等不丢失);
 *   非可复活屏关闭即销毁场景。
 *
 * 进出场动画([animation] / [ScreenAnimation]):
 * - 平台在内容外层包一层进度动画(位移 + 透明度),入场由动画请求信号驱动,出场作为关闭流程的
 *   参与者;业务若想自定义,用内容级的 [rememberScreenVisibilityState] / [ScreenExitEffect];
 * - **父子交叉**(仅父屏是 ComposeScreen 时):打开本屏时父屏退场([exitParentOnOpen];但
 *   [renderParentScreen] 为真时不退场 —— 那样父屏会被画成全透明),关闭本屏时父屏入场,
 *   两个方向都与本屏的动画同时进行,避免"瞬间消失 / 跳一下";
 * - [animationProgress] 是本屏当前进度(1 = 完全显示),业务可读来做联动。
 *
 * 双击:由 Compose 手势层自检(detectTapGestures onDoubleTap / 文本框双击选词,
 * 首次抬起后 40–300ms 内再次按下;指针事件携带真实墙钟时间戳)。MC 原生
 * `MouseHandler` 的 doubleClick 标志(250ms)仅透传给 vanilla 子控件链(super 调用),
 * 不参与 Compose 手势判定。IME 候选窗由系统输入法负责;Popup/Dialog 已实现(
 * 场景内图层弹层:焦点隔离/scrim 遮罩/Escape 与 outside 点击关闭)。
 *
 * 世界渲染:默认照常渲染(与原版一致);[disableWorldRender] 为 true(或全局
 * [ComposeScreenDefaults.disableWorldRenderByDefault] 打开)时本屏显示期间停画世界,
 * 需要世界当背景时用 [WorldBackdropEffect] / [WorldBackdropWhileAnimating] 按需借用 ——
 * 借用凭据是"屏还开着 / 动画还在跑",不是时长。生效点见 `GameRendererMixin`。
 *
 * 关闭流程:[onClose] / [requestClose] **不再立即切屏**,而是交给
 * [ScreenCloseCoordinator] —— 退出动画播完(或自动判定动画已停)才真正关屏并触发
 * [onClosed];内容侧用 [rememberScreenVisibilityState] / [ScreenExitEffect] 接入,
 * 关闭期间输入被吞掉(防连点)。独立屏幕形态的对话框见 [DialogComposeScreen]。
 */
class ComposeScreen(
    val parent: Screen? = null,
    renderParentScreen: Boolean = false,
    /**
     * 场景密度:默认 1f(1dp == 1 像素,场景尺寸 = 窗口像素);
     * 传 >1f 放大 UI(官方桌面 density 语义,文本字号同步放大)。
     */
    val density: Float = 1f,
    /**
     * 本屏初始是否**禁用**世界渲染(运行时读写请用同名的 [ComposeScreen.disableWorldRender] 属性)。
     *
     * 默认取 [ComposeScreenDefaults.disableWorldRenderByDefault](默认 false = 不禁用 =
     * 照常渲染世界,与原版行为一致)。
     */
    disableWorldRender: Boolean = ComposeScreenDefaults.disableWorldRenderByDefault,
    /**
     * 是否暂停游戏([isPauseScreen] 返回值)。原版 [Screen] 默认为 true;
     * 对话框一类覆盖层通常应继承父屏状态(见 [DialogComposeScreen])。
     */
    val pauseGame: Boolean = true,
    /**
     * 按 Esc 是否关闭本屏([shouldCloseOnEsc] 返回值)。
     *
     * true(默认)= 原版行为:Esc → [onClose] → 关闭流程;false 时 Esc 事件照常
     * 传给 Compose 内容(业务可自行处理)。
     */
    val closeOnEsc: Boolean = true,
    /**
     * 进出场动画。默认取 [ComposeScreenDefaults.animation](平台内置整屏滑入 + 淡入)。
     *
     * 平台在内容外层包一层并由 [rememberScreenVisibilityState] 驱动 —— 因此关闭请求到达时会
     * **先播完出场动画再关屏**;业务若要自定义,传 [ScreenAnimation.Custom] 或在内容里自接
     * 第 2/3 层动画 API。
     */
    val animation: ScreenAnimation = ComposeScreenDefaults.animation,
    /**
     * 打开本屏时是否让父屏(仅 [ComposeScreen] 父屏)播一次**视觉退场**动画。
     *
     * 默认 true:子屏入场与父屏退场同时进行,形成打开方向的交叉过渡(否则父屏瞬间消失、
     * 视觉上会"闪一下")。对话框一类覆盖层应传 false —— 它靠遮罩把父屏留在原地,
     * 不该让父屏退场。
     */
    val exitParentOnOpen: Boolean = true,
    private val content: @Composable () -> Unit,
) : Screen(Component.literal("Compose Screen")) {

    /**
     * 是否**禁用**世界渲染(可在运行时切换,**立即生效**)。
     *
     * true 时本屏显示期间不画世界(省掉世界渲染开销),需要"只在某个动画期间"透出世界时,
     * 用 [WorldBackdropEffect] / [WorldBackdropWhileAnimating] 按需借用;
     * 关闭流程期间平台会自动借回世界,出场动画照常能看到世界。
     *
     * 赋值即同步借用计数(输入事件早于世界渲染,故同一帧生效);每帧另有一次
     * [syncWorldBackdrop] 兜底,处理关闭流程 / 复活等状态迁移。
     */
    var disableWorldRender: Boolean = disableWorldRender
        set(value) {
            if (field == value) return
            field = value
            // 只有本屏正在显示时才即时同步:未打开的屏不该占用世界渲染引用
            // (它成为当前屏时由 added() / 每帧 syncWorldBackdrop() 接管)
            if (mc.gui.screen() === this) syncWorldBackdrop()
        }

    /** 本屏持有的 Compose 场景(可复活:removed 保留场景时非空,否则重建) */
    private var composeScene: MinecraftComposeScene? = null

    /**
     * 可复活标记 —— true 时 [removed] 不销毁场景(关闭返回父屏后状态保留),
     * 由 [open] 在替换父屏前自动设置(父屏为 ComposeScreen 时)。
     */
    var reopenable: Boolean = false

    /** 关闭协调器:退出动画播完(或自动判定动画已停)才真正关屏。 */
    private val closeCoordinator = ScreenCloseCoordinator()

    /**
     * 入场信号:值变化即重放入场动画。
     *
     * 三种来源:首次入场、复活([added])、**子屏开始关闭时提前入场**
     * ([prepareEnterAnimation],与子屏退场形成交叉过渡)。平台屏动画与自接第 2 层
     * 动画的业务都可读它,见 [ScreenAnimationHost]。
     */
    /**
     * 动画请求信号:值变化即按 [animationTarget] 重跑一次进度动画。
     *
     * 三种来源:首次入场(信号仍为 0 时由动画宿主自行入场)、复活([added])、
     * 以及父子屏交叉(子屏打开时父屏退场 [prepareExitAnimation] / 子屏关闭时父屏入场
     * [prepareEnterAnimation])。**入场与退场共用一个信号 + 一个目标值**,避免两个
     * 动画协程抢同一个 Animatable(后到者胜)。
     */
    private var animationSignal by mutableStateOf(0)

    /** 动画目标进度:1 = 完全显示(入场),0 = 完全退场。 */
    private var animationTarget by mutableStateOf(1f)

    /**
     * 打开期间是否把父屏渲染在 Compose 之下(可在运行时切换)。
     *
     * ⚠️ 运行期由 false 改为 true 时会顺带让父屏**重新入场**:父屏在本屏打开那一刻可能已播过
     * 退场动画(进度 0),不叫回来就算画出来也是全透明的。
     */
    var renderParentScreen: Boolean = renderParentScreen
        set(value) {
            if (field == value) return
            field = value
            if (value) parentComposeScreen?.prepareEnterAnimation()
        }

    /** 是否已打开过(用于区分首次打开与"被上层盖住后复活",后者不该再触发父屏退场)。 */
    private var everAdded = false

    /**
     * 本屏平台进出场动画的当前进度:1 = 完全显示,0 = 完全退场。
     *
     * 由平台动画宿主每帧写入。业务可用它做联动(例如让某个元素随屏动画一起移动);
     * 也用于诊断交叉过渡 —— 关闭某个子屏时,读它的父屏的这个值,应能看到父屏进度
     * 在子屏退场期间就由 1 降为 0 再升回 1(说明两边在并行),而不是等子屏关完才开始动。
     */
    var animationProgress: Float by mutableStateOf(1f)
        internal set

    /** 退出参与者作用域,经 CompositionLocal 提供给内容里的子组件。 */
    private val exitScope = object : ScreenExitScope {
        override fun hold(): CloseAnimationHandle = closeCoordinator.registerAnimation()
    }

    /** 屏级世界渲染借用句柄(仅本屏需要世界渲染时非 null,见 [syncWorldBackdrop])。 */
    private var worldBackdropHandle: WorldBackdrop.Handle? = null

    /** 关闭请求时刻(自动判定的上限计时起点);null = 尚未请求关闭。 */
    private var closeRequestedAt: TimeMark? = null

    /** "帧静默"连续帧数:无显式参与者时用于自动判定动画是否已停。 */
    private var quietFrames: Int = 0

    /** 是否已执行最终收尾(关屏 + 清理),防重入。 */
    private var closed: Boolean = false

    // ── 复述系统(Narration)桥接状态 ──────────────────────────
    // 最近一次鼠标位置(像素,取自 mouseHandler 原始坐标):悬停朗读回退用

    private val mousePosition get() = Offset(minecraft.mouseHandler.xpos().toFloat(), minecraft.mouseHandler.ypos().toFloat())

    private var lastFocusedSemanticsId: Int = -1

    // 语义变化暂存:onSemanticsChanged 回调发生在语义快照提交期(合成/测量阶段),
    // 不能直接调 triggerImmediateNarration,改为标记后在下一帧 extractRenderState 处理
    private var narrationPending: Boolean = false

    init {
        ensureScene()
    }

    /**
     * 确保场景存在并注册本屏渲染器。首次构造与 [added](关闭返回父屏后再次打开)
     * 都会调用:场景已存在(可复活保留)则复用,否则重建。
     */
    private fun ensureScene() {
        if (composeScene == null) {
            val scene = MinecraftComposeScene(width = 1, height = 1, density = density)
            // 复述系统:语义树变化(焦点迁移等)时标记,下一帧 extractRenderState 补触发
            // 原版朗读调度(不能在语义快照提交期直接触发,会递归)
            scene.onSemanticsChanged = {
                if (NarratedHelper.currentFocusedSemanticsId(composeScene) != lastFocusedSemanticsId) {
                    narrationPending = true
                }
            }
            scene.setContent {
                CompositionLocalProvider(
                    LocalScreenCloseCoordinator provides closeCoordinator,
                    LocalScreenExitScope provides exitScope,
                ) {
                    // 平台级进出场动画:入场/退场由 animationSignal + animationTarget 驱动,
                    // 真正关闭时由动画作为关闭参与者(播完才关屏)
                    ScreenAnimationHost(
                        animation = animation,
                        animationSignal = animationSignal,
                        animationTarget = animationTarget,
                        onProgress = { animationProgress = it },
                        content = content,
                    )
                }
            }
            composeScene = scene
        }
        // setScreen 先 removed 旧屏再 init 新屏,注册顺序保证 active 指向当前屏
        ComposeGuiRenderer.register(composeScene!!.renderer)
    }

    /**
     * 世界渲染借用与 [disableWorldRender] 保持同步(每帧 + [added] 时各调一次,开销是两次判空)。
     *
     * 借用时机必须在屏**成为当前屏之后**([added])而不是构造 —— 否则"只构造未打开"的屏
     * (例如 [DialogComposeScreen] 工厂返回后并未 setScreen)会永久占住引用计数,世界就再
     * 也不渲染了。运行时切换 [disableWorldRender] 同样靠这里在一帧内生效。
     */
    private fun syncWorldBackdrop() {
        // 关闭流程中也借回世界:出场动画期间应能透出世界。
        // 否则禁用世界渲染的屏关闭时全程无世界,业务还得自己为出场动画再借一次
        val needWorld = !disableWorldRender || closeCoordinator.isClosing
        if (needWorld) {
            if (worldBackdropHandle == null) {
                worldBackdropHandle = WorldBackdrop.acquire()
            }
        } else {
            releaseWorldBackdrop()
        }
    }

    private fun releaseWorldBackdrop() {
        worldBackdropHandle?.close()
        worldBackdropHandle = null
    }

    /** :关闭返回父屏后再次打开时复活场景(重新注册渲染器) */
    override fun added() {
        ensureScene()
        // 复活后复位可复活标记:场景被 open 标记为 reopenable 后不会自动复位,若不在
        // revived 时清掉,最后从本屏退出回非 Compose 屏时 removed 不会销毁场景,导致
        // Recomposer/effect 协程、窗口尺寸监听与整棵节点树挂起不释放。复位后除非再次
        // 作为父屏被 open 标记,否则下次 removed 正常关闭。
        reopenable = false
        // 复活:关闭协调器与关屏计时复位(场景被复用,退出动画系统可再次使用)
        closeCoordinator.reset()
        closed = false
        closeRequestedAt = null
        quietFrames = 0
        // 方向区分:首次打开 → 让父屏退场(打开方向交叉);复活 → 本屏重新入场
        val firstOpen = !everAdded
        everAdded = true
        if (firstOpen) {
            // 本屏要求渲染父屏(常驻露出)时**不能**让父屏退场 —— 否则它虽然被画出来,
            // 进度却停在 0(全透明),等于没渲染
            if (exitParentOnOpen && !renderParentScreen) {
                parentComposeScreen?.prepareExitAnimation()
            }
        } else {
            requestAnimation(1f)
        }
        // 世界渲染借用:屏成为当前屏期间持有(见 syncWorldBackdrop KDoc)
        syncWorldBackdrop()
        super.added()
    }

    // ── 渲染 ──────────────────────────────────────────────────

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // 不调用 super:不渲染原版 widget 列表与默认背景,画面完全由 Compose 场景提供
        // 关闭流程已完成且**本屏是当前屏**时执行收尾(销毁场景 / 切回父屏),本帧不再绘制。
        // 必须限定"是当前屏":作为父屏被下层屏渲染时(交叉过渡)不能走这里
        if (closeCoordinator.isClosed && mc.gui.screen() === this) {
            performClose(graphics, mouseX, mouseY, partialTick)
            return
        }
        if (shouldRenderParent && parent != null) {
            // 渲染父屏(Compose 之下)。原版父屏走原版 GuiRenderState(原版
            // guiRenderer 先画,Compose 后画盖上面);Compose 父屏由其 renderFrame
            // 收集到自身渲染器,再并入本屏渲染器(元素顺序在前 = 画在下面)。
            // 关闭流程中父屏为 ComposeScreen 时也走这里(交叉过渡,见 shouldRenderParent)。
            parent.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, partialTick)
            if (parent is ComposeScreen) {
                parent.composeScene?.renderer?.let { parentRenderer ->
                    composeScene?.renderer?.absorbAndClear(parentRenderer)
                }
            }
        }
        // vanillaDraw 帧态 —— 先注入当前帧原版 GuiGraphicsExtractor
        // (guiScale 通道用),再驱动 renderFrame(前/后渲染回调在 renderFrame 内经
        // 1:1 桥或原版通道执行),完成后清除 —— guiScale 通道回调只在 extract
        // 阶段栈内可见 graphics。1:1 通道不依赖本参数(桥经 GuiCommandSink 注入
        // 本屏收集器);本参数仍供 requestCursor 等原版通道使用,见下方 I9 光标块。
        composeScene?.let { scene ->
            scene.vanillaDrawState.graphics = graphics
            MinecraftRenderPlugins.currentGraphics = graphics
            try {
                scene.renderFrame()
            } finally {
                scene.vanillaDrawState.graphics = null
                MinecraftRenderPlugins.currentGraphics = null
            }
        }
        // IME 候选窗跟随:把本帧最新光标矩形同步给 MC 原生通道 + IMBlocker。
        // 必须逐帧做而不是挂在 preedit 上 —— IMBlocker 会 cancel MC 的 preeditCallback,
        // 那条路在装了 IMBlocker 时整条失效(候选窗会冻在输入法激活那一刻的坐标)。
        composeScene?.imeService?.syncImePosition()
        // 复述系统:语义变化(Compose 内部焦点迁移)补触发原版朗读。extractRenderState
        // 帧内调用是安全的(不在语义快照提交期,不会递归);triggerImmediateNarration
        // 内部只在 Narrator.isActive() 或 DEBUG 时执行,静默无副作用。
        if (narrationPending) {
            narrationPending = false
            lastFocusedSemanticsId = NarratedHelper.currentFocusedSemanticsId(composeScene)
            triggerImmediateNarration(false)
        }
        // I9 指针图标:把 Compose 场景的光标请求并入原版 per-frame 光标管线
        // (extractor 构造时 pendingCursor = CursorType.DEFAULT,这里覆写请求值,
        // 帧末由原版 applyCursor → Window.selectCursor 生效,带去重、尊重
        // 原版「允许光标变化」设置项)。无请求时用 DEFAULT 与原版默认一致。
        graphics.requestCursor(composeScene?.desiredCursorType ?: CursorType.DEFAULT)
        // 世界渲染借用与开关同步(支持运行时切换,下一帧生效)
        syncWorldBackdrop()
        // 关闭动画收尾判定:显式参与者全完成,或(无参与者时)场景连续静默若干帧,
        // 或超过上限 —— 三者任一满足即进入 Closed,下一帧开头执行 performClose
        settleClosing()
    }

    /**
     * 关闭动画收尾判定(每帧渲染后调用一次)。
     *
     * - 有显式参与者([ScreenCloseCoordinator.hasAnimationParticipants]):只做上限兜底,
     *   精确时序由参与者自己决定(句柄 complete 后协调器自然收尾);
     * - 无参与者:按"连续 [QUIET_FRAMES_BEFORE_CLOSE] 帧场景无待处理工作"判定动画已停下
     *   (**近似但零 API 负担**);屏里有无限动画(闪烁光标 / loading)时静默永不成立,
     *   由 [CLOSE_TIMEOUT] 上限兜底。
     */
    private fun settleClosing() {
        if (!closeCoordinator.isClosing) return
        val requestedAt = closeRequestedAt ?: TimeSource.Monotonic.markNow().also { closeRequestedAt = it }
        val timedOut = requestedAt.elapsedNow() >= CLOSE_TIMEOUT

        if (closeCoordinator.hasAnimationParticipants) {
            if (timedOut) closeCoordinator.finishNow()
            return
        }

        quietFrames = if (composeScene?.hasPendingWork() == true) 0 else quietFrames + 1
        if (quietFrames >= QUIET_FRAMES_BEFORE_CLOSE || timedOut) {
            closeCoordinator.finishNow()
        }
    }

    /**
     * 最终收尾:注销渲染器、释放世界渲染借用、销毁场景(可复活屏除外)、切回父屏。
     *
     * 只在 `extractRenderState`(渲染线程即游戏主线程)中调用,故可直接操作原版 Screen。
     *
     * 注意:本方法运行在**本帧 GUI 提取阶段**,而 MC 这一帧提取的是"正在关闭的屏"(本屏) ——
     * 换屏后新屏的渲染器本帧是空的,绘制阶段就会画出一帧"什么都没有"的画面(闪帧)。
     * 因此换屏后立刻给新屏补一次提取,把它这一帧的内容补进命令列表。
     */
    private fun performClose(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        if (closed) return
        closed = true
        composeScene?.let { ComposeGuiRenderer.unregister(it.renderer) }
        releaseWorldBackdrop()
        if (!reopenable) {
            composeScene?.let { runCatching { it.close() } }
            composeScene = null
        }
        if (mc.gui.screen() === this) {
            val next = parent
            if (next != null) {
                mc.gui.setScreen(next)
                if (mc.gui.screen() === next) {
                    // 补提取:否则新屏本帧没有任何命令可画(见方法 KDoc)
                    next.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, partialTick)
                }
            } else {
                super.onClose()
            }
        }
        closeCoordinator.invokeClosedCallback()
    }

    /**
     * 空实现 —— 不渲染原版 Screen 的菜单背景遮罩
     * ([Screen.extractBackground] 默认走 extractBlurredBackground + extractMenuBackground,
     * 即模糊 + 半透明黑色遮罩)。Compose 内容自绘背景(业务背景色/图片),不需要原版遮罩。
     */
    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // 空实现:跳过原版菜单背景遮罩
    }

    // ── 鼠标输入(阶段 F)──────────────────────────────────────

    /**
     * 平台接入点的 MC IME 服务(文本输入桥接)。
     *
     * 平台开放点:platformContext 经 factory 可由下游替换,若自定义实现自持
     * [MinecraftTextInputService](默认即如此)这里 cast 成功;否则返回 null,
     * IME 组合态转发(isComposing/onCharTyped/onPreeditChanged)静默失效。
     */
    private val MinecraftComposeScene.imeService: MinecraftTextInputService?
        get() = platformContext.textInputService as? MinecraftTextInputService

    /**
     * MC 原生 doubleClick 标志(`MouseHandler` 按 250ms/down-to-down/同屏同键计算)不转发给
     * Compose —— 双击由 Compose 手势检测器按事件时间戳自行判定(detectTapGestures /
     * 文本框选词),该标志仅经 [super.mouseClicked] 透传给 vanilla 子控件链。
     */
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // 关闭动画期间吞掉输入:防止连点重复请求关闭 / 打断退出动画
        if (closeCoordinator.isClosing) return true
        // IMBlocker: 文本框本就聚焦时不会再来一次 startInputMethod, 点击是候选被清后唯一的补救时机
        composeScene?.imeService?.refreshImBlockerFocus()
        val consumed = composeScene?.sendPointerEvent(
            eventType = PointerEventType.Press,
            position = mousePosition,
            type = PointerType.Mouse,
            keyboardModifiers = event.buttonInfo.modifiers().toPointerKeyboardModifiers(),
            button = PointerButton(event.buttonInfo.button()),
        )
        return consumed?.anyMovementConsumed == true || super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (closeCoordinator.isClosing) return true
        val consumed = composeScene?.sendPointerEvent(
            eventType = PointerEventType.Release,
            position = mousePosition,
            type = PointerType.Mouse,
            keyboardModifiers = event.buttonInfo.modifiers().toPointerKeyboardModifiers(),
            button = PointerButton(event.buttonInfo.button()),
        )
        return consumed?.anyMovementConsumed == true || super.mouseReleased(event)
    }

    override fun mouseMoved(x: Double, y: Double) {
        composeScene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = mousePosition,
            type = PointerType.Mouse,
        )
        super.mouseMoved(x, y)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val consumed = composeScene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = mousePosition,
            type = PointerType.Mouse,
            keyboardModifiers = event.buttonInfo.modifiers().toPointerKeyboardModifiers(),
        )
        return consumed?.anyMovementConsumed == true || super.mouseDragged(event, dx, dy)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (closeCoordinator.isClosing) return true
        val consumed =
            composeScene?.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = mousePosition,
                type = PointerType.Mouse,
                scrollDelta = scrollDelta(scrollX, scrollY),
            )
        return consumed?.anyMovementConsumed == true || super.mouseScrolled(x, y, scrollX, scrollY)
    }

    // ── 键盘输入(阶段 F)──────────────────────────────────────

    /**
     * 键盘按下转发到 Compose。
     *
     * IME 组合态例外:组合期间 Backspace/Delete/Esc 由系统输入法处理(缩短/清除组合串、
     * 取消组合),这里直接消费、不进入 Compose 文本处理——否则 Compose 的退格会删除组合
     * 文本并清除组合区,导致后续 preedit 更新在错误位置重新插入组合文本(与官方桌面 AWT
     * 组合期间按键不达应用的行为一致)。
     */
    override fun keyPressed(event: MCKeyEvent): Boolean {
        if (closeCoordinator.isClosing) return true
        // IMBlocker: 自动聚焦的文本框若从未被点击, 首次按键(含切换输入法的组合键)也要能补救登记
        composeScene?.imeService?.refreshImBlockerFocus()
        return composeScene?.imeService?.isComposing == true
                && event.key in IME_COMPOSITION_KEYS
                || composeScene?.sendKeyEvent(event.toCompose(KeyEventType.KeyDown)) == true
                || super.keyPressed(event)
    }

    override fun keyReleased(event: MCKeyEvent): Boolean {
        if (closeCoordinator.isClosing) return true
        return composeScene?.imeService?.isComposing == true
                && event.key in IME_COMPOSITION_KEYS
                || composeScene?.sendKeyEvent(event.toCompose(KeyEventType.KeyUp)) == true
                || super.keyReleased(event)
    }

    /**
     * 文本输入(计划 I.1):MC 字符上屏(含中文输入法上屏)→ Compose typed KeyEvent。
     *
     * MC 的 charTyped 在字符上屏时被调用;Compose 侧字符输入走 [ComposeKeyEvent] 的
     * codePoint 字段(KeyEvent.isTypedEvent → TextFieldKeyEventHandler 插入文本),
     * 因此这里把 [CharacterEvent.codepoint] 直接构造成 typed 事件转发,无需 TextInputService。
     * 转发前先经 [LocalCharFilter](默认全放行,由业务方覆盖)。
     *
     * IME 提交语义:系统输入法的提交文本也经此路径上屏(MC GLFW 分支先触发字符回调再
     * 触发 preedit null),组合期间先通知 service 计数(组合结束时用于定位光标)。
     */
    @OptIn(InternalComposeUiApi::class)
    override fun charTyped(event: CharacterEvent): Boolean {
        val scene = composeScene ?: return false
        if (!scene.isCharAccepted(event.codepoint)) {
            return false
        }
        scene.imeService?.onCharTyped(event.codepoint)
        val typedEvent = ComposeKeyEvent(
            key = Key.Unknown,
            type = KeyEventType.KeyDown,
            codePoint = event.codepoint,
        )
        return scene.sendKeyEvent(typedEvent)
    }

    /**
     * IME 组合态(preedit)转发(实施计划 §3.2/§4-3):系统输入法的组合串变化(含提交/取消
     * 后的 null)转发到 [moe.forpleuvoir.compose_minecraft.platform.textinput.MinecraftTextInputService.onPreeditChanged],由 service 转换为
     * Compose EditCommand 写入编辑缓冲(下划线组合文本/组合区清理/光标定位)。
     */
    override fun preeditUpdated(event: PreeditEvent?): Boolean {
        composeScene?.imeService?.onPreeditChanged(event)
        return true
    }

    // ── 生命周期 ─────────────────────────────────────────────

    /**
     * 关闭请求 —— 有父屏则回到父屏(与原版各 Screen 的 lastScreen 约定一致),
     * 无父屏走原版默认(setScreen(null):回游戏 HUD / 主菜单)。
     *
     * 注意:**不立即关屏** —— 先进入 [ScreenCloseCoordinator] 的关闭流程,退出动画播完
     * (或自动判定动画已停)后才由 [performClose] 真正切屏,否则退出动画会被直接掐掉。
     */
    override fun onClose() {
        requestClose()
    }

    /**
     * 请求关闭(幂等);时序见 [ScreenCloseCoordinator]。
     *
     * 父屏是 [ComposeScreen] 时,顺带**提前触发父屏的入场动画**:本屏退场与父屏入场同时进行,
     * 形成交叉过渡 —— 否则父屏要等 [performClose] 之后才复活,视觉上会"跳"一下。
     * 配套:关闭期间本屏会把父屏渲染出来(见 [extractRenderState] 的判定)。
     */
    fun requestClose() {
        if (closeCoordinator.state != ScreenCloseState.Open) return
        parentComposeScreen?.prepareEnterAnimation()
        closeCoordinator.requestClose()
    }

    /** 子屏开始关闭时调用:让本屏重新入场(交叉过渡的另一半)。 */
    internal fun prepareEnterAnimation() {
        requestAnimation(1f)
    }

    /**
     * 子屏**打开**时调用:让本屏播一次视觉退场(进度动画回 0)。
     *
     * 只改画面,不动关闭流程 —— 本屏并未被关闭,只是被上层盖住;它作为父屏被下层渲染
     * 期间(见 [shouldRenderParent])这段退场会与子屏入场同时呈现,形成打开方向的交叉过渡。
     */
    internal fun prepareExitAnimation() {
        requestAnimation(0f)
    }

    /** 请求一次进度动画:目标 1 = 入场,0 = 退场(信号自增,后到者胜)。 */
    private fun requestAnimation(target: Float) {
        animationTarget = target
        animationSignal++
    }

    private val parentComposeScreen: ComposeScreen? get() = parent as? ComposeScreen

    /**
     * 本帧是否渲染父屏。
     *
     * 三种情况:
     * 1. [renderParentScreen] 打开 —— 业务要求常驻渲染父屏;
     * 2. 关闭流程中 —— 子屏退场期间要把父屏露出来(与父屏的提前入场一起构成交叉过渡),
     *    否则退场过程背后是空的,切回父屏时视觉上会"跳";
     * 3. **本屏入场进行中**([animationProgress] < 1)—— 打开方向的交叉:父屏此刻正在退场,
     *    必须把它画出来,否则父屏瞬间消失(闪一下)。
     *    这里刻意看**本屏自己的**进度而不是父屏的:父屏的动画要靠本屏渲染它才会推进,
     *    看父屏的进度会形成"没渲染→没帧→进度不动→不渲染"的死锁。
     */
    private val shouldRenderParent: Boolean
        get() = parent != null &&
                (renderParentScreen || closeCoordinator.isClosing || animationProgress < 1f)

    /** 真正关屏(场景已销毁、父屏已恢复)之后的回调。 */
    fun onClosed(block: () -> Unit) {
        closeCoordinator.onClosed(block)
    }

    /** 是否处于关闭流程中(该阶段输入被吞掉,防连点重复请求关闭)。 */
    val isClosing: Boolean get() = closeCoordinator.isClosing

    /** 是否暂停游戏(构造参数 [pauseGame];原版 [Screen] 默认为 true)。 */
    override fun isPauseScreen(): Boolean = pauseGame

    /** 按 Esc 是否关闭本屏(构造参数 [closeOnEsc];false 时 Esc 交给 Compose 内容处理)。 */
    override fun shouldCloseOnEsc(): Boolean = closeOnEsc

    override fun removed() {
        // 注销本屏渲染器(避免 gui 阶段继续提交已关闭的场景)
        composeScene?.let { ComposeGuiRenderer.unregister(it.renderer) }
        // 世界渲染借用必须在此释放:屏已不在显示,继续占用只会让世界白渲染
        // (作为父屏被渲染时,本屏的 extractRenderState 会重新 sync 借回)
        releaseWorldBackdrop()
        if (reopenable) {
            // 可复活:场景保留,本屏只是被上层盖住,复位为"打开"。
            // 不能标记为已关闭:该标记会让本屏作为父屏被渲染时(extractRenderState 开头)
            // 走收尾分支直接 return,既不渲染也不推进组合。
            // 若关闭已推进到 Closed 却尚未执行 performClose,先补发关屏回调 —— reset 会
            // 复位已关闭状态,不补发则 onClosed 不触发。
            if (closeCoordinator.isClosed) {
                closeCoordinator.invokeClosedCallback()
            }
            closed = false
            closeCoordinator.reset()
        } else {
            // 场景销毁:被替换(而非走完关闭流程)时直接收尾,不留半关状态
            composeScene?.let { runCatching { it.close() } }
            composeScene = null
            closed = true
            closeCoordinator.finishNow()
        }
        super.removed()
    }

    // ── 复述系统(Narration)桥接 ──────────────────────────────

    override fun updateNarratedWidget(output: NarrationElementOutput) {
        NarratedHelper.updateNarratedWidget(composeScene, mousePosition, output)
    }

    companion object {
        /**
         * IME 组合期间由系统输入法处理的键(GLFW 键码):
         * Backspace/Delete 缩短或清除组合串,Esc 取消组合。
         */
        private val IME_COMPOSITION_KEYS = intArrayOf(
            InputConstants.KEY_BACKSPACE,
            InputConstants.KEY_DELETE,
            InputConstants.KEY_ESCAPE,
        )

        /**
         * 关闭流程上限:自关闭请求起超过此时长无论如何收尾。
         *
         * 只兜底"动画永不结束"的病态情况(屏里有无限动画导致场景永静默不下来、
         * 或某个退出句柄漏了 complete) —— 正常时序由动画自身决定,不依赖本值。
         */
        private val CLOSE_TIMEOUT = 5.seconds

        /**
         * 无显式动画参与者时,连续多少帧"场景无待处理工作"即视为退出动画已停下。
         *
         * 取 2 而不是 1:动画可能恰好在下一帧才启动(状态先变、动画后开始),
         * 多等一帧不会影响观感(2 帧 ≈ 33ms),但能避免刚请求关闭就直接掐掉动画。
         */
        private const val QUIET_FRAMES_BEFORE_CLOSE = 2

        /**
         * 关闭当前 Compose 屏幕(父屏幕语义):等价于对当前屏调用 [ComposeScreen.requestClose]
         * —— 进入关闭流程(退出动画播完再切屏),有父屏则回到父屏,无父屏回游戏/主菜单。
         * 当前屏幕不是 [ComposeScreen](如原版屏幕)时返回 false,不做任何事。
         *
         * 用途:内容侧「返回」按钮 —— **关闭自己**而非重新 open 一个父屏实例
         * (重新 open 会丢父屏组合状态,且叠加多层屏幕)。
         */
        fun closeCurrent(): Boolean {
            val current = mc.gui.screen()
            if (current is ComposeScreen) {
                current.requestClose()
                return true
            }
            return false
        }

        /**
         * 打开一个 Compose 屏幕(等价于原版 minecraft.gui.setScreen),参数与构造器一致。
         *
         * [parent] 默认取打开前的当前屏幕 —— 关闭流程走完后自动返回它(「父屏幕能力」);
         * 显式传 null 关闭回主菜单/游戏内。[renderParentScreen] 控制打开期间是否把
         * 父屏内容渲染在 Compose 之下(运行期可切换)。父屏为 [ComposeScreen] 时自动标记
         * [ComposeScreen.reopenable],保证返回后场景/状态复活。
         *
         * [density] 场景密度,默认 1f(1dp == 1 像素);传 >1f 放大 UI。
         *
         * 其余参数:[disableWorldRender] 本屏停画世界(默认取全局值)、[pauseGame] 是否暂停游戏、
         * [closeOnEsc] Esc 是否关屏、[animation] 进出场动画、[exitParentOnOpen] 打开时父屏是否
         * 一并退场(父子交叉;[renderParentScreen] 为真时不退场)。
         *
         * 注意 [content] 是最后一个参数 —— 保持 `ComposeScreen.open { ... }`
         * trailing lambda 调用形式与早期版本兼容。
         *
         * @return 创建的 [ComposeScreen] 实例(可经 [ComposeScreen.renderParentScreen]
         * / [ComposeScreen.disableWorldRender] 等运行时切换)。
         */
        fun open(
            parent: Screen? = null,
            renderParentScreen: Boolean = false,
            density: Float = 1f,
            disableWorldRender: Boolean = ComposeScreenDefaults.disableWorldRenderByDefault,
            pauseGame: Boolean = true,
            closeOnEsc: Boolean = true,
            animation: ScreenAnimation = ComposeScreenDefaults.animation,
            exitParentOnOpen: Boolean = true,
            content: @Composable () -> Unit,
        ): ComposeScreen {
            val resolvedParent = parent ?: mc.gui.screen()
            // 父屏是 ComposeScreen:替换前先标记可复活(removed 保留场景)
            (resolvedParent as? ComposeScreen)?.reopenable = true
            val screen = ComposeScreen(
                parent = resolvedParent,
                renderParentScreen = renderParentScreen,
                density = density,
                disableWorldRender = disableWorldRender,
                pauseGame = pauseGame,
                closeOnEsc = closeOnEsc,
                animation = animation,
                exitParentOnOpen = exitParentOnOpen,
                content = content,
            )
            mc.gui.setScreen(screen)
            return screen
        }
    }
}
