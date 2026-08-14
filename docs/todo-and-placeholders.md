# Compose-Minecraft 未实现 / 占位代码清单

> 生成日期:2026-08
> 范围:`common/src/main`(平台核心 + 移植的 `androidx.compose.*` 运行时)与 `common/src/devOnly`(开发测试场景)。
> 约定:标注 **`第一版不支持`** 的代码在运行时抛 `UnsupportedOperationException`;**`占位`** 的实现返回空/无操作。
> 说明:移植源码中还残留大量上游(CMP/AOSP)的 `TODO(b/…)` 注释,属于**继承自上游的回填提示**,非本平台实际缺漏,本文档单独列出,不逐条展开。

---

## 0. 摘要(现状总览)

平台侧基础能力(场景宿主、Screen 桥接、文本/裁剪/滚轮/聚焦/光标/输入管线、系统剪贴板)已经可用并有开发场景验证。以下能力**尚未实现或为占位**:

| 类别 | 状态 | 说明 |
|---|---|---|
| 图形绘制命令 | 部分实现 | 矩形/圆角矩形/文本可用;圆/椭圆/弧/线/路径/点/图片/顶点 未接入 MC 渲染后端 |
| 图层能力 | 部分 | clip/scissor、translate/scale/rotate/alpha 已通;`saveLayer`、`clipPath`、`clipRect(Difference)`、Path(复合) 不支持 |
| 文本 | 部分 | 统一 `McTextStyle`,MC 字体度量;富文本/多 SpanStyle、BiDi、InlineContent/占位符、TextAutoSize 不支持;字号固定 9px |
| 弹窗 | 局限 | `Popup` 部分可用;`Dialog` 未移植;Popup/Dialog 焦点层级未通 |
| 焦点/事件 | 部分 | 键盘/鼠标/滚轮/聚焦已通;双击、拖放、触摸、指针图标、IME preedit 组合态提示 未实现或占位 |
| 平台 API | 居多占位 | 文本输入服务、文本工具栏、无障碍、窗口 inset、触感反馈、软键盘、URI、剪贴板(已接 MC 系统)等。其中多数见 §11「可忽略」,含触感/inset/URI/无障碍 |
| 互操作视图 | 占位(可删) | 无原生视图嵌入,`InteropView` 以 `Any` 占位 —— **Android 原生 View 机制,完全不需要,见 §11.1** |
| 无障碍/无障碍辅助 | 占位(可忽略) | 屏幕阅读器为空实现,平台无无障碍宿主,见 §11 |

---

## 1. 渲染 / 图形(`androidx.compose.ui.graphics`)

### 1.1 `MinecraftCanvas`(`ui/graphics/MinecraftPlatform.kt`)
命令记录式画布。`MinecraftRenderContext` 只回放下列绘制类型,其余在回放时被静默跳过(见 §6):

- ✅ 矩形 `drawRect`
- ✅ 圆角矩形 `drawRoundRect`(仅当 `radiusX/radiusY <= 1` 时退化为矩形;带圆角的矩形需要三角化,未实现——`MinecraftRenderContext.kt` L50-61)
- ✅ 文本 `drawText`
- ❌ 圆 `drawCircle` / 椭圆 `drawOval` / 弧 `drawArc` / 线 `drawLine` / 路径 `drawPath` / 点 `drawPoints` / 图片 `drawImageRect` —— **命令会记录,但 `MinecraftRenderContext` `else -> Unit` 直接丢弃,不渲染**

平台侧明确抛 `UnsupportedOperationException` 的 API:
- `asFrameworkPaint`(L65)
- `Path.op(operation)` 路径布尔运算(L363)
- `PathMeasure.getSegment` / `getPosition` / `getTangent`(L465-478)
- `saveLayer`(L822,不允许离屏图层)
- `clipRect(ClipOp.Difference)`(L883)
- `clipPath`(L893)
- `drawVertices`(L1004)
- `Paint.shader`(L1048)— 渐变/纹理 paint 不支持
- `Paint.blendMode` 仅支持 `SrcOver`(L1051)

### 1.2 `EmptyCanvas`(`ui/graphics/drawscope/EmptyCanvas.kt`)
完整 `Canvas` 的**无操作占位实现**,所有方法抛 `UnsupportedOperationException`。用于 `DrawContext` 内部保证非空 canvas,业务代码不应触达。

### 1.3 `ImageBitmap.kt`
- `createImageBitmap(bytes)` 解码图片 → `UnsupportedOperationException("...第一版不支持图片解码")`(L245)。**无任何图片解码/上传能力**(与 `MinecraftImageBitmap` 的 CPU 像素上传到 GpuTexture 是后续阶段)。

### 1.4 `Shader.kt`(L64-151)
- `LinearGradient(RadialGradient/SweepGradient/Image/Composite)Shader` 全部抛 `UnsupportedOperationException`。**渐变不支持**,`Brush` 仅 `SolidColor` 可用(文本侧同样限制,见 §3)。

### 1.5 `PathEffect.kt`(L82-95)
- `cornerPathEffect` / `dashPathEffect` / `chainPathEffect` / `stampedPathEffect` 全部抛 `UnsupportedOperationException`。虚线/圆角路径效果不支持。

### 1.6 `GraphicsLayer`(`ui/graphics/layer/GraphicsLayer.kt`)
- `draw` 仅支持 translate/scale/rotationZ/alpha/**clip(矩形 scissor)**。
- **rotationX / rotationY(3D 透视)与 pivotOffset 暂不生效**(注释明示)。`setRectOutline`/`setPathOutline` 均为空实现(`Unit`)。
- `toImageBitmap()` 依赖 `MinecraftCanvas.image`;非图片用途路径未接。

### 1.7 `GraphicsLayerOwnerLayer.kt`(`ui/platform/`)
- `setLightingInfo`(3D 光照/阴影)为空实现——阶段 C 不渲染阴影。

### 1.8 `RenderIntent`(`graphics/colorspace/RenderIntent.kt`)
- 部分渲染意图(如 `Absolute`、`Relative` 之外的某些)注明 "currently not implemented and behaves like Relative"。

### 1.9 `CompositionLocals.platform.kt`(`ui/platform/`)
- `HostDefaultProvider` 相关 `TODO(CMP-9752)`:未完整实现,当前为占位对齐。

**后续建议**:优先实现图片解码→`MinecraftImageBitmap` 像素上传→GpuTexture→`drawImageRect`;其次是 Path/圆/椭圆/线等三角化;`saveLayer` 若业务需要可再评估离屏 RenderTarget。

---

## 2. 文本 / 排版(`androidx.compose.ui.text`)

### 2.1 `MinecraftParagraph.platform.kt`(`ui/text/platform/`)
平台唯一文本后端(MC 字体度量,行高固定 9px):
- ❌ **富文本/多样式**:只支持统一 `McTextStyle`;`AnnotatedString` 的 `SpanStyle`/`ParagraphStyle` 差异化未实现。
- ❌ **BiDi / 排版方向**:固定 `ResolvedTextDirection.Ltr`(L193)。
- ❌ **fontSize/字号**:忽略,统一 MC 原生 9px(AGENTS.md 既定)。
- ❌ **渐变 Brush**:`paint(Brush:…)` 只解析 `SolidColor`,否则回退白(L447)。
- ❌ **Text 占位符**:`getPathForRange` 返回空 `Path`(L315);`placeholderRects` 返回 `emptyList()`(L293)——InlineContent / Placeholder 排进文本不生效。
- ❌ `getRangeForRect` 返回整段 `TextRange(0, length)`(简化命中)。
- ❌ `getWordBoundary` 用简化字符类判断(字母/数字/下划线),非 ICU 单词边界。
- ⚠️ `ActualParagraph/…Intrinsics` 直接映射到 `MinecraftParagraph`,`Font.ResourceLoader` 的 `createFontFamilyResolver` 已废弃。

### 2.2 `MultiParagraphLayoutCache.kt`(`foundation/text/modifiers/`)
- ❌ **TextAutoSize**:入口已在 `layoutWithConstraints` 拦截并抛
  `UnsupportedOperationException("TextAutoSize 在 Minecraft 平台第一版不支持(MC 字号固定 9px)")`(L204, L438-443)。`BasicText(autoSize=…)` 不可用。

### 2.3 `TextPainter.kt`
- `drawText(…Brush…)` 解析为纯色;渐变/阴影/装饰按 McTextStyle 能力承载或忽略(§2.1)。

### 2.4 `Key.kt`(`ui/input/key/`)
- 顶部注释 `TODO(demin): implement most of key codes`——但桥接层 `ComposeScreen` 已提供 MC→Compose 键码映射表(`glfwKeyToComposeKey`),Compose `Key` 常量本身沿移植版。

**后续建议**:如需富文本,先扩展 `MinecraftParagraph` 支持按 Span 记录多个 DrawTextCommand(每个 Span 一个 MC `Style`);InlineContent 需要先打通 `recordTextDraw` 之外的占位矩形绘制。

---

## 3. 文本画刷 / 绘制细节

- `Brush`(渐变)`Shader` 全线不支持(见 §1.4)——平台所有绘制(`drawRect(Brush)`、文本背景等)只认 `SolidColor`。
- `Paint.shader` 读取即抛(`MinecraftPlatform.kt` L1048)。

---

## 4. 弹窗 / 焦点层级(`androidx.compose.ui.window`, `ui.scene`)

- ✅ **`Popup`**(`ui/window/Popup.kt`)可用(foundation 依赖)。
- ❌ **`Dialog` 未移植**——`ui/window` 下只有 `Popup.kt` 与 `DialogScrimBlendMode.kt`。
- ⚠️ `ComposeScreen` 头注明确:**双击、Popup/Dialog 焦点层级、IME preedit 组合态提示(候选窗口由系统输入法负责)尚未支持**。
- `CanvasLayersComposeScene` 中有 Popup/Dialog `focusedLayer`/`isInteractive` 相关分支,但多层焦点切换未完整验证。

**后续建议**:若要 Dialog,需参照 CMP `Dialog.skiko.kt` 移植 `ui/window/Dialog.kt`(含 scrim 层与焦点),并打通 `CanvasLayersComposeScene` 的图层焦点分发;`getDialogScrimBlendMode` 已单独抽出待用。

---

## 5. 平台服务 / `PlatformContext`(`androidx.compose.ui.platform`)

`PlatformContext.Empty`(MinecraftComposeScene 传入)使用以下**空/占位**默认实现:

| 成员 | 实现 | 处置 |
|---|---|---|
| `textInputService` | `EmptyPlatformTextInputService`(startInput/stopInput/showKeyboard… 全 `Unit`) | 不依赖(走 charTyped 管线),保留;`PlatformContext.kt` L289 |
| `textToolbar` | `EmptyTextToolbar`(showMenu 空) | 视业务(文本粘贴菜单)再补;`L303` |
| `screenReader` | `EmptyPlatformScreenReader`(`isActive=false`) | **可忽略**,见 §11;`L262` |
| `dragAndDropManager` | `EmptyDragAndDropManager` | 视业务(拖放);`L315` |
| `windowInsets` | `EmptyPlatformWindowInsets` | **可忽略**,见 §11;`PlatformWindowInsets.platform.kt` L86 |
| `parentFocusManager` | `EmptyFocusManager`(clearFocus/moveFocus 空/返回 false) | 平台无父宿主,保留占位;`PlatformContext.kt` L256 |
| `hapticFeedback` | `DefaultHapticFeedback.performHapticFeedback` **空实现**(`// TODO(demin): implement HapticFeedback`) | **可忽略**,见 §11;`ui/platform/DefaultHapticFeedback.kt` |
| `AccessibilityManager` | `DefaultAccessibilityManager`(基本枚举) | **可忽略**,见 §11 |
| 剪贴板 | ✅ **已实现**:`ui/platform/PlatformClipboard.kt` 经 `Minecraft.keyboardHandler.get/setClipboard()` 接系统剪贴板 | — |
| `PointerIcon` | 占位(`PointerIcon.platform.kt`:语义仅用于内部状态区分,不触发系统光标变化) | 视业务(需定制系统光标再补) |
| `uriHandler` | `createPlatformUriHandler`(见 RootNodeOwner/Wrapper) | 视业务(打开外链)再接 |

**后续建议**:文本输入走「charTyped→typed KeyEvent」管线,不依赖 `textInputService`(既定方案);如需剪贴板粘贴菜单等再完善 `textToolbar`。

---

## 6. `MinecraftRenderContext`(`moe/forpleuvoir/compose_minecraft/minecraft/`)

回放画布命令到 `GuiRenderState`:
- ✅ `DrawRectCommand`、`DrawRoundRectCommand(radius<=1)`、`DrawTextCommand`
- ❌ `DrawOvalCommand / DrawCircleCommand / DrawArcCommand / DrawLineCommand / DrawPathCommand / DrawPointsCommand / DrawImageRectCommand`
  —— 命令已记录,但回放 `else -> Unit` **不渲染**(需三角化 / 图片上传,注释明示后续阶段)。
- ⚠️ IME preedit 组合态(候选框)未做,由系统输入法负责。

---

## 7. 输入 / 事件(`androidx.compose.ui.input.*`, `ComposeScreen`)

- ✅ 鼠标:click/release/move/drag/scroll 已转发;滚轮 27px/格(MC 语义 3 行)。
- ✅ 键盘:keyPressed/Released、charTyped(含中文 IME 上屏 → typed KeyEvent)已通。
- ❌ **双击**:`ComposeScreen.mouseClicked` 传 `doubleClick=false` 路径未验证/未实现。
- ❌ **触摸 / 多指 / 触控笔**:`PointerButton`/`PointerType` 只覆盖鼠标;`PointerEvent.platform.kt` 注释 `TODO(CMP-2184) support more buttons`。⚠️ 纯鼠标 GUI 场景通常用不到,视业务决定。
- ❌ **拖放(Drag & Drop)**:`ComposeSceneDragAndDropNode` 存在,但 `PlatformContext.Empty.dragAndDropManager` 为空实现;系统拖放未接。⚠️ 视业务决定。
- ❌ **旋转 Pane/Pan/Scale/手势**:`CanvasLayersComposeScene` 有分支但无 MC 输入源驱动(无触摸屏),通常可忽略。
- ⚠️ **SoftKeyboard 拦截 / rotary**:`FocusOwnerImpl.dispatchSoftKeyboardEvent` 等有 TODO("...to embedded views")。

---

## 8. 互操作视图(`androidx.compose.ui.viewinterop`)—— 完全不需要

- `InteropView` / `InteropViewHolder` / `InteropContainer` 以 `Any` 占位(Minecraft 不嵌入任何原生视图,移除 AWT 依赖)。
- `InteropViewFactoryHolder` 抛 `NotImplementedError("Abstract … must be implemented by platform-specific subclass")`。
- 这是 **Android 原生 View-Compouse 混编机制被连带拷贝的产物**,运行时永不触发(见 **§11.1**),无需实现;后续可连接口契约整体剥离(低优先级清理)。

---

## 9. 无障碍 / 其他 —— 可忽略(见 §11)

- `EmptyPlatformScreenReader.isActive=false` → 无障碍辅助不可用(**无无障碍宿主,可忽略,见 §11**)。
- `AccessibilityManager` 为最简枚举(**可忽略**)。
- `ui/autofill/*`:`Autofill/ContentType/ContentDataType/AutofillType` 等处 `TODO(CMP-7154/8576)` 未接入 —— **Android 自动填充**,可忽略,见 §11.2。

---

## 10. 文本功能区其它占位

- **选区手柄 / 光标手柄**:`TextFieldSelectionHandle` 等已在 T.8 移除(MC EditBox 无手柄);`SelectionHandles.kt` 依赖 Popup 手柄逻辑仍保留于移植源码(不可见)。
- **智能选区 / 拼写**:`rememberPlatformSelectionBehaviors` 用系统 LocaleList;细节未验证。
- **放大镜**:`TextFieldMagnifier.kt` draw 为空 `{}`(无触摸放大镜)。

---

## 11. 完全不需要 / 可忽略(非本平台缺漏,勿实现)

以下代码是从 CMP/AOSP 移植时**连带拷贝进来的上游机制**,与「仅在 MC 帧上运行 Compose、不嵌入任何原生窗口/视图」的本平台无关:

### 11.1 `androidx.compose.ui.viewinterop` 整个包 —— **完全不需要(Android 原生 View 互操作)**
- 内容:`InteropView` / `InteropViewGroup` / `InteropViewHolder` / `InteropContainer` / `InteropViewFactoryHolder` / `InteropPointerInput`。
- 定位:**Android / AWT 原生 View-Conpose 混编机制**。本平台无原生视图,`InteropView` 用 `Any` 占位(见 §8)。
- **运行时永不触发**:`LayoutNode.getInteropView()` 恒返回 null;鼠标/触摸事件里 `pointerInteropFilter` 不会命中;`CanvasLayersComposeScene.hitTestInteropView` 永远返回 null。
- 处置:**不需要实现**。若要删除需连接口契约一起剥——`Owner.getInteropView()`、`LayoutNode.interopViewFactoryHolder`、`ComposeScene.hitTestInteropView()`、`FocusTransactions.kt` 里 `getInteropView() == null` 的焦点分支。属低优先级清理,不影响功能。

### 11.2 `androidx.compose.ui.autofill.*` —— 可忽略(Android 自动填充)
- Android 系统自动填充(Autofill)服务,平台无对应宿主。`TODO(CMP-7154/8576)` 均为此。
- 处置:占位即可,无需实现。

### 11.3 阴影 / 3D 光照 —— 可忽略(绑定 Skia 阴影)
- `GraphicsLayerOwnerLayer.setLightingInfo`(3D 光照)、`DropShadowPainter`、`GraphicsLayer` 的 rotationX/rotationY 透视。
- 平台使用 MC `GuiRenderState`,不渲染阴影/透视。空实现是正确的,无需实现。

### 11.4 系统级桌面 API —— 视宿主而定
- `DEFAULT_DENSITY`/`DrawContext` 密度占位、`DefaultHapticFeedback`(触感反馈,平台无振动)、`EmptyPlatformWindowInsets`(窗口 inset,MC 全屏接管)、`uriHandler`(打开外链,可接 MC/系统或留空)。
- 处置:绝大多数保持占位即「完成」;仅当业务确实需要(如打开外链)再单独接。

> 判断准则:标注 **`第一版不支持`** 的是「缺能力、可能要补」;标 **`占位`** 且所属功能平台本就不支持(原生视图/系统弹窗/触感/无障碍)的是「可忽略或删除」,不需要实现。

---

## 12. 上游继承的 TODO(非平台缺漏,节选)

以下均为从 CMP/AOSP 移植源码中保留的 `TODO(b/…)` / `TODO(demin)…` / `TODO(lmr)…`,**不代表本平台尚未实现**,实现进度以 §1-§10 为准:
- `ComposeUiFlags.kt` / `ComposeFoundationFlags.kt` 的一批 `TODO(b/…)` 功能开关。
- `DefaultHapticFeedback.kt` 的 TODO。
- `InteropView* / DragAndDropNode` 的 b/303904810 等。
- `FocusTraversal/TwoDimensionalFocusSearch` 的移动焦点顺序 TODO。
- `VelocityTracker`(b/204895043)等性能 TODO。
- 大量 `TODO` 出现在 `ui/semantics`、`ui/scene`、`animation`、`foundation/lazy` 中,均为上游注释。

---

## 13. 后续路线建议(按优先级)

1. **图片**:`createImageBitmap` 解码 → `MinecraftImageBitmap` → GpuTexture 上传 → 回放 `drawImageRect`。
2. **几何绘制**:Path(填充/描边)→ 三角化 → 圆/椭圆/弧/线/点;`drawPath` 与选区高亮、圆角矩形真正圆角。
3. **Dialog + Popup 焦点层级**:移植 `Dialog`,打通多图层焦点/键盘分发。
4. **富文本**:按 SpanStyle 分条 `DrawTextCommand`,走多 MC `Style`;再考虑 InlineContent 占位矩形。
5. **输入补全**:双击、拖放(接 MC 或系统)、软键盘事件、可选的指针图标/系统光标。
6. **TextAutoSize**:在固定 9px 前提下,可评估按约束多档缩放字形(需变更排版模型)。
7. **无障碍**:screenReader 接入 MC 的 Toast/讲稿或跳过。
