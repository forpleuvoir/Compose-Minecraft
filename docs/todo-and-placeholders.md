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
| 图形绘制命令 | ✅ 已实现 | 矩形/圆角矩形/圆/椭圆/弧/线/路径/点/文本/图片/顶点渐变均已接入 MC 渲染后端(图片 T.16、顶点 T.23);`drawVertices` 已支持 |
| 图层能力 | 部分 | clip/scissor、translate/scale/rotate/alpha 已通;`saveLayer`、`clipPath`、`clipRect(Difference)`、Path(复合) 不支持 |
| 文本 | 部分 | 统一 `BasicText(style: TextStyle)`(T.28,语义经 TextStyleMapper 映射,MC 原版渲染特性经 PlatformSpanStyle 承载);`fontSize` 并入 style(T.19,18sp=2x 平台基准字号),`autoSize` 自动缩放(T.20);段级 SpanStyle 混排(T.29)、BiDi、InlineContent/占位符 不支持;基线行高 9px。默认字体/默认样式/默认字号均可经 CompositionLocal 覆盖(`LocalDefaultFont`/`LocalDefaultTextStyle`/`LocalDefaultFontSize`,T.30/T.32);自定义字体文件注册(T.32,FreeType 加载 ttf/otf/ttc) |
| 弹窗 | 局限 | `Popup` 部分可用;`Dialog` 未移植;Popup/Dialog 焦点层级未通 |
| 焦点/事件 | 部分 | 键盘/鼠标/滚轮/聚焦已通;IME preedit 组合态已实现(下划线组合文本 + 候选窗跟随);指针图标已实现(I9:Compose `PointerIcon` → MC 原版 `CursorTypes`,经原版 per-frame 光标管线);双击、拖放、触摸 未实现或占位 |
| 平台 API | 居多占位 | 文本输入服务、文本工具栏、无障碍、窗口 inset、触感反馈、软键盘、URI、剪贴板(已接 MC 系统)等。其中多数见 §11「可忽略」,含触感/inset/URI/无障碍 |
| 互操作视图 | 占位(可删) | 无原生视图嵌入,`InteropView` 以 `Any` 占位 —— **Android 原生 View 机制,完全不需要,见 §11.1** |
| 无障碍/无障碍辅助 | 占位(可忽略) | 屏幕阅读器为空实现,平台无无障碍宿主,见 §11 |

---

## 1. 渲染 / 图形(`androidx.compose.ui.graphics`)

### 1.1 `MinecraftCanvas`(`ui/graphics/MinecraftPlatform.kt`)
命令记录式画布。`MinecraftRenderContext` 只回放下列绘制类型,其余在回放时被静默跳过(见 §6):

- ✅ 矩形 `drawRect`
- ✅ 圆角矩形 `drawRoundRect`(圆角 > 1px 走 `GeometryTessellator.roundRect` 三角化)
- ✅ 文本 `drawText`
- ✅ 圆 `drawCircle` / 椭圆 `drawOval` / 弧 `drawArc` / 线 `drawLine` / 路径 `drawPath` / 点 `drawPoints` / 图片 `drawImageRect` / 顶点 `drawVertices` —— 几何命令经 `GeometryTessellator` CPU 三角化提交;图片(T.16)经 `MinecraftImageTextureCache` 上传 GpuTexture 后以带 UV 的 `BlitRenderState(GUI_TEXTURED)` 提交;顶点渐变(T.23)按 vertexMode(Triangles/Strip/Fan)+ 索引展开,每顶点色 0xAARRGGBB 走 `GuiTriangleRenderState.vertexColors`(POSITION_COLOR_LINE_WIDTH 每顶点色,GPU 插值),纹理坐标忽略(平台 GUI shader 无纹理),blendMode 独立优先于 paint

平台侧明确抛 `UnsupportedOperationException` 的 API:
- `asFrameworkPaint`(L65)
- `Path.op(operation)` 路径布尔运算(L363)
- `PathMeasure.getSegment` / `getPosition` / `getTangent`(L465-478)
- `saveLayer`(L822,不允许离屏图层)
- `clipRect(ClipOp.Difference)`(L883)
- `clipPath`(L893)
- `drawVertices`(L1004)
- `Paint.shader`(L1048)— 渐变/纹理 paint 不支持
- `Paint.blendMode` 仅支持 `SrcOver`(L1051)— 记录端校验已放宽(T.22 渲染端按模式选 blend pipeline,超出 17 种可表达范围回退 SrcOver)

### 1.2 `EmptyCanvas`(`ui/graphics/drawscope/EmptyCanvas.kt`)
完整 `Canvas` 的**无操作占位实现**,所有方法抛 `UnsupportedOperationException`。用于 `DrawContext` 内部保证非空 canvas,业务代码不应触达。

### 1.3 `ImageBitmap.kt`
- ✅ `createImageBitmap(bytes)` 已实现(T.16):MC `NativeImage.read(byte[])`(stb 解码 PNG/JPEG)→ ABGR 转 Compose Argb8888(0xAARRGGBB)。
- 上传:`MinecraftImageBitmap`(CPU 像素)由渲染端 `MinecraftImageTextureCache` 在渲染线程按需上传为 GpuTexture(位图身份 LRU 缓存,上限 64,淘汰 close),绘制经带 UV 的 `BlitRenderState`。

### 1.4 `Shader.kt`(L64-151)
- `LinearGradient(RadialGradient/SweepGradient/Image/Composite)Shader` 全部抛 `UnsupportedOperationException`。**渐变不支持**,`Brush` 仅 `SolidColor` 可用(文本侧同样限制,见 §3)。

### 1.5 `PathEffect.kt`(L82-95)
- `cornerPathEffect` / `dashPathEffect` / `chainPathEffect` / `stampedPathEffect` 全部抛 `UnsupportedOperationException`。虚线/圆角路径效果不支持。

### 1.6 `GraphicsLayer`(`ui/graphics/layer/GraphicsLayer.kt`)
- ✅ translate/scale/rotationZ/rotationX/rotationY(3D 透视,T.15)/pivot/alpha/clip(矩形 scissor)/shadowElevation(T.14 GPU 距离场软阴影)均已实现。
- ✅ `setRectOutline` / `setRoundRectOutline` / `setPathOutline` 已实现(T.14 阴影轮廓)。
- ✅ `toImageBitmap()`(T.17):CPU 光栅化器(`GraphicsLayerRasterizer`,与 GPU 回放同一套三角化)把录制命令光栅化到像素缓冲,任意线程可调用;**文本与阴影命令不支持,快照中缺失**。
- ✅ `colorFilter`(T.21,draw 级近似):`ColorFilter.colorMatrix`(4x5 颜色矩阵)与 `BlendModeColorFilter`(tint,按 blendMode 与自身底色 SrcIn/SrcOver/Modulate 合成)/ `LightingColorFilter`(multiply+add)经 `NativeColorFilter` 透传到每个绘制命令,渲染端 `applyColorFilter` 对最终色应用;图片(drawImageRect 恒白调制)与文本(style 色)不经滤镜。
- ✅ `blendMode`(T.22):17 种可表达模式经 `BlendPipelines`(platform/render/)自建 blend pipeline 逐命令生效 —— Porter-Duff 12 种(Clear/Src/Dst/SrcOver/DstOver/SrcIn/DstIn/SrcOut/DstOut/SrcAtop/DstAtop/Xor)+ 数学混合 5 种(Plus/Modulate/Screen/Darken(逐通道 MIN 近似)/Lighten(MAX 近似)),直通 alpha;MC 26.2 blend 函数在 pipeline 编译期固定,故每个模式一个 pipeline 变体(gui_blend_* / gui_triangles_blend_*,与官方 GUI_INVERT=GUI_SNIPPET+INVERT 同思路,不注册直接使用)。生效范围:纯色几何(矩形 blit + 三角化,含 3D 路径);**图片(drawImageRect)、文本(style 色)、阴影、渐变保持 TRANSLUCENT**。另 12 种高级模式(Overlay/Hardlight/Softlight/ColorDodge/ColorBurn/Difference/Exclusion/Multiply/Hue/Saturation/Color/Luminosity)需 GL_KHR_blend_equation_advanced 或 shader 方案,**回退 SrcOver**。已知 draw 级限制:Clear/SrcOut/DstOut 等「输出≈0」模式直接写 0 到主帧缓冲显示为黑(离屏层上才表现为透明)。
- 未实现:`compositingStrategy` / `renderEffect`(离屏合成家族,属性链路完整但渲染端不消费),依赖离屏渲染,见 §11.3。

### 1.7 `GraphicsLayerOwnerLayer.kt`(`ui/platform/`)
- `setLightingInfo`(3D 光照)为空实现;阴影本身已由 `GraphicsLayer.drawShadow` + `MinecraftShadowRenderer` 实现(T.14),不依赖该接口。

### 1.8 `RenderIntent`(`graphics/colorspace/RenderIntent.kt`)
- 部分渲染意图(如 `Absolute`、`Relative` 之外的某些)注明 "currently not implemented and behaves like Relative"。

### 1.9 `CompositionLocals.platform.kt`(`ui/platform/`)
- `HostDefaultProvider` 相关 `TODO(CMP-9752)`:未完整实现,当前为占位对齐。

**后续建议**:离屏合成(saveLayer/compositingStrategy/renderEffect)依赖离屏渲染能力,暂不实现;`toImageBitmap` 已由 CPU 光栅化器支撑(T.17,含顶点渐变命令 T.23,见 §13)。

---

## 2. 文本 / 排版(`androidx.compose.ui.text`)

### 2.1 `MinecraftParagraph.platform.kt`(`ui/text/platform/`)
平台唯一文本后端(MC 字体度量,行高固定 9px):
- ⚠️ **富文本段级混排**(T.29 ✅,`AnnotatedString` 版 `BasicText` 已提升 public):
  `spanStyles` 经 `TextStyleMapper.toStyleSegments` 切分为**全覆盖** `StyleSegment`
  (段样式增量叠加 base MC Style;段间无样式覆盖文本走默认样式;同区间多 span
  后声明优先),经 `BasicText → textModifier → Element → Node → MultiParagraphLayoutCache
  → MultiParagraphIntrinsics → ParagraphIntrinsics → MinecraftParagraph.recordSegmentedTextDraw`
  逐段绘制。段级支持 color/bold/italic/decoration/PlatformSpanStyle(MC 特性);
  **段级字号(scale)暂不支持**(布局统一 base scale);InlineContent 占位符仍未实现。
- ❌ **BiDi / 排版方向**:固定 `ResolvedTextDirection.Ltr`(L193)。
- ✅ **fontSize/字号**(T.19/T.28):`BasicText(style = TextStyle(fontSize = …))` 以 sp 驱动,
  **18sp = 2x 平台基准字号**(9sp = 1x 原生像素,16sp ≈ 1.78x 非整数缩放、非自然字号)
  (MC 无原生字号系统,经渲染矩阵缩放:布局尺寸与字形矩阵同步缩放;仅支持 sp,
  em 抛 `IllegalArgumentException`)。旧的 `fontSize` 独立参数与 `scale: Float = 1f` 已移除。
- ✅ **TextStyle 语义映射**(T.28,`platform/ui/text/TextStyleMapper.kt`):`TextStyle → {MC Style,
  scale, alpha}` —— color(只取 RGB,alpha 走渲染)/alpha(经 recordTextDraw 命令 alpha 合成)/
  fontWeight(≥600 加粗)/fontStyle(斜体)/textDecoration(下划线/删除线)生效;
  **`PlatformSpanStyle` 承载 MC 原版 Style 全部渲染特性**(obfuscated/shadowColor/
  clickEvent/hoverEvent/insertion/font,双向 `Style.toTextStyle()` 不丢失);
  其余字段(letterSpacing/background/shadow/lineHeight/textAlign/fontFamily/…)
  收集进 `PlatformTextData.ignored` 文档化忽略。已知边界:selection/onTextLayout/autoSize
  的 textModifier 分支暂不消费 scale/alpha(纯绘制分支生效)。
- ❌ **渐变 Brush**:`paint(Brush:…)` 只解析 `SolidColor`,否则回退白(L447)。
- ❌ **Text 占位符**:`getPathForRange` 返回空 `Path`(L315);`placeholderRects` 返回 `emptyList()`(L293)——InlineContent / Placeholder 排进文本不生效。
- ❌ `getRangeForRect` 返回整段 `TextRange(0, length)`(简化命中)。
- ❌ `getWordBoundary` 用简化字符类判断(字母/数字/下划线),非 ICU 单词边界。
- ⚠️ `ActualParagraph/…Intrinsics` 直接映射到 `MinecraftParagraph`,`Font.ResourceLoader` 的 `createFontFamilyResolver` 已废弃。

### 2.2 `MultiParagraphLayoutCache.kt`(`foundation/text/modifiers/`)
- ✅ **TextAutoSize**(T.20):`BasicText(autoSize = TextAutoSize.StepBased(...))` 二分搜索最大适配字号
  (默认 12–112sp、步进 0.25sp,官方 `AutoSizeStepBased` 算法原样)。
  字号经渲染 scale 驱动(sp → px 含 fontScale,16sp = scale 1f):`TextAutoSizeLayoutScopeImpl.toPx/performLayout`
  实现(performLayout 用局部 intrinsics 布局,不污染主缓存);`MultiParagraphIntrinsics` 透传 scale,
  `setLayoutDirection` 缓存键含 scale 分量。仅 sp 单位(em 抛 `IllegalArgumentException`)。

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
- ⚠️ `ComposeScreen` 头注明确:**双击、Popup/Dialog 焦点层级尚未支持;IME 候选窗由系统输入法负责(preedit 组合态已实现)**。
- `CanvasLayersComposeScene` 中有 Popup/Dialog `focusedLayer`/`isInteractive` 相关分支,但多层焦点切换未完整验证。

**后续建议**:若要 Dialog,需参照 CMP `Dialog.skiko.kt` 移植 `ui/window/Dialog.kt`(含 scrim 层与焦点),并打通 `CanvasLayersComposeScene` 的图层焦点分发;`getDialogScrimBlendMode` 已单独抽出待用。

---

## 5. 平台服务 / `PlatformContext`(`androidx.compose.ui.platform`)

`PlatformContext.Empty`(MinecraftComposeScene 传入)使用以下**空/占位**默认实现:

| 成员 | 实现 | 处置 |
|---|---|---|
| `textInputService` | ✅ **已实现**:`MinecraftTextInputService`(IME:preedit 组合态 → EditCommand、候选窗 `setTextInputArea` 像素直传、组合期按键隔离),`MinecraftComposeScene` 持有 | 见 §7 |
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

**后续建议**:文本输入管线 = 「charTyped→typed KeyEvent」上屏 + 「preeditUpdated→MinecraftTextInputService」组合态,已按 `input-mc-native-plan.md` 落地;如需剪贴板粘贴菜单等再完善 `textToolbar`。

---

## 6. `MinecraftRenderContext`(`moe/forpleuvoir/compose_minecraft/platform/render/`)与独立渲染工作流(T.24)

**渲染架构(T.24,用户拍板)**:Compose 内容不再进原版 `GuiRenderState` 节点树,而是走**完全独立的渲染工作流**:

- `MinecraftRenderContext.render(canvas, sink: GuiCommandSink)` 回放画布命令到 `GuiCommandSink`(`interface { addElement(GuiElementRenderState); addText(GuiTextRenderState) }`),由 `ComposeGuiRenderer`(`platform/render/ComposeGuiRenderer.kt`,public class,仿 `GuiRenderer` 结构:prepare → `StagedVertexBuffer.upload()` → draw → endDraw/endFrame)实现:
  - **投影 = 像素 1:1**:`setupOrtho(1000, 11000, window.width, window.height, true)`,丢弃原版 `guiScale`(场景尺寸 `resize(windowState.width, windowState.height)`,`ComposeScreen` 输入经 `toPixels(x) = x * guiScale()` 乘回像素);
  - **裁剪精度**:`enableScissor` 像素直传(`renderPass.enableScissor(left, window.height - bottom, w, h)`,四边对窗口钳制、退化矩形 disableScissor)—— 消除原版 `×guiScale 后 (int) 截断` 的精度丢失;
  - **排序**:阴影(`GuiShadowRenderState`)排最前,其余保持记录顺序 —— 取代原 `GuiRenderStateMixin` @Redirect 置底 hack(**该 mixin 已删除**,`compose_minecraft.mixins.json` 现为 `["GameRendererMixin", "StyleAccessor"]`);
  - **挂载**:`GuiRendererMixin`(`common/src/main/java/.../mixin/GuiRendererMixin.java`)`@Inject(method="render", at=@At(value="INVOKE", target="...GuiRenderer;draw()V", shift=At.Shift.AFTER))` 帧钩子 —— 原版 GUI(遮罩/HUD/toasts)画完后提交 `ComposeGuiRenderer.active` 内容,Compose 画在最上层(ComposeScreen 空实现 `extractBackground`,原版 HUD 实测不产生元素,故「最上层」即「唯一 GUI 层」);原版 `guiRenderer` 继续提交 HUD/toasts,**共存不替换**(T.24 用户拍板,解除 AGENTS.md「无帧钩子 mixin」约定)。**注入点演进(每轮用户实测驱动)**:① guiRenderer.render()V 之前 → Compose 被原版 GUI 盖住;② draw() 第二个 executeDrawRange(after-blur 段)之前(层级 = before-blur < Compose < HUD)→ 依赖原版 draws 非空,遮罩移除后 draws 全空、注入不触发、Compose 不渲染;③ 现版 render() 的 draw() 调用点 AFTER —— 不依赖原版 draws 状态,必触发;
  - 文本复用原版 `GlyphRenderState`(`text.ensurePrepared().visit(GlyphVisitor)`),提交时 merge 同 pipeline/scissor/textureSetup 的 draw(`StagedVertexBuffer.appendDraw` + `getVertexBuilder`),索引自动(`RenderSystem.getSequentialBuffer`);
  - render pass:绑定 `mainRenderTarget.getColorTextureView()` / `getDepthTextureView()`,`RenderSystem.bindDefaultUniforms` + `DynamicTransforms`(0,0,-11000),与主帧缓冲同目标。

回放命令清单:
- ✅ `DrawRectCommand` / `DrawRoundRectCommand` / `DrawTextCommand` / `DrawOvalCommand` / `DrawCircleCommand` / `DrawArcCommand` / `DrawLineCommand` / `DrawPathCommand` / `DrawPointsCommand`(几何三角化)/ `DrawGradientRectCommand` / `DrawShadowCommand`(T.14)/ `DrawImageRectCommand`(T.16,纹理 blit)/ `DrawVerticesCommand`(T.23,每顶点色)。
- 3D 命令(`layer3D` 非 null)走 CPU 顶点透视变换路径(T.15);文本/阴影/渐变/图片降级 2D 仿射近似。
- ✅ IME preedit 组合态(候选框)已做:`MinecraftTextInputService`(preedit → SetComposingTextCommand 下划线组合文本 + `setTextInputArea` 候选窗像素直传 T.31),候选窗由系统输入法负责。

---

## 7. 输入 / 事件(`androidx.compose.ui.input.*`, `ComposeScreen`)

- ✅ 鼠标:click/release/move/drag/scroll 已转发;滚轮 54px/格(T.32,对齐官方桌面 ≈53px/格,原 27px = MC 3 行语义)。
- ✅ 键盘:keyPressed/Released、charTyped(含中文 IME 上屏 → typed KeyEvent)、IME preedit 组合态(preeditUpdated → MinecraftTextInputService)已通。
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

### 11.3 离屏合成 / 3D 光照 —— 已实现或按架构约束不实现
- ✅ 阴影(T.14 GPU 距离场软阴影,`Modifier.shadow` / `shadowElevation`)与 3D 透视(T.15 rotationX/rotationY)已实现。
- ⚠️ `GraphicsLayerOwnerLayer.setLightingInfo`(3D 光照)仍为空实现(无实际光照语义需求)。
- ⚠️ 离屏合成家族(`saveLayer`、`compositingStrategy`、`renderEffect`)按 AGENTS.md 约束 #4「不做离屏渲染」**不实现**;`GraphicsLayerScope` 对应 setter 为占位。T.21/T.22 已把 `colorFilter` 与 `blendMode` 折进每个绘制命令(draw 级近似):colorFilter 生效;blendMode 17 种可表达模式经 `BlendPipelines` 自建 blend pipeline 生效,12 种高级模式回退 SrcOver(见 §1.6)。

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

1. ~~**图片**:`createImageBitmap` 解码 → `MinecraftImageBitmap` → GpuTexture 上传 → 回放 `drawImageRect`~~ —— ✅ 已完成(T.16,`MinecraftImageTextureCache` + NativeImage 解码)。
2. ~~**CPU 光栅化器**:`GraphicsLayer.toImageBitmap()` 依赖(图层内容 → 位图快照);无离屏渲染下的替代方案~~ —— ✅ 已完成(T.17,`GraphicsLayerRasterizer`;文本/阴影命令不支持)。
3. ~~**颜色滤镜 / 混合模式**(draw 级近似,不依赖离屏)~~ —— ✅ 已完成(T.21 `colorFilter` ColorMatrix/tint/lighting;T.22 `blendMode` 17 种可表达模式经 `BlendPipelines` 自建 blend pipeline,12 种高级模式回退 SrcOver,详见 §1.6)。
4. ~~**顶点渐变 `drawVertices`**~~ —— ✅ 已完成(T.23,每顶点色 GPU 插值,Triangles/Strip/Fan + 索引展开)。
5. ~~**独立渲染工作流**(像素 1:1 投影、像素级裁剪精度、与 HUD 共存)~~ —— ✅ 已完成(T.24,`ComposeGuiRenderer` + `GuiRendererMixin`,取代 `GuiRenderStateMixin`,详见 §6)。
6. **Dialog + Popup 焦点层级**:移植 `Dialog`,打通多图层焦点/键盘分发。
7. ~~**富文本段级混排**~~ —— ✅ 已完成(T.29,`AnnotatedString` 版 `BasicText` 提升 public,
   `spanStyles` 经 `toStyleSegments` 全覆盖切分 + 段样式映射 → `recordSegmentedTextDraw`
   逐段绘制;段级 color/bold/italic/decoration/PlatformSpanStyle 生效,段级字号暂不支持;
   待做:InlineContent 占位矩形)。
8. ~~**IME preedit 组合态**~~ —— ✅ 已完成(阶段 1-3,`MinecraftTextInputService` + 候选窗 T.31,
   见 `input-mc-native-plan.md`);~~**指针图标(I9)**~~ —— ✅ 已完成(Compose `PointerIcon`
   按 `MinecraftPointerIconKind` 映射 MC 原版 `CursorTypes`,经 `extractRenderState` →
   `GuiGraphicsExtractor.requestCursor` 走原版 per-frame 管线,尊重原版「允许光标变化」
   设置项,无新 mixin);剩余输入补全:双击、拖放(接 MC 或系统)、软键盘事件。
9. ~~**TextAutoSize**:二分搜索最大适配字号(默认 12–112sp)~~ —— ✅ 已完成(T.20,`MultiParagraphLayoutCache` 搜索 + 渲染 scale 驱动)。
10. **无障碍**:screenReader 接入 MC 的 Toast/讲稿或跳过。
11. ~~**默认字体/默认样式 CompositionLocal + 系统字体读取**~~ —— ✅ 已完成(T.30/T.31/T.32):
    - T.30 `LocalDefaultFont`(默认 minecraft:default)/`LocalDefaultTextStyle`,MC 内置字体清单
      `MinecraftFonts`(Default/Alt/UniFont/IllagerAlt/Missing,经 `FontDescription.Resource` 引用);
    - T.31 IME 候选窗位置修复(`setTextInputArea` 内部乘 guiScale,场景像素化后先除回再传);
    - T.32 自定义字体注册(`MinecraftCustomFonts` + `rememberCustomFont`)、字号 Local
      (`LocalDefaultFontSize`)
