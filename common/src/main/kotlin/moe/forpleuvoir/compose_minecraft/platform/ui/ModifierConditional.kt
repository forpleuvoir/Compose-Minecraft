package moe.forpleuvoir.compose_minecraft.platform.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 条件拼接 Modifier:[condition] 为 true 时把 [block] 产出的链片段经 `then` 追加到链尾,
 * 否则原样返回接收者(等价于 `.then(Modifier)`)。
 *
 * ```
 * Modifier
 *     .padding(4.dp)
 *     .thenIf(selected) { Modifier.background(Color.Red) }
 *     .thenIf(showTooltip) { Modifier.tooltip { … } }
 * ```
 *
 * **实现约束(勿改)**:
 * - [block] 产出的是**链片段**,由 `this.then(block())` 追加 —— 直接用 `if (condition) block() else this`
 *   会把调用点之前的链整段丢掉。
 * - `inline` + [block] **不带接收者**是本 API 能穿透作用域的前提。内联使 lambda 的词法上下文
 *   等于调用点,外层 `@LayoutScopeMarker` 作用域(BoxScope / ColumnScope / RowScope 等)
 *   照旧可见 —— `Modifier.align(…)` / `Modifier.matchParentSize()` 这类作用域成员扩展可正常解析;
 *   一旦给 [block] 加上 `Modifier.() ->` 接收者,lambda 内的 `align(…)` 会解析到该接收者上,
 *   语义与 `then` 追加相冲突。
 * - [block] 必须是 lambda 而非已求值的 Modifier:条件为 false 时不应调用 `@Composable` 工厂
 *   (否则会白建一次组合与对应节点)。
 * - 条件翻转时 lambda 的组合组随之增删,组合位置与手写 `if (condition) … else Modifier` 完全一致。
 */
@Composable
inline fun Modifier.thenIf(
    condition: Boolean,
    block: @Composable () -> Modifier,
): Modifier = if (condition) this.then(block()) else this

/**
 * 条件二选一拼接 Modifier:[condition] 为 true 追加 [ifTrue] 的链片段,否则追加 [ifFalse] 的。
 *
 * 接收者与实现约束同 [thenIf]。
 */
@Composable
inline fun Modifier.thenIfElse(
    condition: Boolean,
    ifTrue: @Composable () -> Modifier,
    ifFalse: @Composable () -> Modifier,
): Modifier = if (condition) this.then(ifTrue()) else this.then(ifFalse())

/**
 * 非空拼接 Modifier:[value] 非 null 时把 `block(value)` 产出的链片段追加到链尾,
 * 否则原样返回接收者。
 *
 * 接收者与实现约束同 [thenIf];判空后 smart cast,块内 [value] 为不可空类型。
 */
@Composable
inline fun <T : Any> Modifier.thenIfNotNull(
    value: T?,
    block: @Composable (T) -> Modifier,
): Modifier = if (value != null) this.then(block(value)) else this
