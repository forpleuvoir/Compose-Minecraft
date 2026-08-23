# 文本度量/渲染管线 · 机械普查底账

> 普查方式:对六范围目录按符号表逐行检索,命中全量列出(未做语义过滤)。
> 本文为事实底账,供《text-measurement-redesign.md》引用;重查可用相同符号表复现。

范围:`androidx/compose/ui/text/platform`、`androidx/compose/foundation/text`、
`androidx/compose/ui/text`(TextMeasurer/MultiParagraph*/TextPainter/Paragraph*/StyleSegment)、
`moe/.../platform/render/text`、`moe/.../platform/render/backend/GuiStateBackend.kt`、
`moe/.../platform/ui/text`(均相对 common/src/main/kotlin)。

## A. 字号/缩放公式(独立实现共 5 处)

1. TextStyleMapper.kt:144 `scale = fontSizeSp * density.density * density.fontScale / TextRenderConfig.fontScaleBasePx`
2. BasicText.kt:967(toTextScale)`value * density.density * density.fontScale / TextRenderConfig.fontScaleBasePx`
3. MultiParagraphLayoutCache.kt:223/492(autoSize)`getFontSize(...).toPx() / TextRenderConfig.fontScaleBasePx`
4. MinecraftParagraph.platform.kt:416(段级 ratio)`(sp * density.density * density.fontScale / fontScaleBasePx) / scale`
5. MinecraftParagraph.platform.kt 其余 ~28 处 `* scale` / `/ scale` 出入口(width/height/baseline/坐标换算全链)

历史注:基准经历 9f(硬编码)→ fontScaleBasePx(mc.lineHeight=9)→ 像素模式 12 的三轮演变;
第 4 处段级公式内层 `/9f` 曾长期未随迁(本轮修复)。

## B. 行高/基线约定(三种并存)

| 约定 | 数值 | 使用方 |
|---|---|---|
| VanillaMetricsSource.baselineFromTop = **7.2f** | 0.8×9 启发式 | 历史布局数学 |
| TrueTypeTextWriter / GuiStateBackend 锚点 | **7f** + 补偿(布局基线−7) | GuiTextRenderState 提交 |
| MinecraftParagraph.platform.kt:689 | **0.8f × lineBoxHeight** 再 ×scale | getLineBaseline(光标/选区) |
| 像素字体自然行盒 | ascent 13px / 行盒 16px(@em12) | PixelFont 度量 |

另:装饰厚度 `metrics.lineHeight / 9f`(TrueTypeTextWriter:106/219、RasterBackend:275)隐含 9px 参考。

## C. 字体/模式分支(决策点 ≥20 处)

- BasicTextField.kt:309-311(state 重载盖章)
- CoreTextField.kt:211-213(legacy 盖章)
- BasicText.kt:177(backend 读取透传)
- GuiStateBackend.kt:248-250(routing 三条件)、369/434(coverage swap)、455-471(切段状态机)
- TrueTypeTextWriter.kt:64-69(chain/metrics 门控)、99(bold 链)、135(回退段强制 DEFAULT)、256-263(supports)
- MetricsSource.kt:118-126(activeMetricsSource 双层门控)
- TextCompositionLocals.kt:73-75(resolveDefaultFont 哨兵映射)
- TextStringSimpleNode/TextAnnotatedStringNode(textBackendOverride 盖章/恢复各一对)

## D. 度量对象跨模块引用(9 文件)

定义方:MetricsSource.kt(MetricsSource/VanillaMetricsSource/TrueTypeMetricsSource/activeMetricsSource)、
TrueTypeFont.kt(TrueTypeFont)、TrueTypeFontManager.kt(manager+chain)、PixelFont.kt(族注册表)。

引用方:MinecraftParagraph.platform.kt(:129 快照)、GuiStateBackend.kt(295/352/424 + pixelFontCovers)、
TrueTypeTextWriter.kt(regularChain/metricsOrNull/boldChain)、GlyphCache.kt(fontSlot/getOrCreate)、
MetricsSource.kt(自引)、TrueTypeFontManager.kt(Chain/loadChain)、PixelFont.kt(自引)、
TextRenderConfig.kt(注释)、MinecraftCustomFonts.kt(lineHeightPx9)。

零引用确认:foundation/text 约 100 文件、androidx ui/text 六目标中 Paragraph.kt/TextPainter.kt、
moe ui/text 的 CustomFont.kt/LocalCharFilter.kt、render/text 的 MinecraftGuiText/GlyphAtlas/GuiGlyphRenderState。

统计:A+B+C+D 主清单条目 ≈ 447,分布前五:
MinecraftParagraph.platform(69)、GuiStateBackend(35)、MetricsSource(34)、BasicText(31)、TrueTypeTextWriter(30)。
