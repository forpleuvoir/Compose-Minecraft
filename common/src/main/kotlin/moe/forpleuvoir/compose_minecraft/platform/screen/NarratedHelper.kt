package moe.forpleuvoir.compose_minecraft.platform.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.collections.plusAssign

object NarratedHelper {

    // 朗读文案翻译键(assets/compose_minecraft/lang/{en_us,zh_cn,...}.json)
    private const val KEY_NAVIGATION_HINT = "compose_minecraft.narration.navigation_hint"
    private const val KEY_TOGGLEABLE_ON = "compose_minecraft.narration.toggleable.on"
    private const val KEY_TOGGLEABLE_OFF = "compose_minecraft.narration.toggleable.off"
    private const val KEY_TOGGLEABLE_INDETERMINATE = "compose_minecraft.narration.toggleable.indeterminate"
    private const val KEY_SELECTED = "compose_minecraft.narration.selected"
    private const val KEY_PROGRESS = "compose_minecraft.narration.progress"
    private const val KEY_HEADING = "compose_minecraft.narration.heading"
    private const val KEY_DISABLED_SUFFIX = "compose_minecraft.narration.disabled_suffix"
    private const val KEY_ROLE_BUTTON = "compose_minecraft.narration.role.button"
    private const val KEY_ROLE_CHECKBOX = "compose_minecraft.narration.role.checkbox"
    private const val KEY_ROLE_SWITCH = "compose_minecraft.narration.role.switch"
    private const val KEY_ROLE_RADIO_BUTTON = "compose_minecraft.narration.role.radio_button"
    private const val KEY_ROLE_TAB = "compose_minecraft.narration.role.tab"
    private const val KEY_ROLE_IMAGE = "compose_minecraft.narration.role.image"
    private const val KEY_ROLE_DROPDOWN_LIST = "compose_minecraft.narration.role.dropdown_list"
    private const val KEY_ROLE_VALUE_PICKER = "compose_minecraft.narration.role.value_picker"
    private const val KEY_ROLE_CAROUSEL = "compose_minecraft.narration.role.carousel"

    /**
     * 复述系统接入(方案 A):重写原版 [Screen.updateNarratedWidget],把 Compose 语义树
     * 映射为原版朗读输出。
     *
     * 朗读目标(与用户确认:焦点优先 + 悬停回退,对齐原版 FOCUSED > HOVERED 优先级):
     * 1. 焦点节点:语义树中 `Focused == true` 的节点(经 focusable/clickable 自动写入,
     *    Focusable.kt 语义应用),无则跳过;
     * 2. 悬停回退:鼠标位置命中的语义节点(按绘制序,Z 序上层优先)。
     *
     * 文本组装(与用户确认):主文本 = text > contentDescription > editableText
     * (BasicText 写 text、Image 写 contentDescription、BasicTextField 写 editableText);
     * 附加 HINT = stateDescription / ToggleableState / Selected / Error /
     * ProgressBarRangeInfo / Heading;类型词 = Role 名(经 MC 语言系统翻译)。
     * 文案经 Component.translatable 输出,跟随当前游戏语言(assets/compose_minecraft/lang)。
     *
     * 触发沿用原版:鼠标移动(750ms)/点击(200ms)经 afterMouseMove/afterMouseAction
     * → handleDelayedNarration 自然触发;Compose 内部焦点变化(键盘导航)经
     * [narrationPending] 在 [extractRenderState] 补 [triggerImmediateNarration]。
     */
    fun updateNarratedWidget(composeScene: MinecraftComposeScene?, mousePosition: Offset, output: NarrationElementOutput) {
        val scene = composeScene ?: return
        val owners = scene.getSemanticsOwners()
        if (owners.isEmpty()) return

        // 收集全部语义节点(合并树,含图层/弹窗)
        val allNodes = owners.flatMap { owner ->
            owner.getAllSemanticsNodes(mergingEnabled = true)
        }

        // 1) 焦点节点优先
        val focusedNode = allNodes.firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Focused) == true && !node.isSemanticsHidden
        }
        // 2) 悬停回退:鼠标所在的最上层可朗读节点
        val hoveredNode =
            focusedNode ?: allNodes.asReversed().firstOrNull { node ->
                !node.isSemanticsHidden && node.hasNarrationContent &&
                        node.boundsInRoot.contains(mousePosition)
            }
        val target = focusedNode ?: hoveredNode
        if (target == null) {
            // 无焦点也无悬停目标:输出导航提示(对齐原版 SCREEN_USAGE_NARRATION,
            // 且朗读器开启时立即有反馈;文案经语言系统翻译)
            output.add(
                NarratedElementType.USAGE,
                Component.translatable(KEY_NAVIGATION_HINT),
            )
            return
        }

        // 组装朗读文本
        val (primary, hints) = narrationText(target)
        if (primary == null && hints.isEmpty()) return
        if (primary != null) {
            output.add(NarratedElementType.TITLE, primary)
        }
        hints.forEach { hint ->
            output.add(NarratedElementType.HINT, hint)
        }
    }

    /** 当前 Compose 焦点语义节点 id(无焦点返回 -1)。语义变化回调据此检测焦点迁移。 */
    fun currentFocusedSemanticsId(composeScene: MinecraftComposeScene?): Int {
        val scene = composeScene ?: return -1
        return scene.getSemanticsOwners()
            .flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true && !it.isSemanticsHidden }
            ?.id ?: -1
    }

    /**
     * 从语义节点组装朗读文本:返回 (主文本, 附加提示列表)。
     * 主文本:Text(多段 join) > ContentDescription > EditableText;仅取纯文本部分。
     * 提示:StateDescription / ToggleableState / Selected / Error / ProgressBarRangeInfo /
     * Heading / Disabled;Role 类型词覆盖为空文本时的主文本(如纯图片按钮)。
     * 语义树携带的业务字符串原样输出;本 helper 生成的类型词/状态词一律走翻译键。
     */
    private fun narrationText(node: SemanticsNode): Pair<Component?, List<Component>> {
        val config = node.config
        val hints = mutableListOf<Component>()

        // 状态类属性(业务字符串原样;helper 生成词走翻译键)
        config.getOrNull(SemanticsProperties.StateDescription)?.let { hints += Component.literal(it) }
        config.getOrNull(SemanticsProperties.ToggleableState)?.let { toggle ->
            hints += when (toggle) {
                ToggleableState.On            -> Component.translatable(KEY_TOGGLEABLE_ON)
                ToggleableState.Off           -> Component.translatable(KEY_TOGGLEABLE_OFF)
                ToggleableState.Indeterminate -> Component.translatable(KEY_TOGGLEABLE_INDETERMINATE)
            }
        }
        config.getOrNull(SemanticsProperties.Selected)?.takeIf { it }?.let { hints += Component.translatable(KEY_SELECTED) }
        config.getOrNull(SemanticsProperties.Error)?.let { hints += Component.literal(it) }
        config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { info ->
            val pct = ((info.current - info.range.start) / (info.range.endInclusive - info.range.start) * 100)
                .toInt()
                .coerceIn(0, 100)
            hints += Component.translatable(KEY_PROGRESS, pct)
        }
        if (config.contains(SemanticsProperties.Heading)) hints += Component.translatable(KEY_HEADING)
        val disabled = config.contains(SemanticsProperties.Disabled)

        // 主文本:Text > ContentDescription > EditableText
        val textParts = config.getOrNull(SemanticsProperties.Text)
        val primaryText = when {
            !textParts.isNullOrEmpty()                                                -> textParts.joinToString(" ") { it.text }
            !config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() ->
                config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString(" ")

            else                                                                      -> config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        }
        val primary = primaryText.takeIf { it.isNotEmpty() }?.let(Component::literal)

        // 类型词:Role 翻译键(有主文本时作类型提示,无主文本时作主文本兜底;禁用时拼后缀)
        val roleComponent = config.getOrNull(SemanticsProperties.Role)?.let(::roleComponent)?.let { role ->
            if (disabled) role.copy().append(Component.translatable(KEY_DISABLED_SUFFIX)) else role
        }
        val effectivePrimary = primary ?: roleComponent
        roleComponent?.takeIf { primary != null }?.let { hints += it }
        return effectivePrimary to hints
    }

    /** Role → 翻译键组件(朗读用途,经 MC 语言系统翻译;未知类型回退枚举名) */
    private fun roleComponent(role: Role): Component =
        when (role) {
            Role.Button       -> Component.translatable(KEY_ROLE_BUTTON)
            Role.Checkbox     -> Component.translatable(KEY_ROLE_CHECKBOX)
            Role.Switch       -> Component.translatable(KEY_ROLE_SWITCH)
            Role.RadioButton  -> Component.translatable(KEY_ROLE_RADIO_BUTTON)
            Role.Tab          -> Component.translatable(KEY_ROLE_TAB)
            Role.Image        -> Component.translatable(KEY_ROLE_IMAGE)
            Role.DropdownList -> Component.translatable(KEY_ROLE_DROPDOWN_LIST)
            Role.ValuePicker  -> Component.translatable(KEY_ROLE_VALUE_PICKER)
            Role.Carousel     -> Component.translatable(KEY_ROLE_CAROUSEL)
            else              -> Component.literal(role.toString())
        }

    /** 语义节点是否对读屏隐藏(合并树配置级别判断) */
    private val SemanticsNode.isSemanticsHidden: Boolean
        get() =
            config.contains(SemanticsProperties.HideFromAccessibility) ||
                    config.contains(SemanticsProperties.IsSensitiveData)

    /** 节点是否有可朗读内容(文本或类型词) */
    private val SemanticsNode.hasNarrationContent: Boolean
        get() {
            val config = config
            return !config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() ||
                    !config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() ||
                    config.getOrNull(SemanticsProperties.EditableText) != null ||
                    config.getOrNull(SemanticsProperties.Role) != null
        }
}