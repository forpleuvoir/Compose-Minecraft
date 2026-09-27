package moe.forpleuvoir.compose_minecraft.platform.screen

/**
 * 屏幕崩溃发生的阶段。
 *
 * 阶段信息用于定位崩溃子系统 —— 四个阶段的宿主代码与失败面完全不同:
 * 帧阶段在 [ComposeScreen.extractRenderState] 内,提交阶段在
 * `ComposeGuiRenderer.render`,输入阶段在 [ComposeScreen] 的输入回调,
 * 协程阶段在 Compose 内容自己的 [kotlinx.coroutines] 作用域里。
 */
enum class ScreenCrashPhase {

    /** 帧提取阶段:组合、布局、绘制命令记录(含父屏提取、复述与光标同步)。 */
    Frame,

    /** GUI 提交阶段:顶点上传与 draw(字体图集、纹理缓存、离屏 PIP)。 */
    Draw,

    /** 输入分发阶段:指针 / 键盘 / 字符 / 输入法组合态。 */
    Input,

    /** 内容协程阶段:LaunchedEffect、动画、rememberCoroutineScope 等未捕获异常。 */
    Effect,
}

/**
 * 一次屏幕崩溃的现场信息(崩溃恢复见 [ComposeScreen])。
 *
 * 屏内抛出的未捕获异常不会向上冒泡给游戏主循环(那会直接崩游戏或让渲染线程静默死亡),
 * 而是被平台收口成一次崩溃:销毁场景、跳过退出动画直接关屏,并把本对象交给崩溃钩子
 * ([ComposeScreen.onCrash] / [ComposeScreenDefaults.onScreenCrash])。
 *
 * @property screen 发生崩溃的屏幕(此时场景已被拆除,屏即将离开屏幕栈)
 * @property phase 崩溃阶段
 * @property cause 未捕获的异常(原样保留,含堆栈)
 */
class ScreenCrash(
    val screen: ComposeScreen,
    val phase: ScreenCrashPhase,
    val cause: Throwable,
)
