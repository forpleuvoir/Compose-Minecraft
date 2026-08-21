# Compose 运行时更新指南

> 本文档记录如何跟进上游 Compose Multiplatform 版本更新。
> 当前移植基线：**CMP 1.11**（`androidx/compose/**`，894 个文件）。
> 最新更新：2025-08。

---

## 0. 现状概览

| 指标 | 数量 |
|---|---|
| 移植源码文件总数 | **894** |
| 含"平台适配"修改的文件 | **59** |
| 纯上游（零修改） | **835** |

关键事实：**894 个文件同一个 `androidx.compose.*` 包空间下，不能换官方 JAR**——编译/运行都会冲突。所以更新时只能源码级跟进。

好消息：**835 个文件（93%）是纯上游源码，上游发新版后直接覆盖即可**。真正需要你花时间审的只有 59 个修改文件。

---

## 1. 修改文件分类

按耦合深度分四层：

### 第 1 层：核心重构（8 个文件，与上游根本分歧）

这些文件不仅改了类型，逻辑结构也不同。**每次更新必须逐行 diff，不能自动合并。**

| 文件 | MC 引用数 | 平台标记数 | 改动本质 |
|---|---|---|---|
| `foundation/text/BasicText.kt` | 12 | 22 | `TextStyle` → `Style`（MC），fontSize(sp) 渲染缩放，AnnotatedString 富文本重载 |
| `foundation/style/ResolvedStyle.kt` | 9 | 2 | 整文件重写：`ResolvedStyle` → MC `Style` 解析 |
| `foundation/style/StyleScope.kt` | 2 | 1 | `TextStyle` API 替换为 MC `Style` |
| `ui/text/platform/MinecraftParagraph.platform.kt` | 5 | 26 | **全新文件**，MC 原生文字度量/绘制自实现 |
| `ui/text/PlatformTextStyle.kt` | 5 | 8 | **全新文件**，TextStyle 与 MC Style 双向适配 |
| `ui/graphics/MinecraftPlatform.kt` | 6 | 14 | **全新文件**，MC 渲染平台（Canvas/Command/Shader） |
| `foundation/text/modifiers/TextAnnotatedStringNode.kt` | 3 | 8 | 富文本段处理：StyleSegment 全覆盖切分 |
| `foundation/text/modifiers/TextStringSimpleNode.kt` | 3 | 10 | 富文本段处理 + 透明度/缩放透传 |

### 第 2 层：深度适配（12 个文件）

逻辑改动较多，但类型体系与上游仍兼容。

| 文件 | MC 引用 | 平台标记 | 改动本质 |
|---|---|---|---|
| `foundation/text/modifiers/MultiParagraphLayoutCache.kt` | 1 | 12 | 缓存键加入 scale 分量，富文本段透传 |
| `foundation/text/input/internal/TextFieldCoreModifier.kt` | 1 | 10 | 光标闪烁(T.7)、水平滚动(T.6)、焦点同步、T.11 修复 |
| `foundation/text/input/internal/TextFieldLayoutStateCache.kt` | 1 | 6 | 字号缩放、键盘类型、方向合并、缓存键 |
| `foundation/text/BasicTextField.kt` | 1 | 6 | fontSize(sp) 缩放、触摸选区手柄移除(T.8) |
| `foundation/text/TextFieldSize.kt` | 3 | 2 | 最小尺寸不再依赖字体解析 |
| `foundation/text/TextMeasurer.kt` | 1 | 5 | Style 缓存键、字体解析移除 |
| `foundation/text/TextPainter.kt` | 2 | 3 | Style 无 brush/textDecoration/shadow 分离 |
| `ui/graphics/layer/GraphicsLayer.kt` | 2 | 4 | 视口剔除(T.36)、无离屏渲染(T.17)、阴影(T.14) |
| `ui/text/MultiParagraphIntrinsics.kt` | 1 | 4 | 富文本段透传、段落级样式移除 |
| `foundation/text/ParagraphLayoutCache.kt` | 1 | 4 | 多段样式、文本缩放 |
| `foundation/text/HeightInLinesModifier.kt` | 1 | 3 | 字体解析移除、MC 固定度量 |
| `ui/text/TextLayoutResult.kt` | 1 | 2 | 渲染缩放 |

### 第 3 层：导入级修改（20 个文件）

类型替换为 MC `Style`，但逻辑基本未动。**这些是 `Style` import 污染**，上游更新时只需确认 import 仍匹配。

`foundation/text/` 下：
- `TextDelegate.kt`、`TextLayoutHelper.kt`、`TextAnnotatedStringElement.kt`、`SelectableTextAnnotatedStringElement.kt`、`SelectableTextAnnotatedStringNode.kt`、`TextStringSimpleElement.kt`、`TextStyleProviderNode.kt`、`MinLinesConstrainer.kt`、`CoreTextField.kt`、`TextFieldDelegate.kt`、`TextFieldCursor.kt`
- `input/internal/TextFieldTextLayoutModifier.kt`、`TextLayoutState.kt`
- `selection/SelectionManager.kt`、`TextFieldSelectionManager.kt`、`TextSelectionRenderer.kt`
- `DeadKeyCombiner.kt`、`KeyMapping.kt`、`StringHelpers.kt`
- `contextmenu/ContextMenuUi.kt`

`ui/text/` 下：
- `MultiParagraph.kt`、`MultiParagraphIntrinsics.kt`、`Paragraph.kt`、`ParagraphIntrinsics.kt`
- `ParagraphStyle.kt`、`TextPainter.kt`
- `intl/JvmPlatformLocale.platform.kt`

**共同模式**：`style: Style` 替换了 `TextStyle`/`ParagraphStyle`/`SpanStyle`，且文件内 MC `Style` 仅作为类型传递，无额外逻辑分支。

### 第 4 层：轻量适配（19 个文件）

无 MC 引用，仅局部注释/参数调整。上游更新时几乎不需要关心。

- `ui/graphics/Canvas.kt` — post-concat 顺序注释
- `ui/graphics/drawscope/CanvasDrawScope.kt`、`DrawScope.kt` — 同上
- `ui/graphics/ImageBitmap.kt` — CPU 像素数组构造
- `ui/graphics/painter/BitmapPainter.kt` — FilterQuality 默认值
- `ui/draw/Shadow.kt` — API 弃用重定向
- `ui/input/pointer/PointerIcon.kt` — 光标类型映射
- `ui/input/pointer/PointerIcon.platform.kt` — 指针图标 platform 实现
- `ui/platform/DefaultHapticFeedback.kt` — 空实现
- `ui/platform/GraphicsLayerOwnerLayer.kt` — 命令烘焙模型
- `ui/window/Dialog.kt`、`Popup.kt`、`ComposeSceneLayerMeasurePolicy.kt` — 过渡动画/输入法 insets 空实现
- `foundation/Image.kt` — FilterQuality 默认值
- `foundation/gestures/Scrollable.kt` — 滚轮配置
- `animation/LookaheadAnimationVisualDebugHelper.kt` — TextStyle 忽略 fontSize

---

## 2. 更新流程（Step by Step）

### Step 1：拉取上游新源码

从 Compose Multiplatform 对应版本拉取 sources。推荐方式：

```bash
# 方式 A: 下载 sources JAR（最稳定）
# https://repo1.maven.org/maven2/org/jetbrains/compose/ui/1.12.0/ui-1.12.0-sources.jar
# 解压后拿到纯上游文件

# 方式 B: 直接拉 GitHub 源码
# https://github.com/JetBrains/compose-multiplatform/tree/{version}
```

### Step 2：同步 835 个纯上游文件

```bash
# 拿到上游源码后，对不在修改清单里的文件直接替换
# 可以用 git diff 验证：替换后纯上游文件 diff 应为空
```

**快速验证**：

```bash
cd common/src/main/kotlin/androidx/compose
find . -name "*.kt" | wc -l                    # 总数不变 = 894
grep -rl "net\.minecraft\|moe\.forpleuvoir" . --include="*.kt" | wc -l  # 仍为 59
```

### Step 3：逐文件审 59 个修改文件

对每个修改文件：

1. `git diff <file>` 看上游改了什么
2. 对照下方"修改清单"确认你的改动是否仍适用
3. 冲突解决：
   - **纯上游改动部分** → 直接接受
   - **你的修改部分** → 保留你的逻辑，合并上游新增/修改
   - **上游删除了你依赖的 API** → 需调整你的实现（记录到"破坏性变更"日志）

### Step 4：验证构建

```
mcp__idea__build_project(rebuild=true)
```

必须全量构建（devOnly 不可漏）。

### Step 5：跑 dev scene 手动验证

Fabric `runClient`，进入 dev 菜单，逐项测试：
- 文本渲染（BasicText / BasicTextField / AnnotatedString）
- 富文本段（点击高亮 → 段样式）
- 渲染（Geometry / Image / Vertices）
- 旋转 / 3D 透视
- 弹层（Popup / Dialog / Tooltip）
- 输入（中英文 / IME 组合）

---

## 3. 修改清单（逐文件记录）

> 每次更新时对照此清单审 diff。T.N 是 AGENTS.md 中对应任务编号。

### 3.1 `foundation/text/BasicText.kt`

**类型替换**：`TextStyle` → `net.minecraft.network.chat.Style`（MC）。
**新增 API**：
- `style: Style?`（可空，T.30）— 未传时用 `LocalDefaultTextStyle` 兜底
- `fontSize: TextUnit?`（T.26）— sp 驱动渲染缩放，18sp = 2x 基准
- `ContentOverflowBehavior`（T.19）— 渲染缩放策略
- `AnnotatedString` 重载（T.29）— spanStyles → StyleSegment 全覆盖切分
- `Component` 展平（T.3）— `flatten` 按段样式展开

**关键机制**：
- `toPlatformData` 把 Compose `TextStyle` 语义映射到 MC `Style`（color/alpha/fontSize/sp/fontWeight/fontStyle/decoration）
- `toStyleSegments` 把 AnnotatedString spanStyles 切分为 StyleSegment
- 字号缩放：`TextUnit(sp)` → 渲染矩阵 scale（18sp → 2x），布局 1x 空间 + 绘制矩阵放大

### 3.2 `foundation/style/ResolvedStyle.kt`

**整文件重写**：原 CMP 的 `ResolvedStyle` 被替换为 MC `Style` 解析器。
- 从 MC `Style` 提取：颜色/加粗/斜体/下划线/删除线/乱码/字体描述
- `toTextStyle()`：MC `Style` → Compose `TextStyle`（用于布局度量）
- `style` 字段直接暴露 MC `Style` 供渲染端使用

### 3.3 `foundation/style/StyleScope.kt`

**类型替换**：`TextStyle` 相关 API 改为 MC `Style`。
- `TextStyle()` 构造函数替换为 `Style()` / `Component.literal(...)` 模式
- 平台文档注明 MC `Style` 替换 Compose `TextStyle`

### 3.4 `foundation/style/StyleModifier.kt`

**类型替换**：MC `Style` 作为样式容器类型。

### 3.5 `foundation/text/modifiers/TextAnnotatedStringNode.kt`

**富文本段处理**：
- `segments: List<StyleSegment>` — spanStyles 切分后的段列表
- `scale: Float` — 文本渲染缩放
- `updateDraw` 比较整样式（MC `Style` 无布局/绘制分离）
- `TextStyle.merge` → `Style.withColor` 映射

### 3.6 `foundation/text/modifiers/TextStringSimpleNode.kt`

**富文本段 + 透明度**：
- `segments` + `scale` + `alpha`
- MC `Style` 无 brush/shadow/textDecoration 分离，装饰由样式承载
- `TextStyle.alpha` 合成进绘制色

### 3.7 `foundation/text/modifiers/MultiParagraphLayoutCache.kt`

**缓存键扩展**：
- `scale` 分量（T.20 自动字号、T.26 渲染缩放）
- `segments`（T.29 富文本）
- 缓存重建策略：整样式参与比较（MC `Style` 无布局/绘制分离）

### 3.8 `foundation/text/input/internal/TextFieldCoreModifier.kt`

**多项修复**：
- T.7：焦点以 `selectionState` 为准，光标动画惰性同步
- T.6：水平滚动恢复原版 ScrollState 模型
- T.11：行首光标 x 钳制到 0
- 光标绘制：MC EditBox 风格竖条光标

### 3.9 `ui/text/platform/MinecraftParagraph.platform.kt`

**全新文件**，MC 原生文字度量/绘制自实现：
- 行边界语义：exclusive end（行尾含 `\n`），半开区间 `[start, end)`
- 多段样式绘制：`drawText` 按段边界切分
- T.11 修复：`computeLines` 死循环防御（单字符 > maxWidth 强制占行）
- T.12 修复：同上
- T.6 修复：水平截断移除
- T.26：坐标统一 ×scale
- T.33 修复：缩放用 save/restore 包裹
- T.10：文本缩放（1f = 原样，布局 1x 空间 + 绘制矩阵放大）

### 3.10 `ui/text/PlatformTextStyle.kt`

**全新文件**，TextStyle 与 MC Style 双向适配：
- `obfuscated: Boolean?`（MC 乱码）
- `shadowColor: Int?`（MC 文本阴影）
- `clickEvent/hoverEvent/insertion/font`（MC 交互/字体）
- `toTextStyle()` / `fromTextStyle()` 双向映射
- `StyleSegment` 段样式

### 3.11 `ui/graphics/MinecraftPlatform.kt`

**全新文件**，MC 渲染平台：
- 命令录制：`recordColor/recordPath/recordText/recordGradient/recordVertices/recordShadow`
- `MinecraftCanvas`：像素 1:1 投影 + 像素级裁剪
- `MinecraftImageTextureCache`：资源缓存
- 3D 透视：`layer3D` 行主序 4x4 矩阵（T.15）
- 视口剔除（T.36）
- 阴影 LRU 缓存

### 3.12 `ui/graphics/layer/GraphicsLayer.kt`

**关键适配**：
- T.36 视口剔除：clip 图层裁剪
- T.17 无离屏渲染：改为 CPU 光栅化
- T.14 阴影：距离场方案（GPU shader）
- T.9 修复：translate 后画布处于图层局部坐标系

### 3.13 `ui/text/TextMeasurer.kt`

**Style 缓存**：
- 字号缩放（T.26）
- `TextStyle` 的 `resolveDefaults` 移除
- 整样式参与缓存哈希

### 3.14 `ui/text/TextPainter.kt`

**Style 适配**：
- MC `Style` 无 brush/textDecoration/shadow/drawStyle 分离
- Brush 由 `MinecraftParagraph` 按 SolidColor 解析

### 3.15 `ui/text/MultiParagraphIntrinsics.kt`

**段透传 + 段落样式移除**：
- `segments` 透传到 Paragraph
- MC `Style` 无段落级样式，统一用默认 `ParagraphStyle`

### 3.16 `ui/text/Paragraph.kt` / `ParagraphIntrinsics.kt` / `MultiParagraph.kt`

**类型替换**：`ParagraphStyle` → `Style`，逻辑基本未动。

### 3.17 `ui/draw/Shadow.kt`

**API 弃用重定向**：官方 `Modifier.shadow` 弃用 → 重定向到 `moe.forpleuvoir.compose_minecraft.platform.ui.shadow`。

### 3.18 `ui/graphics/Canvas.kt` / `drawscope/*.kt`

**post-concat 顺序**：与官方 Skia 一致（`M' = T(p) * S * T(-p)`）。

### 3.19 `ui/window/Dialog.kt` / `Popup.kt`

**空实现**：过渡动画不实现（无 Skia GraphicsLayer 录制）；输入法 insets 恒为 0。

### 3.20 `ui/platform/DefaultHapticFeedback.kt`

**空实现**：MC 无触觉硬件。

### 3.21 `ui/platform/GraphicsLayerOwnerLayer.kt`

**命令烘焙模型**：`GraphicsLayer.record` 把子树命令收集为烘焙图层。

### 3.22 `foundation/text/selection/*.kt`

**触摸选区手柄移除**（T.8）：MC 桌面端无触摸，删除 `TextFieldSelectionHandle` 等，保留浮动工具条。

### 3.23 `ui/text/intl/JvmPlatformLocale.platform.kt`

**MC 语言包元数据**：`Language.isDefaultRightToLeft`、`mcLanguageCodeToLocale`。

### 3.24 `ui/input/pointer/PointerIcon.platform.kt`

**光标映射**：Compose `PointerIcon` → MC `CursorTypes`（ARROW/CROSSHAIR/IBEAM/POINTING_HAND）。

---

## 4. 破坏性变更响应矩阵

上游更新时，如果出现以下情况，需要人工干预：

| 上游变更类型 | 影响面 | 处理方式 |
|---|---|---|
| **上游新增 API** | 通常无影响 | 直接接受，不引入使用 |
| **上游修改已有 API 签名** | 可能影响你的 59 个文件 | 按修改清单逐文件判断 |
| **上游删除 API** | 可能破坏你的依赖 | 查调用链，替换或降级 |
| **上游变更内部数据结构** | 可能破坏你的缓存/序列化 | 重点检查 `LayoutCache`、`AnnotatedString` |
| **上游变更 expect/actual 绑定** | 影响 `.platform.kt` 文件 | 必须同步，否则编译失败 |
| **上游重命名包/类** | 影响 import 路径 | 全局替换 import |

---

## 5. 关键约束提醒

1. **不要引入官方 JAR**：`androidx.compose.*` 包空间冲突，只能源码级跟进。
2. **保持 `Style` → `TextStyle` 映射方向一致**：上游改动可能引入新的 `TextStyle` 字段（如 `lineHeight`），需要同步到 `PlatformTextStyle`。
3. **保留"平台适配"注释**：这些注释是上游 diff 时的锚点，不能删除。
4. **T.N 编号对应 AGENTS.md 任务**：不要丢失编号引用。
5. **构建必须全量**：`build_project(rebuild=true)`，devOnly 不可漏。

---

## 6. 快速命令

```bash
# 统计修改文件数（与本文档 59 核对）
grep -rl "平台适配" common/src/main/kotlin/androidx/compose --include="*.kt" | wc -l

# 列出所有修改文件
grep -rl "平台适配" common/src/main/kotlin/androidx/compose --include="*.kt" | sort

# 查看某文件的 MC 耦合度
grep -cE "net\.minecraft|moe\.forpleuvoir" <file>

# 统计纯上游文件（与 835 核对）
find common/src/main/kotlin/androidx/compose -name "*.kt" | wc -l
```
