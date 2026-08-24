package moe.forpleuvoir.compose_minecraft.platform.ui.text

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.text.KeyCommand

/**
 * 字段键行为自定义(P3 键位方案):
 *
 * - [mappingOverride]:键→命令覆盖,先于默认 `KeyMapping.map` 查询
 *   (返回 null = 交回默认映射);
 * - [typedOverride]:可见字符输入过滤(在 typed 通道前执行)。
 *   返回 [TypedEdit.Pass] 放行原字符、[TypedEdit.Consume] 仅消费、
 *   [TypedEdit.Insert] 以替换文本输入(Tab→4 空格等)。
 *
 * 两层都可选;默认 null = 行为与未接入前完全一致。
 */
class TextFieldKeyScheme(
    val mappingOverride: ((KeyEvent) -> KeyCommand?)? = null,
    val typedOverride: ((KeyEvent, codePoint: Int) -> TypedEdit)? = null,
) {
    /** 单行字段 Tab 切焦点时由字段注入的焦点移动器 */
    internal var focusMover: (() -> Unit)? = null

    sealed interface TypedEdit {
        data class Insert(val text: String) : TypedEdit
        data object Consume : TypedEdit
        data object Pass : TypedEdit
    }

    companion object {
        /** 代码编辑器示例:Tab 输入 4 空格 */
        fun tabAsSpaces(count: Int = 4) = TextFieldKeyScheme(
            typedOverride = { _, _ -> TypedEdit.Insert(" ".repeat(count)) },
        )

        /** Local 全局覆盖(参数优先于此 Local) */
        val Local = staticCompositionLocalOf<TextFieldKeyScheme?> { null }
    }
}
