# Compose-Minecraft 未实现 / 占位代码清单

> 生成日期:2026-08
> 范围:`common/src/main`(平台核心 + 移植的 `androidx.compose.*` 运行时)与 `common/src/devOnly`(开发测试场景)。
> 约定:标注 **`第一版不支持`** 的代码在运行时抛 `UnsupportedOperationException`;**`占位`** 的实现返回空/无操作。
> 说明:
> - 本文档**只记录尚未实现 / 占位**的条目;已实现能力(渲染、输入、文本、弹窗、光标、剪贴板、复述等)见
>   `PLATFORM_MIGRATION_GUIDE.md` 与 `AGENTS.md`,不再重复列出。
> - 移植源码中还残留大量上游(CMP/AOSP)的 `TODO(b/…)` 注释,属于**继承自上游的回填提示**,
>   非本平台实际缺漏,本文档在 §12 单独说明,不逐条展开。

---

## 0. 摘要(现状总览)

平台侧基础能力(场景宿主、Screen 桥接、文本/裁剪/滚轮/聚焦/光标/输入管线、系统剪贴板、
Popup/Dialog 弹层、MC 复述)已可用并有开发场景验证。以下能力**尚未实现或为占位**:

| 类别 | 状态 | 说明 |
|---|---|---|
| 图层能力 | 部分 | clip/scissor、translate/scale/rotate/alpha 已通;`saveLayer`、`clipPath`、`clipRect(Difference)`、复合 Path 操作不支持 |
| 文本 | 部分 | 段级 **字号**(scale)暂不支持(布局统一 base scale);InlineContent / 占位符未实现;BiDi / 排版方向固定 LTR;渐变 Brush 不支持 |
| 焦点/事件 | 部分 | 键盘/鼠标/滚轮/聚焦/IME/指针图标已通;双击、拖放、触摸 未实现或占位 |
| 平台 API | 居多占位 | 文本工具栏、无障碍 screenReader 接口、窗口 inset、触感反馈、软键盘、URI 等,其中多数见 §11「可忽略」 |
| 互操作视图 | 占位(可删) | 无原生视图嵌入,`InteropView` 以 `Any` 占位 —— **Android 原生 View 机制,完全不需要,见 §11.1** |

---

## 1. 渲染 / 图形(`androidx.compose.ui.graphics`)

基础几何(矩形/圆角/圆/椭圆/弧/线/路径/点/文本/图片/顶点)、阴影(T.14)、3D 透视(T.15)、
图片(T.16)、CPU 光栅化(T.17)、colorFilter(T.21)、blendMode(T.22)均已实现。
以下为明确不支持 / 占位:

### 1.1 `MinecraftCanvas`(`ui/graphics/MinecraftPlatform.kt`)抛 `UnsupportedOperationException` 的 API

- `Path.addPath` 仅支持 `MinecraftPath`(实际类型不符时抛)
- `Path.op(operation)` 路径布尔运算
- `PathMeasure.getSegment` / `getPosition` / `getTangent`
- `saveLayer`(不允许离屏图层,AGENTS.md 约束 #4「不做离屏渲染」)
- `clipRect(ClipOp.Difference)`
- `clipPath`
- `drawImageRect` 仅支持 `MinecraftImageBitmap`(实际类型不符时抛)
- `Paint.shader`(渐变/纹理 paint 不支持)
- `Paint.blendMode` 仅支持 `SrcOver`(记录端校验放宽:T.22 渲染端按模式选 blend pipeline,
  超出 17 种可表达范围回退 SrcOver)

### 1.2 `EmptyCanvas`(`ui/graphics/drawscope/EmptyCanvas.kt`)

完整 `Canvas` 的**无操作占位实现**,所有方法抛 `UnsupportedOperationException`。
用于 `DrawContext` 内部保证非空 canvas,业务代码不应触达。

### 1.3 `Shader.kt`

`LinearGradient` / `RadialGradient` / `SweepGradient` / `Image` / `Composite` Shader
全部抛 `UnsupportedOperationException`。**渐变不支持**,`Brush` 仅 `SolidColor` 可用(文本侧同样限制,见 §3)。

### 1.4 `PathEffect.kt`

`cornerPathEffect` / `dashPathEffect` / `chainPathEffect` / `stampedPathEffect`
全部抛 `UnsupportedOperationException`。虚线/圆角路径效果不支持。

### 1.5 `GraphicsLayer`(`ui/graphics/layer/GraphicsLayer.kt`)—— 离屏合成家族

- ✅ translate/scale/rotationZ/rotationX/rotationY(3D 透视,T.15)/pivot/alpha/clip(矩形 scissor)/
  shadowElevation(T.14)/outline/toImageBitmap(T.17)/colorFilter(T.21)/blendMode(T.22)均已实现。
- 未实现:`compositingStrategy` / `renderEffect`(离屏合成家族,属性链路完整但渲染端不消费),
  依赖离屏渲染,按 AGENTS.md 约束 #4 **不实现**,见 §11.2。
- `toImageBitmap()`(T.17)支持几何/顶点命令;**文本与阴影命令不支持,快照中缺失**。

### 1.6 `GraphicsLayerOwnerLayer.kt`(`ui/platform/`)

`setLightingInfo`(3D 光照)为空实现;阴影本身已由 `GraphicsLayer.drawShadow` +
`MinecraftShadowRenderer` 实现(T.14),不依赖该接口。

### 1.7 `RenderIntent`(`graphics/colorspace/RenderIntent.kt`)

部分渲染意图(如 `Absolute`、`Relative` 之外的某些)注明 "currently not implemented and behaves like Relative"。

### 1.8 `CompositionLocals.platform.kt`(`ui/platform/`)

`HostDefaultProvider` 相关 `TODO(CMP-9752)`:未完整实现,当前为占位对齐。

---

## 2. 文本 / 排版(`androidx.compose.ui.text`)

文本渲染后端(`MinecraftParagraph`)、TextStyle 语义映射(T.28)、富文本段级混排(T.29)、
autoSize(T.20)、字号 Local(T.30/T.32)、自定义字体(T.32)均已实现。以下为未实现 / 简化:

### 2.1 `MinecraftParagraph.platform.kt`(`ui/text/platform/`)

- ❌ **BiDi / 排版方向**:固定 `ResolvedTextDirection.Ltr`。
- ❌ **段级字号(scale)**:富文本段级 color/bold/italic/decoration/PlatformSpanStyle 生效,
  但**段级字号暂不支持**(布局统一 base scale)。
- ❌ **渐变 Brush**:`paint(Brush:…)` 只解析 `SolidColor`,否则回退白。
- ❌ **Text 占位符**:`getPathForRange` 返回空 `Path`;`placeholderRects` 返回 `emptyList()` ——
  InlineContent / Placeholder 排进文本不生效。
- ❌ `getRangeForRect` 返回整段 `TextRange(0, length)`(简化命中)。
- ❌ `getWordBoundary` 用简化字符类判断(字母/数字/下划线),非 ICU 单词边界。

### 2.2 `TextPainter.kt`

`drawText(…Brush…)` 解析为纯色;渐变/阴影/装饰按 McTextStyle 能力承载或忽略(§2.1)。

### 2.3 `Key.kt`(`ui/input/key/`)

顶部注释 `TODO(demin): implement most of key codes`——但桥接层 `ComposeScreen` 已提供
MC→Compose 键码映射表(`glfwKeyToComposeKey`),Compose `Key` 常量本身沿移植版,无需处理。

---

## 3. 文本画刷 / 绘制细节

- `Brush`(渐变)`Shader` 全线不支持(见 §1.3)——平台所有绘制(`drawRect(Brush)`、文本背景等)只认 `SolidColor`。
- `Paint.shader` 读取即抛。

---

## 4. 弹窗 / 焦点层级(`androidx.compose.ui.window`, `ui.scene`)

- ✅ **`Popup`** 与 **`Dialog`** 已实现(T.33):`ComposeSceneLayer` 图层机制 —— Popup 锚点定位/
  PositionProvider/clipping/焦点隔离/Escape 与 outside 点击关闭;Dialog scrim 遮罩/居中/
  模态焦点圈定/dismissOnBackPress/dismissOnClickOutside/usePlatformDefaultWidth。
- 多层焦点/键盘分发:`CanvasLayersComposeScene` 的 `focusedLayer`/`isInteractive` 完整生效。
- ⚠️ 剩余:`DropdownMenu` 未移植;`Dialog` 开合动画(`animateTransition`)未实现(默认关闭);
  `ComposeScreen` 头注明确:**双击尚未支持**。

---

## 5. 平台服务 / `PlatformContext`(`androidx.compose.ui.platform`)

`MinecraftPlatformContext`(可继承替换,`platform/screen/`)已实现:textInputService(IME)、
剪贴板、setPointerIcon(光标)、semanticsOwnerListener(复述源)、windowInfo、startInputMethod。
以下为其余**空/占位**默认实现(继承自 `PlatformContext.Empty`):

| 成员 | 实现 | 处置 |
|---|---|---|
| `textToolbar` | `EmptyTextToolbar`(showMenu 空) | 视业务(文本粘贴菜单)再补 |
| `screenReader` | `EmptyPlatformScreenReader`(`isActive=false`) | 平台接口占位;**MC 复述已另行实现**(§9,不经该接口) |
| `dragAndDropManager` | `EmptyDragAndDropManager` | 视业务(系统拖放)再补 |
| `windowInsets` | `EmptyPlatformWindowInsets` | **可忽略**,见 §11.3(MC 全屏无系统栏/IME inset) |
| `parentFocusManager` | `EmptyFocusManager`(clearFocus/moveFocus 空/返回 false) | 平台无父宿主,保留占位 |
| `hapticFeedback` | `DefaultHapticFeedback.performHapticFeedback` 空实现 | **可忽略**,见 §11.3(平台无振动) |
| `AccessibilityManager` | `DefaultAccessibilityManager`(基本枚举) | **可忽略**,见 §11.3 |
| `uriHandler` | `createPlatformUriHandler` | 视业务(打开外链)再接 |

---

## 6. 渲染工作流(`platform/render/`,T.24)—— 已实现,简述

- **`ComposeGuiRenderer` + `GuiRendererMixin` 帧钩子**:Compose 内容不再进原版 `GuiRenderState`
  节点树,而是走独立渲染工作流 —— 像素 1:1 投影(`setupOrtho`),丢弃 guiScale;render pass 绑定
  `mainRenderTarget` 颜色/深度视图,与主帧缓冲同目标;原版 `guiRenderer` 仍提交 HUD/toasts(共存)。
- **文本提交**:`text.ensurePrepared().visit(GlyphVisitor)`,按序交错绘制(与几何 z 序一致,
  修复「文本批量后置穿透 scrim」问题);相同 pipeline/scissor/textureSetup 的 draw 合并 append。
- 回放命令:`DrawRect/RoundRect/Text/Oval/Circle/Arc/Line/Path/Points/GradientRect/Shadow/
  ImageRect/Vertices` 全部已实现;3D 命令走 CPU 顶点透视(T.15),文本/阴影/渐变/图片降级 2D 仿射近似。
- 剩余限制:无离屏图层(§1.5);`Dialog` 动画不做(§4)。

---

## 7. 输入 / 事件(`androidx.compose.ui.input.*`, `ComposeScreen`)

鼠标/键盘/滚轮/IME/指针图标已通(见 AGENTS.md 已知限制)。以下未实现:

- ❌ **双击**:`ComposeScreen.mouseClicked` 传 `doubleClick=false` 路径未验证/未实现。
- ❌ **触摸 / 多指 / 触控笔**:`PointerButton`/`PointerType` 只覆盖鼠标;
  `PointerEvent.platform.kt` 注释 `TODO(CMP-2184) support more buttons`。⚠️ 纯鼠标 GUI 场景通常用不到。
- ❌ **系统拖放(Drag & Drop)**:`ComposeSceneDragAndDropNode` 存在,但
  `dragAndDropManager` 为空实现;系统拖放未接。⚠️ 视业务决定。
- ❌ **旋转 Pane/Pan/Scale/手势**:`CanvasLayersComposeScene` 有分支但无 MC 输入源驱动(无触摸屏)。
- ⚠️ **SoftKeyboard 拦截 / rotary**:`FocusOwnerImpl.dispatchSoftKeyboardEvent` 等有 TODO(
  "...to embedded views");软键盘按用户决定**不实现**(MC 桌面 IME 由系统输入法负责)。

---

## 8. 互操作视图(`androidx.compose.ui.viewinterop`)—— 完全不需要

- `InteropView` / `InteropViewHolder` / `InteropContainer` 以 `Any` 占位(Minecraft 不嵌入任何原生视图)。
- `InteropViewFactoryHolder` 抛 `NotImplementedError`。
- 这是 **Android 原生 View-Compouse 混编机制被连带拷贝的产物**,运行时永不触发(见 **§11.1**),
  无需实现;后续可连接口契约整体剥离(低优先级清理)。

---

## 9. 无障碍

- ✅ **MC 复述(Narration)已实现**(自行实现,不经平台 screenReader 接口):`ComposeScreen`
  重写 `updateNarratedWidget` → `NarratedHelper`(`platform/screen/`)遍历 Compose 语义树
  (SemanticsOwner → SemanticsNode),悬停/焦点优先读 TITLE 与 HINT(role/state 等经 MC 语言系统
  `assets/compose_minecraft/lang/*.json` 翻译),经原版朗读调度链触发(getNarrator().saySystemNow)。
- 平台接口 `EmptyPlatformScreenReader.isActive=false` 仍为占位(**无无障碍宿主,可忽略,见 §11.3**)。
- 语义树自动写入:BasicText(text)/BasicTextField(editableText)/clickable(role)/
  focusable(focused)/Image(contentDescription)等均已接入。

---

## 10. 文本功能区其它占位

- **选区手柄 / 光标手柄**:`TextFieldSelectionHandle` 等已在 T.8 移除(MC EditBox 无手柄);
  `SelectionHandles.kt` 依赖 Popup 手柄逻辑仍保留于移植源码(不可见)。
- **智能选区 / 拼写**:`rememberPlatformSelectionBehaviors` 用系统 LocaleList;细节未验证。
- **放大镜**:`TextFieldMagnifier.kt` draw 为空 `{}`(无触摸放大镜)。

---

## 11. 完全不需要 / 可忽略(非本平台缺漏,勿实现)

以下代码是从 CMP/AOSP 移植时**连带拷贝进来的上游机制**,与「仅在 MC 帧上运行 Compose、
不嵌入任何原生窗口/视图」的本平台无关:

### 11.1 `androidx.compose.ui.viewinterop` 整个包 —— **完全不需要(Android 原生 View 互操作)**

- `InteropView` / `InteropViewGroup` / `InteropViewHolder` / `InteropContainer` /
  `InteropViewFactoryHolder` / `InteropPointerInput`。
- **运行时永不触发**:`LayoutNode.getInteropView()` 恒返回 null;`CanvasLayersComposeScene.hitTestInteropView`
  永远返回 null。
- 处置:**不需要实现**。若要删除需连接口契约一起剥(`Owner.getInteropView()`、
  `LayoutNode.interopViewFactoryHolder`、`ComposeScene.hitTestInteropView()`、
  `FocusTransactions.kt` 里 `getInteropView() == null` 的焦点分支)。低优先级清理。

### 11.2 离屏合成 / 3D 光照 —— 按架构约束不实现

- ⚠️ `saveLayer`、`compositingStrategy`、`renderEffect` 按 AGENTS.md 约束 #4「不做离屏渲染」
  **不实现**;`GraphicsLayerScope` 对应 setter 为占位。T.21/T.22 已把 `colorFilter` 与 `blendMode`
  折进每个绘制命令(draw 级近似):colorFilter 生效;blendMode 17 种可表达模式经 `BlendPipelines`
  自建 blend pipeline 生效,12 种高级模式回退 SrcOver(设计细节见编译产物与 AGENTS.md 历史)。
- ⚠️ `GraphicsLayerOwnerLayer.setLightingInfo`(3D 光照)仍为空实现(无实际光照语义需求)。

### 11.3 系统级桌面 API —— 视宿主而定

- `DEFAULT_DENSITY`/`DrawContext` 密度占位、`DefaultHapticFeedback`(触感反馈,平台无振动)、
  `EmptyPlatformWindowInsets`(窗口 inset,MC 全屏接管)、`EmptyPlatformScreenReader`
  (MC 复述已自实现)、`uriHandler`(打开外链,可接 MC/系统或留空)。
- **已删除**:`androidx.compose.ui.autofill.*`(Android 自动填充)与 autofill 语义键、
  高亮、菜单项已在重构中整体移除(`fc9007b`),不再存在。

> 判断准则:标注 **`第一版不支持`** 的是「缺能力、可能要补」;标 **`占位`** 且所属功能平台本就不支持
> (原生视图/系统弹窗/触感/无障碍宿主)的是「可忽略或删除」,不需要实现。

---

## 12. 上游继承的 TODO(非平台缺漏,节选)

以下均为从 CMP/AOSP 移植源码中保留的 `TODO(b/…)` / `TODO(demin)…` / `TODO(lmr)…`,
**不代表本平台尚未实现**,实现进度以 §1-§10 为准:

- `ComposeUiFlags.kt` / `ComposeFoundationFlags.kt` 的一批 `TODO(b/…)` 功能开关。
- `DefaultHapticFeedback.kt` 的 TODO。
- `InteropView* / DragAndDropNode` 的 b/303904810 等。
- `FocusTraversal/TwoDimensionalFocusSearch` 的移动焦点顺序 TODO。
- `VelocityTracker`(b/204895043)等性能 TODO。
- 大量 `TODO` 出现在 `ui/semantics`、`ui/scene`、`animation`、`foundation/lazy` 中,均为上游注释。

---

## 13. 后续路线建议(未实现项,按优先级)

1. **InlineContent / 占位符**:需要先打通 `recordTextDraw` 之外的占位矩形绘制
   (`MinecraftParagraph.placeholder…` 目前返回空)。
2. **段级字号(scale)**:富文本段级字号暂不支持(布局统一 base scale),需扩展
   `recordSegmentedTextDraw` 为每段独立字号矩阵。
3. **双击**:`mouseClicked` 的 doubleClick 语义与间隔判定(目前固定传 false)。
4. **系统拖放(Drag & Drop)**:接 `EmptyDragAndDropManager`(MC 无原生拖拽,可探索内部拖拽手势
   `draggable`/`detectDragGestures` 已可用;系统级 OS 拖放需平台桥)。
5. **`DropdownMenu`**:未移植(依赖 Popup + 焦点层级基建已齐,可仿官方实现补上)。
6. **BiDi / 排版方向**:固定 LTR,需要多方向文本时再评估。
7. **明确不做**:软键盘(MC 桌面 IME 由系统输入法负责)、触摸/多指/触控笔(纯鼠标 GUI)、
   离屏合成家族(§11.2)、动画库动效(`dialog` 开合动画等,未经受控验证)。