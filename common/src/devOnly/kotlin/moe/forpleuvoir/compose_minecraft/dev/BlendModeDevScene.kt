package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen

/**
 * 混合模式测试(T.22):17 种可表达的 BlendMode。
 *
 * 每块 = 灰色底(0xFF757575)+ 半透明红(0xCCE53935,alpha 0.66)叠加,
 * 红色块带对应 blendMode。矩形走 blit 组 pipeline(gui_blend_*),圆形走
 * 三角化组 pipeline(gui_triangles_blend_*) —— 双几何验证。
 *
 * 预期效果(灰底 117,117,117;红 229,57,53 × 0.66):
 * - Clear: 红区透明 → 露出白背景
 * - Src: 纯红(255,0,0)
 * - Dst: 灰(红被忽略)
 * - SrcOver: 红×0.66 + 灰×0.34 → 亮红
 * - DstOver: 灰盖红
 * - SrcIn: 红×灰alpha(1.0) = 红;灰透明处不画
 * - SrcOut: 红×0(灰不透明)→ 全透明 → 白
 * - SrcAtop/DstAtop/Xor: 边缘混合
 * - Plus: 红+灰 饱和
 * - Modulate: 红×灰 → 暗红(117,0,0)
 * - Screen: 红+灰×(1-红) → 亮粉
 * - Darken: min(红,灰) → 暗红;Lighten: max → 粉
 */
@Composable
internal fun BlendModeDevScene() {
    val nums = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩", "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰")
    val modes = listOf(
        BlendMode.Clear to false, BlendMode.Src to false, BlendMode.Dst to false,
        BlendMode.SrcOver to false, BlendMode.DstOver to false,
        BlendMode.SrcIn to true, BlendMode.DstIn to true,
        BlendMode.SrcOut to true, BlendMode.DstOut to true,
        BlendMode.SrcAtop to true, BlendMode.DstAtop to true, BlendMode.Xor to true,
        BlendMode.Plus to false, BlendMode.Modulate to true,
        BlendMode.Screen to true, BlendMode.Darken to true, BlendMode.Lighten to true,
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(
                text = "混合模式测试 (T.22)",
                style = TextStyle(fontSize = 22.sp),
                color = { Color(0xFF1A1A1A) },
            )
            BasicText(
                text = "灰底 + 半透明红(alpha 0.66)叠加;圆 = 三角化几何。12 种高级模式(Overlay/Difference/...)回退 SrcOver。请按编号报告每块颜色。",
                style = TextStyle(fontSize = 12.sp),
                color = { Color(0xFF455A64) },
            )
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "",
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )
            modes.chunked(3).forEachIndexed { rowIndex, row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEachIndexed { colIndex, (mode, useCircle) ->
                        BlendCell(
                            num = nums[rowIndex * 3 + colIndex],
                            mode = mode,
                            useCircle = useCircle,
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.width(96.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BlendCell(num: String, mode: BlendMode, useCircle: Boolean) {
    Column(Modifier.width(96.dp)) {
        Box(
            Modifier
                .size(96.dp, 72.dp)
                .background(Color(0xFF757575))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (useCircle) {
                    drawCircle(
                        color = Color(0xCCE53935),
                        radius = 26f,
                        center = Offset(w / 2f, h / 2f),
                        blendMode = mode,
                    )
                } else {
                    drawRect(
                        color = Color(0xCCE53935),
                        topLeft = Offset(w / 2f - 32f, h / 2f - 20f),
                        size = Size(64f, 40f),
                        blendMode = mode,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = "$num ${mode.toString()}",
            style = TextStyle(fontSize = 13.sp),
            color = { Color(0xFF1A1A1A) },
        )
    }
}
