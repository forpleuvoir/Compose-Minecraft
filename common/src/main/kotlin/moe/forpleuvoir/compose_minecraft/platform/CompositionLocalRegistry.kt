package moe.forpleuvoir.compose_minecraft.platform

import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.mutableStateOf

/**
 * 全局 CompositionLocal 注册器(平台开放点)。
 *
 * 依赖方模组/业务方可在任意时机(如 mod 初始化、打开 Compose 屏幕前)
 * 经 [register] 注入要「重新提供」的顶层 CompositionLocal —— 这些
 * [ProvidedValue] 会在 setContent 的组合内被
 * [androidx.compose.ui.platform.ProvideCommonCompositionLocals] 作为
 * values 注入(位于平台默认 locals 之上,可覆盖默认 locals)。
 *
 * - 全局单例:所有 Compose 场景统一注入,避免每屏重复注册;
 * - 动态生效:内部以 [mutableStateOf] 持有列表,setContent 组合内读取该
 *   state,注册/注销/清空后无需重建场景,即触发重组即时生效;
 * - 顺序语义:[androidx.compose.runtime.CompositionLocalProvider] 按顺序
 *   后者覆盖前者,因此 [register] 追加到末尾,后注册者覆盖先注册者。
 */
object CompositionLocalRegistry {

    private val _values = mutableStateOf<List<ProvidedValue<*>>>(emptyList())

    /** 当前已注册的顶层 locals(有序,后者覆盖前者)。 */
    val values: List<ProvidedValue<*>>
        get() = _values.value

    /** 注册一组 locals(追加到末尾,后注册者覆盖先注册者,即时生效)。 */
    fun register(vararg values: ProvidedValue<*>) {
        _values.value += values
    }

    /** 按 [CompositionLocal] key 注销已注册的 locals。 */
    fun unregister(vararg keys: CompositionLocal<*>) {
        _values.value = _values.value.filterNot { it.compositionLocal in keys }
    }

    /** 清空全部注册的 locals。 */
    fun clear() {
        _values.value = emptyList()
    }
}
