# 文本系统 MC 化设计(完全替换 TextStyle)

> 目标:**文本相关的东西完全以 Minecraft 文本样式为基准** —— 用 MC 样式类型
> **彻底替换** Compose 的 `TextStyle`,文本渲染走完整 MC `Style`
> (颜色/加粗/斜体/下划线/删除线/乱码/字体/阴影),光标与选区按 MC `EditBox`
> 风格,度量用 MC 字体精确前缀宽度。
>
> **状态:方向已确认;实现留待后续会话。**

## 0. 架构边界(平台定位)

**本 mod 只提供基础能力,不做风格化:**

- 定位类似 `compose-ui` / `compose-foundation` 的 **Basic 层级**;
- 类似 **Compose Material** 的主题系统、默认组件外观(按钮/输入框/卡片等
  自带样式的组件)当前**完全不考虑**;
- `McTextStyle` 是 **MC `Style` 的基础封装**(能力字段 1:1),不是风格化预设;
- `McText` / `McTextField` 是**无默认外观的基础组件**:
  - `McTextField` 不画边框/背景(同 `BasicTextField` 语义);
  - UI 长什么样(边框、背景、配色、布局)完全由业务方决定。

## 1. 已确认决策

| 决策点 | 结论 |
|---|---|
| 文本样式基准 | **完全替换 Compose TextStyle**,改成 MC 样式类型(不保留 textStyle 参数、不做映射兼容) |
| 光标视觉 | MC EditBox 风格(竖条光标 + 选区矩形高亮,闪烁接 MC `guiTicks` 节奏) |
| 选区高亮颜色 | 沿用 `LocalTextSelectionColors`(业务可配) |
| 触摸选区手柄 | **移除**(桌面鼠标场景不需要手柄球) |
| 实现 | 留待后续会话 |

## 2. 现状链路(改造前)

```
TextStyle(Compose)
  └─ TextFieldTextLayoutModifier(textStyle)
       └─ TextLayoutState → Paragraph(text, textStyle)
            └─ MinecraftParagraph.paint:仅取 color,样式全丢
                 └─ DrawTextCommand(text, x, y, color, fontSize)
                      └─ MinecraftRenderContext → GuiTextRenderState(纯文本无 Style)
```

其余样式相关部分(现状 → 目标):

| 部分 | 现状 | 目标 |
|---|---|---|
| 光标 | Compose `drawLine` + `CursorAnimationState` | MC EditBox 风格 + MC 闪烁节奏 |
| 选区高亮 | `highlightPaint` 矩形 + 可配颜色 | 同左(保留可配) |
| 光标位置度量 | `avgCharWidth` 近似 | `font.width(前缀)` 精确(同 `EditBox.getScreenX`) |
| 命中测试 | 近似 | 前缀宽度定位 |
| 文本水平滚动 | 无 | `plainSubstrByWidth` 截断 + `displayPos` 跟随 |
| 触摸选区手柄 | `TextFieldSelectionHandle` | 移除 |

## 3. MC 样式类型(新建,替换 TextStyle)

```
class McTextStyle(
  color: Color,                 // 对应 Style.withColor
  bold: Boolean = false,        // Style.withBold
  italic: Boolean = false,      // Style.withItalic
  underlined: Boolean = false,  // Style.withUnderlined
  strikethrough: Boolean = false, // Style.withStrikethrough
  obfuscated: Boolean = false,  // Style.withObfuscated
  font: ResourceLocation? = null, // Style.withFont(资源包字体)
  shadow: Boolean = false,      // GuiTextRenderState.dropShadow
  background: Color = Transparent, // GuiTextRenderState.backgroundColor
)
```

→ 内部渲染时构造:

```
Component.literal(text).withStyle(Style.EMPTY
  .withColor(...).withBold(...).withItalic(...)
  .withUnderlined(...).withStrikethrough(...).withObfuscated(...)
  .withFont(...))
```

## 4. 完全替换的波及面(必须逐处替换)

TextStyle 在 Compose 文本栈的分布与处置:

| 位置 | 处置 |
|---|---|
| `BasicText(text, style = TextStyle)` | **替换为 `McText(text, style = McTextStyle)`**;原 BasicText 入口移除或改签名为 McTextStyle |
| `BasicTextField` 公共 API 的 `textStyle` 参数 | **替换为 McTextStyle** |
| `CoreTextField` 内部 textStyle 使用 | 换 McTextStyle |
| `TextFieldTextLayoutModifier(textStyle)` | 字段换 McTextStyle |
| `TextLayoutState` / `TextFieldTextLayoutModifierNode` | 换 McTextStyle |
| `MinecraftParagraphIntrinsics(style: TextStyle)` | 换 McTextStyle(不再从 TextStyle 取字段) |
| `MinecraftParagraph.paint` | 直接消费 McTextStyle,记录样式快照 |
| `SpanStyle` / `AnnotatedString` 多样式富文本 | 第一版**不做**(只支持统一 McTextStyle;富文本后续阶段再议) |
| `TextMeasurer` / `TextPainter` 等文本工具 | 若依赖 TextStyle,同步替换或搁置 |

> 说明:TextField 的 `value`(`TextFieldValue` = 文本 + 选区)不含样式,不受影响;
> 光标/选区颜色本就走独立参数(`CursorBrush`/`LocalTextSelectionColors`),也不受影响。

## 5. 分阶段实施

| 阶段 | 内容 | 影响文件 |
|---|---|---|
| T.0 类型 | 新建 `McTextStyle`(与 MC Style 1:1) | 新增 minecraft 包文件 |
| T.1 样式承载 | `DrawTextCommand` 扩展 MC 样式快照;`MinecraftRenderContext` 构造带 Style 的 Component | MinecraftPlatform.kt / MinecraftRenderContext.kt |
| T.2 替换 Paragraph | `MinecraftParagraph`/Intrinsics 样式参数 TextStyle → McTextStyle | MinecraftParagraph.platform.kt |
| T.3 替换 TextField | CoreTextField / TextFieldTextLayoutModifier / TextLayoutState 的 textStyle → McTextStyle | foundation/text/** |
| T.4 组件 | `McText` 替代 BasicText(移除旧入口) | 新增 minecraft 包文件 + foundation/text/BasicText.kt |
| T.5 精确度量 | 前缀宽度替换 avgCharWidth(光标/命中/词界) | MinecraftParagraph.platform.kt |
| T.6 水平滚动 | plainSubstrByWidth + displayPos | MinecraftParagraph.platform.kt |
| T.7 光标 MC 化 | EditBox 风格光标 + guiTicks 闪烁 | TextFieldCursor.kt |
| T.8 移除手柄 | 删除 TextFieldSelectionHandle 相关 | CoreTextField.kt / selection/** |
| T.9 验证 | dev scene:TextField + McText 样式矩阵(颜色/加粗/斜体/下划线/删除线/乱码/阴影) | devOnly |

## 6. 风险

- **波及面大**:TextStyle 在文本栈多处为公共类型,替换后 Compose 内部任何
  仍引用 TextStyle 的文本路径(如 `AnnotatedString`、`TextMeasurer`)需逐一
  清理或显式搁置,否则编译即失败;
- `Component` + `Style` 渲染需验证 `GuiTextRenderState` 对全部样式组合
  (尤其 obfuscated 与资源字体位置)的行为;
- 精确度量替换 avgCharWidth 会影响 `getBoundingBox`/`fillBoundingBoxes`
  依赖方(选区高亮、autofill),需回归;
- 水平滚动与 Compose `TextFieldScrollerPosition` 的交互需对齐,避免光标
  越界或抖动;
- `BasicText` 移除后,foundation 内部若仍有引用(如 ContextMenu/工具提示
  内部文本)需同步替换为 McText,否则编译失败。
