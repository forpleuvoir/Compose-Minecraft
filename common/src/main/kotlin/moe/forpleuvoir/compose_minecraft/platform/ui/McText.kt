package moe.forpleuvoir.compose_minecraft.platform.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow

/**
 * MC 化的基础文本组件(T.4):替代 Compose 的 BasicText 公共入口。
 *
 * 平台定位(Basic 层级,不做风格化):
 * - 无默认外观 —— 颜色/加粗/斜体/下划线/删除线/乱码/字体/阴影全部由 [McTextStyle] 表达;
 * - 样式基准为 MC `Style`(渲染经完整 MC 文本管线);
 * - 仅支持统一 [McTextStyle] 的纯文本,富文本(AnnotatedString 多样式)第一版不做;
 * - 行高固定 9px(MC Font),字号忽略。
 *
 * @param text 要显示的文本。
 * @param modifier 应用于布局节点的 [Modifier]。
 * @param style MC 文本样式(颜色/加粗/斜体/下划线/删除线/乱码/字体/阴影/背景)。
 * @param onTextLayout 新文本布局计算完成时的回调。
 * @param overflow 视觉溢出处理方式。
 * @param softWrap 是否软换行;false 时按无限宽度布局。
 * @param maxLines 最大可见行数(1 <= [minLines] <= [maxLines])。
 * @param minLines 最小可见行数。
 * @param color 覆盖 [style] 中文本颜色的颜色生产者。
 */
@Composable
fun McText(
    text: String,
    modifier: Modifier = Modifier,
    style: McTextStyle = McTextStyle.Default,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    color: ColorProducer? = null,
) {
    // 平台适配点:公共入口迁至 minecraft 包,内部委托 foundation 的 internal BasicText(String 路径)
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        color = color,
    )
}
