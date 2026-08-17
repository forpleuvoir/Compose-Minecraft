# Compose Minecraft Platform — 业务迁移指南

> 对应实施计划的 **阶段 H**:平台完成后的业务迁移建议。
> 本文档只输出迁移建议,不默认修改业务 Mod。

## 1. 平台概述

**Compose Minecraft Platform Mod** 是一个双 Loader(Fabric + NeoForge)基座 Mod,
把 Compose Multiplatform 1.11 的 UI 运行时移植到 Minecraft 客户端:

- **无 Skia / Skiko / Desktop / Material**:所有绘制经 `ComposeGuiRenderer` 提交到
  当前主帧缓冲,不走原版 `GuiRenderState` 节点树(独立渲染工作流,T.24,
  Vulkan/OpenGL 渲染后端无关,天然双后端支持);
- **原版 `Screen` 桥接**:Compose 场景通过 `net.minecraft.client.gui.screens.Screen`
  挂入 Minecraft,渲染、输入、生命周期全部走原版屏幕机制;渲染经
  `GuiRendererMixin` 帧钩子(`@Inject` 在 `GuiRenderer.render()` 的 draw() 调用之后)
  提交,mixin 清单:GuiRendererMixin(帧钩子)+ StyleAccessor(只读字段)+
  FontManagerAccessor / FontSetAccessor / MinecraftAccessor(T.32 自定义字体);
- **场景密度可配置(T.26)**:默认 `1f`(1dp == 1 像素,场景尺寸 = 窗口像素,T.24
  1:1 不再除 guiScale),`ComposeScreen.open(density = …)` 可传 >1f 放大 UI。

| 属性 | 值 |
|---|---|
| Mod ID | `compose_minecraft` |
| 版本 | `26.2-0.1.0` |
| Loader | Fabric(0.19.x+ / Loom 1.17.x)、NeoForge(26.2.x) |
| 运行时依赖 | Kotlin for Forge(Fabric 端 Fabric Kotlin) |
| 平台包 | `moe.forpleuvoir.compose_minecraft.platform` |

## 2. 依赖平台 Mod

### 2.1 运行时安装

把对应 Loader 的发布 JAR 放入 `mods/` 文件夹即可:

- `compose_minecraft-fabric-26.2-0.1.0.jar`
- `compose_minecraft-neoforge-26.2-0.1.0.jar`

平台 JAR 已内嵌完整 Compose 运行时(约 4166 个 `androidx.compose.*` 类),
消费者 Mod **不需要** 再引入任何 Compose/Skiko 依赖,也不要重复嵌入。

### 2.2 Fabric 元数据声明

`fabric.mod.json` 中声明依赖(建议带上版本区间):

```json
{
  "depends": {
    "compose_minecraft": ">=26.2-0.1.0"
  }
}
```

### 2.3 NeoForge 元数据声明

`neoforge.mods.toml` 的 `[[dependencies.compose_minecraft]]` 段:

```toml
[[dependencies.compose_minecraft]]
modId = "compose_minecraft"
type = "required"
versionRange = "[26.2-0.1.0,)"
ordering = "NONE"
side = "CLIENT"
```

## 3. 开始使用

```kotlin
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen

// 在任意主线程位置打开 Compose 屏幕(等价于原版 minecraft.gui.setScreen);
// 可传 density >1f 放大 UI(默认 1f)
ComposeScreen.open(density = 1f) {
    MyComposeUi()
}
```

平台还提供 `MinecraftInitializer`(ServiceLoader)作为客户端初始化钩子;
`MinecraftComposeScene` 是场景宿主,`ComposeScreen` 已内置,一般无需直接使用。

## 4. 可用的 Compose API 范围

以下 API 已随平台 JAR 提供(源码级移植,无外部 Compose 依赖):

### 布局

`Box` / `Column` / `Row` / `Spacer` / `BoxWithConstraints` / `FlowLayout` / `FlexBox` /
`Grid` / `AspectRatio` / `padding` / `size` / `fillMaxSize` / `weight` / `Arrangement` /
`Alignment` / `offset` / `IntrinsicSize` 等。

### 基础组件

`Canvas`(绘制命令经 MinecraftCanvas 直通 ComposeGuiRenderer)、`background`、
`border`、`clickable`、`Image`(CPU 位图)、`focusable`、`Hoverable`、
`ContextMenuArea`、`BasicMarquee`、`ReceiveContent`。
(系统拖放 `DragAndDropTarget` / `DragAndDropSource` 未接通,见 §5)

### 滚动与列表

`verticalScroll` / `horizontalScroll` / `scrollable`、`LazyList` / `LazyGrid` /
`LazyStaggeredGrid` / `LazyLayout`(全量移植)、`rememberScrollState`、
`rememberLazyListState` 等。

### 文本(MC Font 度量)

`BasicText`(推荐,`Text` 未移植)、`ClickableText`、`SelectionContainer` 等。

- 字体 = Minecraft 字体,行高 9px(固定 1x 行盒);
- `fontSize`(sp)驱动字号(T.19):16sp = 1 倍平台基准(9sp = 1x 原生像素),
  布局尺寸随缩放联动;`autoSize`(T.20)自动缩放(二分搜索最大适配字号);
- 颜色经 `TextStyle(color = ...)` 生效;`BasicTextField(fontSize)` 输入框字号
  (T.26,默认 18sp = 2x);
- 富文本:段级混排(T.29,`BasicText(text: AnnotatedString)`)、
  `PlatformSpanStyle` 承载 MC 原版 Style 渲染特性(obfuscated/shadowColor/
  clickEvent/hoverEvent/insertion/font,T.28);
- 默认字体/样式/字号 CompositionLocal(T.30/T.32):`LocalDefaultFont` /
  `LocalDefaultTextStyle` / `LocalDefaultFontSize` 可 Provider 覆盖;
  自定义字体经 `rememberCustomFont(path)` 注册(T.32)。

### 输入与焦点

- 指针:Press / Release / Move / Drag / Scroll(滚轮)完整转发;
- 键盘:KeyDown / KeyUp 转发,GLFW 键码经 MC `InputConstants` 映射为 Compose `Key`;
- 焦点:获得/转移、`onFocusChanged`、Tab 与方向键导航、`FocusRequester`。
  注意 `onFocusChanged` 必须放在焦点目标(如 `focusable` / `clickable`)**之前**;
- `clickable` 自带焦点目标,不要与 `focusable` 叠加使用(会产生双焦点目标,
  导致 Tab 循环)。

### 图形

矩形 / 圆角矩形 / 圆 / 椭圆 / 弧 / 线 / 路径 / 点 / 图片(CPU 位图)/
变换(平移/缩放/旋转)/ 裁剪。命令式 `Canvas` 与声明式组件均可用。

## 5. 不包含的 API 与替代方案

| 缺失项 | 状态 | 替代方案 |
|---|---|---|
| **Material 全家**(Button/TextField/Card/Theme…) | 未移植 | 用基础组件 + `background`/`border`/`clickable` 自绘 |
| 官方 `Text` 组件 | 未移植(依赖缺失的 `LocalTextStyle`) | 用 `BasicText(text, style = TextStyle(color = ...))` |
| `TextField` / IME 文本输入 | 已接通:charTyped 上屏 + IME preedit 组合态(下划线 + 候选窗跟随) | 中文输入法上屏可用,组合态可见(原实现计划 `input-mc-native-plan.md` 已随实现落地移除,实现见 `platform/textinput/MinecraftTextInputService.kt`) |
| `Popup` / `Dialog` | 已实现(T.33):场景内图层弹层,焦点隔离/遮罩/Escape 与 outside 关闭 | 直接使用;`DropdownMenu` 未移植,需自行定位绘制 |
| 剪贴板 | 已接通(经 MC `KeyboardHandler`,纯文本) | `LocalClipboard.current` 读写文本 |
| 指针图标 `PointerIcon` | 已实现(I9) | `Modifier.pointerHoverIcon` 生效:Default→ARROW、Crosshair→CROSSHAIR、Text→IBEAM、Hand→POINTING_HAND(MC 原版 `CursorTypes`,经原版 per-frame 光标管线;自定义图标回退 ARROW) |
| 动画库 `animation` / `material3` 动效 | 未经受控验证 | 先验证再使用 |
| 远程图片加载 | 未移植(`FontFamily.Resolver` 未接通) | 仅用 CPU 位图(`Image`) |
| 自定义字体 | 已实现(T.32):FreeType 加载任意 ttf/otf/ttc 注册进 MC FontManager | `rememberCustomFont(path)` 自动注册/注销,`LocalDefaultFont provides …` 或 `PlatformSpanStyle(font = …)` 切换 |

## 6. 从 Desktop/Skiko 迁移时删除的依赖

业务 Mod 若原先基于 CMP Desktop 开发,迁移后应删除/替换:

```kotlin
// 删除(平台已内嵌等价能力,且以 MC 字体/当前帧渲染替代)
implementation(compose.desktop.currentOs)         // Skiko 桌面运行时
implementation(compose.material)                  // Material(未移植)
implementation(compose.material3)                 // Material3(未移植)
implementation(compose.components.resources)      // 资源加载(未移植)
```

保留:业务 Mod 自身逻辑依赖(如 ViewModel/lifecycle 已内嵌,无需再引)。

## 7. 旧桥接文件清理建议

业务 Mod 若沿用旧的自绘/桥接方案(如直接操作 `GuiGraphics`、自建 Screen、
事件总线转发输入),迁移后可删除:

- 旧的 Screen 桥接类 → 替换为 `ComposeScreen.open { }`;
- 旧的键盘/鼠标事件转发 → 删除(Compose 场景已接收全部输入);
- 旧的文字渲染代码 → 删除(用 `BasicText`);
- 旧的 MC 字体测量工具 → 删除(Compose 文本度量已统一走 MC Font)。

## 8. 坐标与密度

- 默认密度 `1f`:场景坐标 = 窗口像素,`1dp == 1 像素`,场景尺寸 = 窗口像素
  (T.24,不再除 guiScale);
- 密度可配置(T.26):`ComposeScreen.open(density = 2f)` 时 UI 元素视觉放大,
  1dp = 2 像素,文本字号随 scale 同步放大,与官方桌面 density 语义一致;
- 指针坐标直接使用 MC 传入的像素坐标,无需换算(scene 内部按 density 换算 dp);
- 窗口尺寸变化(含 GUI Scale 调整)自动同步,`resize` 由 `renderFrame` 每帧驱动。

## 9. 已知限制与后续计划

- **NeoForge 端 dev 测试**:NeoForge(ModDevGradle)不支持 devOnly 源码集,
  dev 场景验证统一在 Fabric 端进行;NeoForge 端只做发布构建;
- **文本输入**:`charTyped` 上屏与 IME 组合态(preedit)已接通,`BasicTextField` 链路可用;
  指针图标已接通(I9);剩余输入补全(双击/拖放)见 `docs/todo-and-placeholders.md`;
- **弹出层**:Popup/Dialog 已实现(T.33,场景内图层弹层,非系统窗口;DropdownMenu 未移植);
- **发布**:平台当前未配置 maven 发布(阶段 G 决策),消费者直接依赖发布 JAR;
  需要时再补 `mavenLocal()` / 远程仓库发布。
