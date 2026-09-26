package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformSpanStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalDefaultFont
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalDefaultTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.MinecraftFonts
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style

/**
 * 文本字号测试屏幕:验证 `BasicText(fontSize)` 以 sp 驱动字号。
 *
 * 约定 **16sp = 原样 1 倍**(与旧 `scale = 1f` 渲染一致),8sp 半大、32sp 两倍;
 * 布局尺寸(行高/宽度/换行)随缩放联动(1/scale 换算链路)。
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
                "文本字号测试 (BasicText fontSize / sp)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "T.19:fontSize(sp) 驱动字号,16sp = 原样 1 倍(与旧 scale=1 渲染一致);\n" +
                    "仅支持 sp 单位(em 抛异常)。布局尺寸随缩放联动。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
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
                        style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle().merge(TextStyle(fontSize = 10.sp)),
                        modifier = Modifier.width(52.dp),
                    )
                    BasicText("Minecraft 文本 Aa", style = TextStyle(fontSize = s))
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
                BasicText("8sp 行", style = TextStyle(fontSize = 8.sp), modifier = Modifier.padding(end = 12.dp))
                BasicText("16sp 行", style = TextStyle(fontSize = 16.sp), modifier = Modifier.padding(end = 12.dp))
                BasicText("32sp 行", style = TextStyle(fontSize = 32.sp))
            }

            // ── ③ 窄宽换行 ──
            SectionLabel("③ 窄宽换行(160dp 内 32sp,布局宽度按 1/scale 换算)")
            BasicText(
                "窄宽度换行测试:32sp 大字号在 160dp 宽度内自动换行,布局宽度按 1/scale 换算。",
                style = TextStyle(fontSize = 32.sp),
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
            BasicText("加粗 + 24sp", style = Style.EMPTY.withBold(true).toTextStyle().merge(TextStyle(fontSize = 24.sp)))
            BasicText("斜体 + 24sp", style = Style.EMPTY.withItalic(true).toTextStyle().merge(TextStyle(fontSize = 24.sp)))
            BasicText("下划线 + 24sp", style = Style.EMPTY.withUnderlined(true).toTextStyle().merge(TextStyle(fontSize = 24.sp)))
            BasicText(
                "粗体 + 斜体 + 下划线 + 红色 + 24sp",
                style = Style.EMPTY
                    .withBold(true)
                    .withItalic(true)
                    .withUnderlined(true)
                    .withColor(Color(0xFFFF7043))
                    .toTextStyle()
                    .merge(TextStyle(fontSize = 24.sp)),
            )

            // ── ⑥ 中英混排 ──
            SectionLabel("⑥ 中英混排(32sp,MC 字体 CJK 贴图随矩阵缩放)")
            BasicText(
                "中文混排 Mix ABC 123 —— 32sp",
                style = TextStyle(fontSize = 32.sp),
            )

            // ── ⑦ 对照参考 ──
            SectionLabel("⑦ 对照参考(默认 16sp,与旧 scale=1 渲染一致)")
            BasicText(
                "默认 16sp:与旧 scale=1 渲染一致(对照参考,本行即默认字号)",
                style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle(),
            )

            // ── ⑧ TextAutoSize ──
            SectionLabel("⑧ TextAutoSize(自动缩放:二分搜索最大适配字号)")
            BasicText(
                "220x100 容器内短文本:应放大填满;140x44 容器内长文本:应缩小避免溢出。",
                style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle(),
            )
            Box(
                Modifier
                    .width(220.dp)
                    .height(100.dp)
                    .background(Color(0xFF263238))
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

            // ── ⑨ MC 渲染特性(PlatformSpanStyle 承载)──
            SectionLabel("⑨ MC 渲染特性(PlatformSpanStyle:obfuscated/shadowColor/clickEvent/hoverEvent/insertion/font)")
            BasicText(
                "Obfuscated 乱码(闪烁字体):",
                style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle(),
            )
            BasicText(
                "THIS IS OBFUSCATED",
                style = TextStyle.Default.merge(
                    SpanStyle(platformStyle = PlatformSpanStyle(obfuscated = true)),
                ),
            )
            BasicText(
                "ShadowColor 阴影文字:",
                style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle(),
            )
            BasicText(
                "带阴影的文本",
                style = TextStyle.Default.merge(
                    SpanStyle(
                        platformStyle = PlatformSpanStyle(shadowColor = Color(0x80000000)),
                    ),
                ),
            )
            BasicText(
                "ClickEvent/HoverEvent/Insertion(构造承载,渲染同普通文本):",
                style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle(),
            )
            BasicText(
                "点击命令 / 悬停提示 / 插入文本",
                style = TextStyle.Default.merge(
                    SpanStyle(
                        platformStyle = PlatformSpanStyle(
                            clickEvent = ClickEvent.RunCommand("say hello from compose"),
                            hoverEvent = HoverEvent.ShowText(Component.literal("MC 原版悬停提示")),
                            insertion = "inserted-text",
                        ),
                    ),
                ),
            )

            // ── ⑩ 富文本(AnnotatedString spanStyles 逐段混排)──
            SectionLabel("⑩ 富文本(spanStyles 逐段混排:每段一行,便于核对样式)")
            BasicText(
                "spanStyles 段级样式叠加 base TextStyle;段间无样式文本走默认样式。字号(scale)逐段暂不支持。",
                style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle(),
            )
            BasicText(
                buildAnnotatedString {
                    append("① 普通文本")
                    append("\n")
                    withStyle(SpanStyle(color = Color(0xFFFF5252))) { append("② 红色") }
                    append("\n")
                    withStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                        append("③ 加粗")
                    }
                    append("\n")
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append("④ 斜体")
                    }
                    append("\n")
                    withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                        append("⑤ 下划线")
                    }
                    append("\n")
                    withStyle(
                        SpanStyle(
                            textDecoration =
                                androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        ),
                    ) { append("⑥ 删除线") }
                    append("\n")
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF69F0AE),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            platformStyle = PlatformSpanStyle(obfuscated = true),
                        ),
                    ) { append("⑦ 绿粗乱码") }
                    append("\n")
                    withStyle(
                        SpanStyle(
                            platformStyle = PlatformSpanStyle(
                                shadowColor = Color(0xFFB71C1C),
                                clickEvent = ClickEvent.RunCommand("say rich text works"),
                            ),
                        ),
                    ) { append("⑧ 红阴影点击") }
                    append("\n")
                    append("⑨ 尾部普通")
                },
                style = TextStyle(fontSize = 72.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF263238))
                    .padding(6.dp),
            )
            BasicText(
                "段间未覆盖文本示例(仅首尾两段有样式,中间为默认):",
                style = Style.EMPTY.withColor(Color(0xFF78909C)).toTextStyle(),
            )
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color(0xFF40C4FF))) { append("[蓝]") }
                    append("无样式中段 ")
                    withStyle(SpanStyle(color = Color(0xFFFFB74D))) { append("[橙]") }
                },
                style = TextStyle(fontSize = 36.sp),
            )

            // ── ⑪ 默认字体/默认样式 CompositionLocal──
            SectionLabel("⑪ 默认字体/默认样式 CompositionLocal(T.30)")
            CompositionLocalProvider(
                LocalDefaultTextStyle provides TextStyle(fontSize = 36.sp, color = Color(0xFFFFD54F)),
            ) {
                BasicText("默认样式:未传 style(黄 36sp)")
                BasicText("显式覆盖", style = TextStyle(color = Color(0xFF64B5F6), fontSize = 36.sp))
            }
            BasicText(
                "默认样式:Provider 外(恢复白 18sp)",
                style = TextStyle(color = Color(0xFF78909C)),
            )
            CompositionLocalProvider(LocalDefaultFont provides MinecraftFonts.Alt) {
                BasicText("默认字体:alt(未指定字体)", style = TextStyle(fontSize = 36.sp))
            }
            BasicText("默认字体:default(恢复)", style = TextStyle(fontSize = 36.sp))

            // ── ⑫ overflow(Ellipsis / Clip)──
            SectionLabel("⑫ overflow:Ellipsis 应在末行尾追加 \"...\",Clip 仅截断")
            BasicText(
                "Ellipsis(maxLines=2):一行很长的文本第二行应当被裁剪并追加三点省略号,验证溢出语义",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color(0xFF80CBC4)),
            )
            BasicText(
                "Clip(maxLines=2):同样文本直接截断,无省略号",
                maxLines = 2,
                overflow = TextOverflow.Clip,
                style = TextStyle(color = Color(0xFF78909C)),
            )
            BasicText(
                "Ellipsis(maxLines=1):单行超宽也应出现省略号",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color(0xFFCE93D8)),
            )

            // ── ⑬ InlineContent──
            SectionLabel("⑬ InlineContent:占位符原子排版 + 替代文本不显示")
            val inlineContent = mapOf(
                // Center 对齐:在行盒内垂直居中 —— 对 MC 字形观感最稳
                // (AboveBaseline 底贴基线偏高、Bottom 底贴行盒偏沉)
                "redBox" to InlineTextContent(
                    Placeholder(12.sp, 12.sp, PlaceholderVerticalAlign.Center),
                ) {
                    Box(Modifier.fillMaxSize().background(Color(0xFFEF5350)))
                },
                "tag" to InlineTextContent(
                    Placeholder(40.sp, 12.sp, PlaceholderVerticalAlign.Center),
                ) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0xFF26A69A)),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("TAG", style = TextStyle(color = Color.White, fontSize = 9.sp))
                    }
                },
            )
            BasicText(
                buildAnnotatedString {
                    append("前文ABC ")
                    appendInlineContent("redBox", "[红块]")
                    append(" 中 ")
                    appendInlineContent("tag", "[标签]")
                    append(" 后文。占位符应占位且不显示替代文本;窄宽下整体换行不截半。")
                },
                inlineContent = inlineContent,
                modifier = Modifier.width(240.dp),
                style = TextStyle(color = Color(0xFFB0BEC5)),
            )

            // ── ⑭ 段级字号(span fontSize)──
            SectionLabel("⑭ 段级字号:span fontSize 驱动段内缩放,行高取最大段")
            BasicText(
                buildAnnotatedString {
                    append("小 ")
                    withStyle(SpanStyle(fontSize = 9.sp, color = Color(0xFF80CBC4))) { append("9sp") }
                    append(" 中 ")
                    withStyle(SpanStyle(fontSize = 36.sp, color = Color(0xFFFFB74D))) { append("36sp") }
                    append(" 大;窄宽换行按缩放后宽度判定,行盒取最大段。")
                },
                modifier = Modifier.width(260.dp),
                style = TextStyle(color = Color.White),
            )

            // ── ⑮ 段落对齐(TextAlign)──
            // 对齐只在「容器比文本宽」时可见 —— 所以对照用**短文本 + 240dp 容器**:
            // Left/Start/Justify 贴左、Center 居中、Right/End 贴右,位置差异一眼可辨。
            // (此前用「几乎占满容器的换行文本」对照,偏移只有几像素,等于看不出。)
            SectionLabel("⑮ 段落对齐:短文本 + 240dp 容器(左/中/右位置差异一眼可辨)")
            listOf(
                "Left" to TextAlign.Left,
                "Center" to TextAlign.Center,
                "Right" to TextAlign.Right,
                "Start(= Left,Ltr)" to TextAlign.Start,
                "End(= Right,Ltr)" to TextAlign.End,
                "Justify(不支持→Start)" to TextAlign.Justify,
            ).forEach { (label, align) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        label,
                        style = TextStyle(color = Color(0xFF80CBC4)),
                        modifier = Modifier.width(180.dp),
                    )
                    BasicText(
                        "短文本",
                        style = TextStyle(color = Color.White, textAlign = align),
                        modifier = Modifier
                            .width(240.dp)
                            .background(Color(0xFF263238)),
                    )
                }
            }
            // 换行对照:每行的起点随行宽变化(短行偏移更明显)
            BasicText(
                "换行对照(Center):这一行比较长会占满容器宽度,第二行略短,第三行最短。",
                style = TextStyle(color = Color(0xFFB0BEC5), textAlign = TextAlign.Center),
                modifier = Modifier
                    .width(300.dp)
                    .background(Color(0xFF37474F)),
            )

            // ── ⑯ onTextLayout:三个重载都必须回调 ──
            // 验证点(客户端日志搜 `textLayout:`):String / AnnotatedString / Component
            // 三条重载都应打印尺寸与行数;Component 版此前是死参数(永不回调)。
            SectionLabel("⑯ onTextLayout:三个重载都应回调(日志搜 textLayout:)")
            BasicText(
                "String 版:onTextLayout 应回调(打印尺寸/行数)",
                style = TextStyle(color = Color(0xFFB0BEC5)),
                modifier = Modifier.width(260.dp),
                onTextLayout = { println("textLayout: [String] size=${it.size} lines=${it.lineCount}") },
            )
            BasicText(
                buildAnnotatedString { append("AnnotatedString 版:onTextLayout 应回调") },
                style = TextStyle(color = Color(0xFFB0BEC5)),
                modifier = Modifier.width(260.dp),
                onTextLayout = { println("textLayout: [Annotated] size=${it.size} lines=${it.lineCount}") },
            )
            BasicText(
                component = Component.literal("Component 版:onTextLayout 应回调(修复点)"),
                defaultStyle = Style.EMPTY.withColor(Color(0xFFB0BEC5)),
                modifier = Modifier.width(260.dp),
                onTextLayout = { println("textLayout: [Component] size=${it.size} lines=${it.lineCount}") },
            )

            // ── ⑰ TextMeasurer / 老工厂(MultiParagraph / Paragraph)对齐 ──
            // 数值:日志搜 `alignProbe:` —— 三条老入口的 getLineLeft(0) 应随对齐变化
            //   (240px 容器 + 短文本):Left≈0、Center≈(240−w)/2、Right≈240−w。
            // ⚠️ 三条入口的约束口径不同(都是官方语义,写错会抛异常或对齐无余量):
            //   · TextMeasurer:width = min==max ? max : intrinsic(≡ finalMaxWidth);
            //     要给**定宽**(min == max = 240)才有对齐余量,它内部转成 (0, width) ✓
            //   · MultiParagraph:**契约要求 minWidth == 0**(否则抛 IllegalArgumentException);
            //     直接给 maxWidth = 240 即可 —— 它把 maxWidth 原样转发给每个 Paragraph,
            //     对齐余量来自那个 maxWidth(容器宽),与 min 无关
            //   · Paragraph(text, style, constraints, …):constraints 直达 MinecraftParagraph,
            //     定宽 (240,240) ⇒ alignWidth = maxWidth = 240 ✓
            // ⚠️ 字号口径:本平台没有"字号"参数,字号 = scale(emPx,18sp ⇒ 18px)。
            //   · TextMeasurer / ParameterIntrinsics 版有 scale ✓ → 传 18f 文字可读;
            //   · 便捷构造(MultiParagraph/Paragraph 的 annotatedString/text 版)**没有 scale 形参**
            //     (既有设计)→ 恒 1px 字号,故其 lineLeft 反映的是 3px 宽字符串
            //     (Center = (240−3)/2 ≈ 118、Right = 237),数值仍然能证明 textAlign 传到了段落。
            //   推荐口径:字号与对齐都随 intrinsics 走(见下面的 18px 对照行)。
            // 视觉:TextMeasurer 量出的 layout 直接 drawText 进 240dp 容器,与 ⑮ 对照。
            SectionLabel("⑰ TextMeasurer / 老工厂(MultiParagraph/Paragraph):同样支持 textAlign")
            val probeMeasurer = rememberTextMeasurer()
            val probeDensity = LocalDensity.current
            val probeResolver = LocalFontFamilyResolver.current
            val probeStyle = Style.EMPTY.withColor(Color(0xFFFFFFFF))
            listOf(
                "Left" to TextAlign.Left,
                "Center" to TextAlign.Center,
                "Right" to TextAlign.Right,
            ).forEach { (label, align) ->
                val probe = remember(probeMeasurer, probeStyle, probeDensity, probeResolver, align) {
                    val measured = probeMeasurer.measure(
                        text = AnnotatedString("短文本"),
                        style = probeStyle,
                        // 定宽容器 = 240px(与 visual 的 Canvas 一致)
                        constraints = Constraints(minWidth = 240, maxWidth = 240),
                        // 字号:scale 是 emPx(18sp ⇒ 18px),不传 = 1f = 1px 文字
                        scale = 18f,
                        textAlign = align,
                    )
                    @Suppress("DEPRECATION")
                    val legacyParagraph = Paragraph(
                        "短文本",
                        probeStyle,
                        Constraints(minWidth = 240, maxWidth = 240),
                        probeDensity,
                        probeResolver,
                        textAlign = align,
                    )
                    // MultiParagraph 契约:minWidth 必须为 0 → 只给 maxWidth(容器宽)
                    val legacyMulti = MultiParagraph(
                        AnnotatedString("短文本"),
                        probeStyle,
                        Constraints(maxWidth = 240),
                        probeDensity,
                        probeResolver,
                        textAlign = align,
                    )
                    // 推荐口径:intrinsics 携带 {scale, textAlign},两条老入口都能拿到字号 + 对齐
                    val intrMulti = MultiParagraph(
                        MultiParagraphIntrinsics(
                            AnnotatedString("短文本"),
                            probeStyle,
                            listOf(),
                            probeDensity,
                            probeResolver,
                            scale = 18f,
                            textAlign = align,
                        ),
                        Constraints(maxWidth = 240),
                    )
                    @Suppress("DEPRECATION")
                    val intrParagraph = Paragraph(
                        ParagraphIntrinsics(
                            "短文本",
                            probeStyle,
                            listOf(),
                            probeDensity,
                            probeResolver,
                            scale = 18f,
                            textAlign = align,
                        ),
                        Constraints(maxWidth = 240),
                    )
                    println(
                        "alignProbe: [$label]" +
                            " measurer18=${measured.getLineLeft(0)}" +
                            " intrMP18=${intrMulti.getLineLeft(0)}" +
                            " intrP18=${intrParagraph.getLineLeft(0)}" +
                            " 便捷1px: mp=${legacyMulti.getLineLeft(0)} p=${legacyParagraph.getLineLeft(0)}"
                    )
                    Probe(measured, legacyParagraph, legacyMulti, intrMulti, intrParagraph)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(260.dp)) {
                        BasicText(
                            "$label 18px: measurer=${probe.first.getLineLeft(0).toInt()}" +
                                " / intrMP=${probe.fourth.getLineLeft(0).toInt()}" +
                                " / intrP=${probe.fifth.getLineLeft(0).toInt()}",
                            style = TextStyle(color = Color(0xFF80CBC4)),
                        )
                        BasicText(
                            "便捷构造 1px: mp=${probe.third.getLineLeft(0).toInt()}" +
                                " / p=${probe.second.getLineLeft(0).toInt()}",
                            style = TextStyle(color = Color(0xFF78909C)),
                        )
                    }
                    Canvas(
                        Modifier
                            .width(240.dp)
                            .height(20.dp)
                            .background(Color(0xFF263238))
                    ) {
                        drawText(probe.first, color = Color.White)
                    }
                }
            }
        }
    }
}

/** 分组标题:青色加粗小标题。 */
@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true).toTextStyle(),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** ⑰ 的探针结果容器(5 条入口的测量结果)。 */
private class Probe(
    val first: TextLayoutResult,
    val second: Paragraph,
    val third: MultiParagraph,
    val fourth: MultiParagraph,
    val fifth: Paragraph,
)
