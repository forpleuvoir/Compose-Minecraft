package moe.forpleuvoir.compose_minecraft.platform.ui.popup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 场景级弹层宿主(参考 ibukigourd `ui.scene.PopupHost` 模式,纯 Basic 无 material3)。
 *
 * 用途:业务弹层(如 tooltip、自定义菜单)不必在调用处组合 [Popup] —— 那样会在业务
 * 父布局中留下 0 尺寸锚点节点([Popup] 内部 EmptyLayout 参与父布局测量,Row 等
 * 分布类布局会受影响)。改为:业务通过 [LocalPopupHost] 把 [PopupEntry] 注册进
 * [PopupHostState],由场景根处的 [PopupHostOverlay] 统一渲染 —— 弹层内容组合在
 * 根场景(独立图层),业务父布局完全无感。
 *
 * 推荐用法([register] —— 自动捕获调用处的 CompositionLocal,自动注销):
 * ```
 * val popupHost = LocalPopupHost.current
 * popupHost?.register(
 *     key = popupKey,
 *     positionProvider = AnchorBoundsPositionProvider({ anchorBounds }, AnchorPosition.Below),
 *     onDismissRequest = { visible = false },
 * ) {
 *     // 这里的主题 / 自定义 Local 自动与调用处一致(register 内部捕获
 *     // currentCompositionLocalContext,PopupHostOverlay 渲染时自动注入)
 *     BasicText("弹层内容", style = ...)
 * }
 * ```
 *
 * 注意:与 [androidx.compose.ui.window.Popup] 的关系 —— 本机制仅是"把 Popup 的
 * 组合位上移到场景根"的托管方案,[PopupHostOverlay] 内部仍使用标准 [Popup] 渲染
 * 每个 entry。锚点定位由 [PopupEntry.positionProvider] 自由决定(如
 * [AnchorBoundsPositionProvider] 捕获调用方组件的 boundsInRoot,零新增布局节点)。
 */
@Stable
class PopupHostState {
    private val _entries = mutableStateMapOf<Any, PopupEntry>()

    val entries: Map<Any, PopupEntry> get() = _entries

    /** 注册一个弹层;相同 [PopupEntry.key] 再次注册会替换旧条目。 */
    fun show(entry: PopupEntry) {
        _entries[entry.key] = entry
    }

    /** 按 [key] 移除弹层。 */
    fun hide(key: Any) {
        _entries.remove(key)
    }
}

/** 一个待渲染的弹层条目。 */
data class PopupEntry(
    /** 唯一标识,由业务方生成(如 `remember { Any() }`)。 */
    val key: Any,
    /** 定位策略:计算弹层在窗口中的位置。 */
    val positionProvider: PopupPositionProvider,
    /** 点击弹层外部时的回调(透传给 [Popup]). */
    val onDismissRequest: (() -> Unit)? = null,
    /** 弹层行为配置(透传给 [Popup]). */
    val properties: PopupProperties = PopupProperties(),
    /**
     * 注册处([register] 调用点)的 CompositionLocal 上下文快照。
     * [PopupHostOverlay] 渲染时经 [CompositionLocalProvider] 应用到弹层内容,
     * 使弹层内容读到的主题 / 自定义 Local 与注册处一致(而非根场景默认值)。
     * 每次 [register] 重组都会刷新(SideEffect 重写条目),跟随调用方环境更新。
     */
    val compositionLocalContext: CompositionLocalContext,
    /** 弹层内容。 */
    val content: @Composable () -> Unit,
)

/** 当前场景的弹层宿主;场景根 [PopupHostOverlay] 所在处提供。 */
val LocalPopupHost = staticCompositionLocalOf<PopupHostState?> { null }

/**
 * 在组合作用域注册一个弹层条目(推荐入口)。
 *
 * 相比手动 `show(PopupEntry(...))`:
 * - **自动捕获**:内部取 [currentCompositionLocalContext],调用处的主题 / 自定义
 *   Local 自动透传到弹层内容,无需手动注入;
 * - **自动注销**:本组合退出时自动 `hide(key)`;
 * - **自动刷新**:每次重组以最新 context 与 content 重写条目。
 *
 * 本调用不产生任何布局节点(业务父布局完全无感),弹层内容实际渲染在场景根的
 * [PopupHostOverlay]。
 */
@Composable
fun PopupHostState.register(
    key: Any,
    positionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    // 捕获调用处的全部 CompositionLocal 值快照(theme / 自定义 Local 等);
    // 与 rememberComposeSceneLayer 内部同款机制(Compose 官方 Wrapper.kt 用法)。
    val compositionLocalContext = currentCompositionLocalContext
    val state = this
    SideEffect {
        state.show(
            PopupEntry(
                key = key,
                positionProvider = positionProvider,
                onDismissRequest = onDismissRequest,
                properties = properties,
                compositionLocalContext = compositionLocalContext,
                content = content,
            )
        )
    }
    DisposableEffect(key) {
        onDispose { state.hide(key) }
    }
}

/**
 * 弹层宿主渲染层:遍历 [PopupHostState.entries] 逐条渲染为标准 [Popup]。
 *
 * 必须组合在场景根(与 [LocalPopupHost] 的 Provider 同一层级),这样每个弹层
 * 的锚点节点(Popup 内部 EmptyLayout)落在根场景而非业务父布局。
 *
 * 每个弹层内容用 [PopupEntry.compositionLocalContext] 包一层
 * [CompositionLocalProvider],使弹层继承**注册处**(业务调用点)的主题与自定义
 * Local,而不是根场景的默认值。
 */
@Composable
fun PopupHostOverlay() {
    val state = LocalPopupHost.current ?: return
    for (entry in state.entries.values) {
        key(entry.key) {
            Popup(
                popupPositionProvider = entry.positionProvider,
                onDismissRequest = entry.onDismissRequest,
                properties = entry.properties,
            ) {
                CompositionLocalProvider(entry.compositionLocalContext) {
                    entry.content()
                }
            }
        }
    }
}