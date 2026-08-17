package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

/**
 * 文本字号测试屏幕(T.19):验证 `BasicText(fontSize)` 以 sp 驱动字号。
 *
 * 约定 **16sp = 原样 1 倍**(与旧 `scale = 1f` 渲染一致),8sp 半大、32sp 两倍;
 * 布局尺寸(行高/宽度/换行)随缩放联动(T.10 的 1/scale 换算链路)。
 *
 * 分块(编号便于反馈):
 * ① 字号阶梯:8/16/24/32/48sp,背景框高度 = 各字号行盒;
 * ② 行高对比:三字号同行(顶部对齐),行盒取最大子项;
 * ③ 窄宽换行:160dp 内 32sp 长文本,布局宽度按 1/scale 换算;
 * ④ Component 版:MC 富文本重载同样支持 fontSize;
 * ⑤ 样式叠加:24sp 与 bold/italic/underline/颜色组合;
 * ⑥ 中英混排:32sp 中文 + 拉丁 + 数字(MC 字体 CJK 贴图随矩阵缩放);
 * ⑦ 对照:默认 16sp 灰字,确认与旧版渲染一致。
 */
@Composable
fun TextDevScene() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            BasicText(
                "文本字号测试 (BasicText fontSize / sp)",
                style = Style.EMPTY.withColor(Color.White).withBold(true),
            )
            BasicText(
                "T.19:fontSize(sp) 驱动字号,16sp = 原样 1 倍(与旧 scale=1 渲染一致);\n" +
                    "仅支持 sp 单位(em 抛异常)。布局尺寸随缩放联动。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)),
            )

            // ── ① 字号阶梯 ──
            SectionLabel("① 字号阶梯(背景框高度 = 各字号行盒)")
            listOf(8.sp, 16.sp, 24.sp, 32.sp, 48.sp).forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .background(Color(0xFF263238))
                        .padding(6.dp)
                ) {
                    BasicText(
                        "${s.value.toInt()}sp",
                        fontSize = 10.sp,
                        style = Style.EMPTY.withColor(Color(0xFF90A4AE)),
                        modifier = Modifier.width(52.dp),
                    )
                    BasicText("Minecraft 文本 Aa", fontSize = s)
                }
            }

            // ── ② 行高对比 ──
            SectionLabel("② 行高对比(三字号同行,顶部对齐)")
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF263238))
                    .padding(6.dp)
            ) {
                BasicText("8sp 行", fontSize = 8.sp, modifier = Modifier.padding(end = 12.dp))
                BasicText("16sp 行", fontSize = 16.sp, modifier = Modifier.padding(end = 12.dp))
                BasicText("32sp 行", fontSize = 32.sp)
            }

            // ── ③ 窄宽换行 ──
            SectionLabel("③ 窄宽换行(160dp 内 32sp,布局宽度按 1/scale 换算)")
            BasicText(
                "窄宽度换行测试:32sp 大字号在 160dp 宽度内自动换行,布局宽度按 1/scale 换算。",
                fontSize = 32.sp,
                modifier = Modifier
                    .width(160.dp)
                    .background(Color(0xFF263238))
                    .padding(4.dp),
            )

            // ── ④ Component 版 ──
            SectionLabel("④ Component 版(M C 富文本重载)")
            BasicText(
                component = Component.literal("Component 版 BasicText").withStyle(
                    Style.EMPTY.withBold(true).withColor(Color(0xFF4FC3F7))
                ),
                fontSize = 24.sp,
            )

            // ── ⑤ 样式叠加 ──
            SectionLabel("⑤ 样式叠加(全部 24sp)")
            BasicText("加粗 + 24sp", fontSize = 24.sp, style = Style.EMPTY.withBold(true))
            BasicText("斜体 + 24sp", fontSize = 24.sp, style = Style.EMPTY.withItalic(true))
            BasicText("下划线 + 24sp", fontSize = 24.sp, style = Style.EMPTY.withUnderlined(true))
            BasicText(
                "粗体 + 斜体 + 下划线 + 红色 + 24sp",
                fontSize = 24.sp,
                style = Style.EMPTY
                    .withBold(true)
                    .withItalic(true)
                    .withUnderlined(true)
                    .withColor(Color(0xFFFF7043)),
            )

            // ── ⑥ 中英混排 ──
            SectionLabel("⑥ 中英混排(32sp,MC 字体 CJK 贴图随矩阵缩放)")
            BasicText(
                "中文混排 Mix ABC 123 —— 32sp",
                fontSize = 32.sp,
            )

            // ── ⑦ 对照参考 ──
            SectionLabel("⑦ 对照参考(默认 16sp,与旧 scale=1 渲染一致)")
            BasicText(
                "默认 16sp:与旧 scale=1 渲染一致(对照参考,本行即默认字号)",
                style = Style.EMPTY.withColor(Color(0xFF78909C)),
            )

            // ── ⑧ TextAutoSize ──
            SectionLabel("⑧ TextAutoSize(自动缩放:二分搜索最大适配字号)")
            BasicText(
                "220x100 容器内短文本:应放大填满;140x44 容器内长文本:应缩小避免溢出。",
                style = Style.EMPTY.withColor(Color(0xFF78909C)),
            )
            Box(
                Modifier
                    .width(220.dp)
                    .height(100.dp)
                    .background(Color(0xFF263238))
                    .border(1.dp, Color(0xFF80CBC4))
                    .padding(6.dp)
            ) {
                BasicText(
                    "AutoSize 放大",
                    autoSize = TextAutoSize.StepBased(),
                )
            }
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .width(140.dp)
                    .height(44.dp)
                    .background(Color(0xFF263238))
                    .border(1.dp, Color(0xFF80CBC4))
                    .padding(6.dp)
            ) {
                BasicText(
                    "TextAutoSize 自动缩小测试 0123456789 中文混排",
                    autoSize = TextAutoSize.StepBased(),
                )
            }
        }
    }
}

/** 分组标题:青色加粗小标题。 */
@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}
