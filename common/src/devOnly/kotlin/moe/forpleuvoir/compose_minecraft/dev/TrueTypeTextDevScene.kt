package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import moe.forpleuvoir.compose_minecraft.platform.render.text.TextRenderBackend
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalTextRenderBackend
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.forpleuvoir.compose_minecraft.platform.render.text.FontSource
import moe.forpleuvoir.compose_minecraft.platform.render.text.TextRenderConfig
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

/**
 * TrueType 文本渲染对照测试(T.TT,第一步:管线 + 开关 + 度量同源 + 回退)。
 *
 * - 顶部开关切换原版位图渲染 / 自研 TrueType 渲染(全局 [TextRenderConfig.enabled]);
 * - 切换后整棵子树重建([key]):布局度量重新快照,宽度/行高按新来源计算;
 * - dev 字体:C:\Windows\Fonts\msyh.ttc(微软雅黑,仅 dev 验证用,
 *   D1 内置字体后续再定,不打包进发布 JAR);
 * - 全部 MC Style 渲染特性(粗体/斜体/下划线/删除线/混淆/渐变)均管线内合成;
 *   资源字体 run 仍回退原版。
 */
@Composable
fun TrueTypeTextDevScene() {
    // 首次组合注册 dev 字体源(幂等)
    remember {
        val devFont = "C:\\Windows\\Fonts\\msyh.ttc"
        if (TextRenderConfig.fontSources.none { it.path == devFont }) {
            TextRenderConfig.fontSources += FontSource(devFont)
        }
        // 回退链成员:黑体 + Segoe UI Symbol(符号字体,覆盖 msyh/simhei
        // 都缺失的 ⑪⑫ 等 —— 三级链演示:雅黑 → 黑体 → Segoe UI Symbol)
        val devFallback = "C:\\Windows\\Fonts\\simhei.ttf"
        if (TextRenderConfig.fontSources.none { it.path == devFallback }) {
            TextRenderConfig.fontSources += FontSource(devFallback)
        }
        val devSymbol = "C:\\Windows\\Fonts\\seguisym.ttf"
        if (TextRenderConfig.fontSources.none { it.path == devSymbol }) {
            TextRenderConfig.fontSources += FontSource(devSymbol)
        }
        // 字体家族:粗体字重文件(微软雅黑 Bold);首位为失效源,演示加载自动跳过
        val devBold = "C:\\Windows\\Fonts\\msyhbd.ttc"
        val devBoldMissing = "C:\\Windows\\Fonts\\__not_exist_bold__.ttf"
        if (TextRenderConfig.boldFontSources.none { it.path == devBold }) {
            TextRenderConfig.boldFontSources += FontSource(devBoldMissing)
            TextRenderConfig.boldFontSources += FontSource(devBold)
        }
        true
    }

    // 初始选中状态跟随实际全局开关(而非假定 TTF)
    var truetype by remember { mutableStateOf(TextRenderConfig.enabled) }


    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x88121212))
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
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            BasicText(
                "TrueType 文本渲染对照 (stb_truetype 管线)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "切换后整屏重建:布局宽度/行高随度量来源变化(flag 关闭时与历史行为一致)。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // ── 模式开关 ──
            Row(Modifier.padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                ModeButton(
                    label = "原版位图渲染",
                    selected = !truetype,
                    onClick = { TextRenderConfig.enabled = false; truetype = false },
                )
                ModeButton(
                    label = "TrueType 渲染",
                    selected = truetype,
                    onClick = { TextRenderConfig.enabled = true; truetype = true },
                )
                var boundsOn by remember { mutableStateOf(TextRenderConfig.debugTextBounds) }
                ModeButton(
                    label = if (boundsOn) "行盒:开" else "行盒:关",
                    selected = boundsOn,
                    onClick = {
                        TextRenderConfig.debugTextBounds = !TextRenderConfig.debugTextBounds
                        boundsOn = TextRenderConfig.debugTextBounds
                    },
                )
            }
            BasicText(
                if (truetype) {
                    "当前:TTF 渲染器(平滑轮廓;仅显式资源字体/缺字 run 回退原版)。本界面外始终原版。"
                } else {
                    "当前:原版位图渲染器(8×8 像素风,放大有块状锯齿)"
                },
                style = Style.EMPTY
                    .withColor(if (truetype) Color(0xFF80CBC4) else Color(0xFFFFCC80))
                    .toTextStyle(),
                modifier = Modifier.padding(top = 4.dp),
            )

            key(truetype) {
                Column {
                    SectionLabelTt("① 中英混排(默认 16sp)")
                    BasicText(
                        "中文混排 Mix ABC 123 —— 默认字号",
                        style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                    )

                    SectionLabelTt("② 字号阶梯(sp → scale → 光栅化字号同步放大)")
                    listOf(9.sp, 16.sp, 24.sp, 36.sp).forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .background(Color(0xFF263238))
                                .padding(6.dp)
                        ) {
                            BasicText(
                                "${s.value.toInt()}sp",
                                style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle()
                                    .merge(TextStyle(fontSize = 10.sp)),
                                modifier = Modifier.width(52.dp),
                            )
                            BasicText(
                                "Minecraft 文本 Aa",
                                style = TextStyle(fontSize = s, color = Color.White),
                            )
                        }
                    }

                    SectionLabelTt("③ 窄宽换行(160dp 内 32sp,换行按 TTF 度量判定)")
                    BasicText(
                        "窄宽度换行测试:32sp 大字号在 160dp 宽度内自动换行。",
                        style = TextStyle(fontSize = 32.sp, color = Color.White),
                        modifier = Modifier
                            .width(160.dp)
                            .background(Color(0xFF263238))
                            .padding(4.dp),
                    )

                    SectionLabelTt("④ 富文本段级混排(颜色段;粗体段管线内双绘制合成)")
                    BasicText(
                        component = Component.literal("")
                            .append(Component.literal("普通段 ").withStyle(Style.EMPTY.withColor(Color.White)))
                            .append(Component.literal("红色段 ").withStyle(Style.EMPTY.withColor(Color(0xFFFF7043))))
                            .append(Component.literal("青色段").withStyle(Style.EMPTY.withColor(Color(0xFF4FC3F7)))),
                        fontSize = 24.sp,
                    )
                    BasicText(
                        component = Component.literal("粗体段(管线合成)").withStyle(Style.EMPTY.withBold(true)),
                        fontSize = 24.sp,
                    )

                    SectionLabelTt("⑤ 输入框 A/B 对照(同字号同文案;上=BasicText 下=BasicTextField)")
                    // A:BasicText 基准(青底)
                    BasicText(
                        "输入中文/English…",
                        style = TextStyle(fontSize = 18.sp, color = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1B3A2A))
                            .padding(horizontal = 6.dp),
                    )
                    // B:BasicTextField(蓝紫底)—— 与 A 紧贴,字形基线应完全对齐
                    val input = rememberTextFieldState("输入中文/English…")
                    BasicTextField(
                        state = input,
                        textStyle = Style.EMPTY.withColor(Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF232A4A))
                            .padding(horizontal = 6.dp),
                    )
                    BasicText(
                        "若 B 中文字相对 A 下移 → 输入框绘制路径 bug;若两者一致但整体偏下 → 度量问题。",
                        style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle()
                            .merge(TextStyle(fontSize = 10.sp)),
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    SectionLabelTt("⑦ 图集压力(重复字形应命中缓存,页数稳定)")
                    BasicText(
                        "缓存验证 Cache Test: ".repeat(6),
                        style = Style.EMPTY.withColor(Color(0xFFA5D6A7)).toTextStyle(),
                    )

                    SectionLabelTt("⑪ 定向回退(LocalTextRenderBackend.VANILLA 子树)")
                    CompositionLocalProvider(LocalTextRenderBackend provides TextRenderBackend.VANILLA) {
                        BasicText(
                            "本行强制原版位图渲染(VANILLA 子树)",
                            style = Style.EMPTY.withColor(Color.White).toTextStyle()
                                .merge(TextStyle(fontSize = 18.sp)),
                            modifier = Modifier
                                .background(Color(0xFF3A2A1B))
                                .padding(horizontal = 6.dp),
                        )
                    }
                    BasicText(
                        "对照:此行在 TTF 渲染器下为矢量字形",
                        style = TextStyle(fontSize = 18.sp, color = Color.White),
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    SectionLabelTt("⑧ 混淆 §k(同宽随机字形,默认 16ms 重掷可配;advance 不变布局不抖)")
                    BasicText(
                        component = Component.literal("OBFS 混淆混淆 obfuscation 0123")
                            .withStyle(Style.EMPTY.withColor(Color.White).withObfuscated(true)),
                        fontSize = 24.sp,
                    )
                    BasicText(
                        "对照(未混淆): OBFS 混淆混淆 obfuscation 0123",
                        style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle().merge(TextStyle(fontSize = 24.sp)),
                    )

                    SectionLabelTt("⑧ 装饰线(下划线=行盒底、删除线=行高中线,几何对齐原版)")
                    BasicText(
                        "下划线 underline + 24sp",
                        style = Style.EMPTY.withUnderlined(true).toTextStyle().merge(TextStyle(fontSize = 24.sp)),
                    )
                    BasicText(
                        "删除线 strikethrough + 24sp",
                        style = Style.EMPTY.withStrikethrough(true).toTextStyle().merge(TextStyle(fontSize = 24.sp)),
                    )
                    BasicText(
                        "下划线 + 删除线 + 红色 + 24sp",
                        style = Style.EMPTY.withUnderlined(true).withStrikethrough(true)
                            .withColor(Color(0xFFFF7043)).toTextStyle().merge(TextStyle(fontSize = 24.sp)),
                    )

                    SectionLabelTt("⑨ 斜体与粗斜体(管线内剪切合成)")
                    BasicText(
                        "斜体 Italic + 24sp",
                        style = Style.EMPTY.withItalic(true).toTextStyle().merge(TextStyle(fontSize = 24.sp)),
                    )
                    BasicText(
                        "粗斜体 Bold Italic + 24sp",
                        style = Style.EMPTY.withBold(true).withItalic(true).toTextStyle()
                            .merge(TextStyle(fontSize = 24.sp)),
                    )

                    SectionLabelTt("⑩ 阴影与彩色阴影(PlatformSpanStyle.shadowColor)")
                    BasicText(
                        "黑色阴影 Shadow",
                        style = Style.EMPTY.withColor(Color.White)
                            .withShadowColor(0xFF000000.toInt()).toTextStyle()
                            .merge(TextStyle(fontSize = 24.sp)),
                    )
                    BasicText(
                        "彩色阴影:白字红影",
                        style = Style.EMPTY.withColor(Color.White)
                            .withShadowColor(0xFFE53935.toInt()).toTextStyle()
                            .merge(TextStyle(fontSize = 24.sp)),
                    )
                    BasicText(
                        "彩色阴影:黄字蓝影 + 粗体",
                        style = Style.EMPTY.withBold(true).withColor(Color(0xFFFFEB3B))
                            .withShadowColor(0xFF1E88E5.toInt()).toTextStyle()
                            .merge(TextStyle(fontSize = 24.sp)),
                    )

                    SectionLabelTt("⑪ 字体家族(缺字沿回退链:雅黑→黑体→Segoe UI Symbol)(粗体独立字重文件 msyhbd;失效源自动跳过)")
                    BasicText(
                        "真粗体 Real Bold 0123",
                        style = Style.EMPTY.withBold(true).toTextStyle()
                            .merge(TextStyle(fontSize = 24.sp)),
                    )
                    BasicText(
                        "常规对照 Regular 0123",
                        style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle()
                            .merge(TextStyle(fontSize = 24.sp)),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    BasicText(
                        "说明:粗体走独立字重文件;移除 boldFontSources 后此行退化为膨胀合成。",
                        style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle()
                            .merge(TextStyle(fontSize = 10.sp)),
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    SectionLabelTt("⑫ 渐变文本(逐字形采样)")


                    BasicText(
                        "渐变文字 Gradient Text",
                        style = TextStyle(
                            // 横向渐变:单行文本只有水平方向有采样跨度,垂直渐变会恒取首色
                            brush = Brush.horizontalGradient(listOf(Color(0xFFFFEB3B), Color(0xFFE91E63))),
                            fontSize = 32.sp,
                        ),
                    )
                }
            }
        }
    }
}

/** 模式切换按钮 */
@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(end = 8.dp)
            .background(if (selected) Color(0xFF4FC3F7) else Color(0xFF37474F))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        BasicText(
            label,
            style = Style.EMPTY.withColor(if (selected) Color.Black else Color.White).toTextStyle(),
        )
    }
}

/** 分组标题(TrueType 场景专用,避免与各场景私有 SectionLabel 冲突) */
@Composable
private fun SectionLabelTt(text: String) {
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true).toTextStyle(),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}
