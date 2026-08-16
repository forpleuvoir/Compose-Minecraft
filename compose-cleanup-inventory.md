# 移植源码清理清单(`androidx/compose/`)

> 范围:仅 `common/src/main/kotlin/androidx/compose/` 下的 CMP 1.11 移植源码。
> 处置原则:保留 expect 契约与被核心路径引用的结构;删除仅限零调用死代码;
> "整理"= 保留契约骨架,把 desktop/Swing/AWT/Skiko 实体依赖换成 MC 平台占位,
> 不动行为逻辑(AGENTS.md 约束 #2、#7)。
>
> 分类标记:
> - `[删]` 已确认零调用,可整文件删除
> - `[整理]` 被核心/契约引用,含桌面端实体依赖,需替换实现保留契约
> - `[命名]` 内容已完成 MC 适配,仅文件名/类名/注释残留 "Desktop/Skiko" 字样,低优
> - `[不动]` 平台契约点,AGENTS.md 明确保护,不得改动
> - `[存疑]` 尚未完全确认,需进一步调查后再定

---

## A. 死代码(零 incoming 调用)

| # | 文件 | 符号 | 证据 | 标记 | 用途 / 说明 |
|---|---|---|---|---|---|
| A1 | `ui/scene/PlatformLayersComposeScene.kt` | `PlatformLayersComposeScene`(工厂函数,行 64-80)、`PlatformLayersComposeSceneImpl`(私有类,行 82-210) | `analyze_calls INCOMING_CALLS` 返回仅根节点、无调用者 | `[删]` | desktop "每层独立平台层"的 scene 实现。平台实际只用 `CanvasLayersComposeScene`(由 `MinecraftComposeScene` 包装)。整个文件无外部引用,删除安全。 |
| A2 | `ui/internal/SkikoListUtils.kt` | `fastMapIndexedNotNullTo`(行 33)、`fastIndexOfFirst`(行 52) | 两个函数 `analyze_calls INCOMING_CALLS` 均返回无调用者 | `[删]` | 命名含 "Skiko" 但内容是通用 list 工具(无 Skiko 依赖);两函数均无调用,是死文件。 |

---

## B. desktop 实体残留(被核心引用,需"整理")

| # | 文件 | 被引用证据 | 标记 | 用途 / 说明 |
|---|---|---|---|---|
| B1 | `ui/viewinterop/InteropView.platform.kt` | 被 `LayoutNode`、`RootNodeOwner`、`CanvasLayersComposeScene`、`ComposeScene` 引用 | `[整理]` | desktop 嵌入原生 AWT/Swing View 的互操作 actual。MC 不嵌入原生 View,永不触达。整理:保留 expect 契约,把 Swing/AWT 载体换成 `UnsupportedOperationException` 占位。 |
| B2 | `ui/viewinterop/InteropContainer.platform.kt` | 同上,被 `LayoutNode`/`RootNodeOwner` 引用 | `[整理]` | 互操作容器(插入/移除/重排原生 View)。MC 无原生 View,占位。 |
| B3 | `ui/viewinterop/InteropViewHolder.platform.kt` | `search_symbol` 命中类 + 2 方法,被 viewinterop 内部引用 | `[整理]` | 原生 View 持有者。占位。 |
| B4 | `ui/viewinterop/InteropPointerInput.platform.kt` | 引用 InteropView 链 | `[整理]` | 互操作视图指针输入桥。占位。 |
| B5 | `ui/viewinterop/InteropViewFactoryHolder.kt` | `search_symbol` 命中类 + 2 方法 | `[整理]` | 互操作 View 工厂持有者。占位。 |

> B 类统一处置:保留 expect 契约与核心引用点;把 actual 内 Swing/AWT/Skiko 载体替换为占位实现(抛异常或返回空),保持编译契约。MC 路径不会触达,占位语义正确。

---

## C. `ui/platform/Default*.kt` / `Platform*.kt`(需逐文件核实)

| # | 文件 | 标记 | 用途 / 说明 |
|---|---|---|---|
| C1 | `ui/platform/PlatformClipboard.kt` | `[命名]` | 已是 MC 实现(`MinecraftClipboard` 经 MC `KeyboardHandler` 访问剪贴板,注释明确"不引入 AWT/Skiko/Desktop")。内容无 desktop 实体,**无需改**。 |
| C2 | `ui/platform/DefaultAccessibilityManager.kt` | `[存疑]` | desktop 可访问性管理。MC 无原生 a11y,核实是否被核心强制引用;若是,占位化。 |
| C3 | `ui/platform/DefaultHapticFeedback.kt` | `[存疑]` | 触觉反馈默认实现。MC 无触觉硬件。核实是否被 `LocalHapticFeedback` 默认引用后占位化。 |
| C4 | `ui/platform/DefaultTextToolbar.kt` | `[存疑]` | 文本工具栏(复制/粘贴/全选菜单)。desktop 为系统菜单;MC 已接通剪贴板但无选单 UI,核实是否依赖 desktop 实体。 |
| C5 | `ui/platform/DefaultNavigationEventDispatcherOwner.kt` | `[存疑]` | 导航事件派发器拥有者。MC 无对应系统概念,核实引用链。 |
| C6 | `ui/platform/DefaultViewModelOwnerStore.kt` | `[存疑]` | ViewModel 拥有者存储默认实现。MC Mod 是否走 ViewModel 存续需核实。 |
| C7 | `ui/platform/DefaultViewConfiguration.kt` | `[存疑]` | ViewConfiguration 默认值(最小触控尺寸/点击延迟)。desktop 用桌面阈值,MC 应改 GUI 阈值 —— **行为适配点而非清理点**,列为存疑以免误删。 |
| C8 | `ui/platform/SoftwareKeyboardController.kt` | `[存疑]` | 软键盘控制器。MC 无软键盘(IME 候选由系统输入法负责,AGENTS.md 已知限制),核实是否已占位。 |
| C9 | `ui/platform/PlatformDragAndDropManager.kt` | `[存疑]` | 平台拖拽管理器。AGENTS.md 未列拖拽为已支持,疑似 desktop 实体或占位,需核实。 |
| C10 | `ui/platform/PlatformScreenReader.kt` | `[存疑]` | 屏幕阅读器平台桥。MC 无原生屏幕阅读器,占位候选。 |
| C11 | `ui/platform/PlatformUriHandler.kt` | `[存疑]` | URI 处理器(打开链接)。desktop 调系统浏览器;MC 无此能力,占位候选。 |
| C12 | `ui/platform/PlatformTextInputMethodRequest.kt` | `[存疑]` | 文本输入方法请求。与已接通的 charTyped 输入链相关,**需仔细核实而非贸然占位**。 |
| C13 | `ui/platform/PlatformWindowInsetsProvider/PlatformWindowInsetsProviderNode.kt` | `[存疑]` | 窗口 insets 提供者。desktop/Skiko 取系统 insets;MC GUI 无 insets 概念,核实是否已空实现。 |

---

## D. 文本相关 desktop/Skiko 命名残留(已完成适配,仅命名/注释)

| # | 文件 | 标记 | 用途 / 说明 |
|---|---|---|---|
| D1 | `ui/text/intl/DesktopPlatformLocale.platform.kt` | `[命名]` | 已是 JVM `java.util.Locale` 实现,注释明确"替代原 AWT、不引入 AWT/desktop",自写 RTL 语言列表替代 AWT `ComponentOrientation`。无 AWT 依赖。内容无需改,仅文件名 `DesktopPlatformLocale` 残留 desktop 前缀(可选重命名 `JvmPlatformLocale`)。 |
| D2 | `ui/text/platform/DesktopStringDelegate.platform.kt` | `[命名]` | 已是 `java.util.Locale` 的 JVM 实现(upper/lower/capitalize/decapitalize),无 AWT/Swing 依赖。类名 `DesktopStringDelegate` + 工厂 `ActualStringDelegate()` 返回它,保留契约即可。内容无需改。 |
| D3 | `ui/text/FontRasterizationSettings.platform.kt` | `[命名]` | 注释提及 "Skiko" 但内容是 JVM `os.name` 检测的 `Platform` 枚举(Windows/Linux/MacOS/Android/iOS...),已脱离 Skiko。命名/注释残留,内容无需改。 |

---

## E. ComposeUiFlags 的 Skiko 命名残留(行为标志,非实体)

| # | 文件 | 标记 | 用途 / 说明 |
|---|---|---|---|
| E1 | `ui/ComposeUiFlags.platform.kt` | `[命名]` | `internal object SkikoComposeUiFlags` + 4 个布尔标志(useLegacyRenderNodeLayers / isClearFocusOnMouseDownEnabled / isDialogAnimationEnabled / areWindowInsetsRulersEnabled)。这是运行时行为开关,非 Skiko 实体依赖。`useLegacyRenderNodeLayers` 对应的 `LegacyRenderNodeLayer` 只在 `.tmp-scene` 旧产物中出现,当前源码已无该类 —— 该标志大概率是**死标志**(设了无效果)。**建议**:重命名 object 为 `MinecraftComposeUiFlags`;`useLegacyRenderNodeLayers` 若确认无对应实现可删。需核实各标志读取点。 |

---

## F. 平台契约保护点(不得改动)

| # | 文件 | 标记 | 用途 / 说明 |
|---|---|---|---|
| F1 | `ui/input/key/Key.kt` | `[不动]` | 全模块唯一 `java.awt` 引用点(5 个 import + VK 常量)。AGENTS.md 约束 #6:Compose `Key` 编码即 AWT VK 值(库源码契约),桥接层不引用 AWT 类型 —— 但 Key 定义本身依赖 `java.awt.event.KeyEvent` 的 VK 常量是官方契约,保留。`javax.swing` 全模块零命中。 |

---

## G. 待进一步调查的"存疑"汇总

> 实施前需用 `analyze_calls` / `read_file` 逐个确认死活与是否含 desktop 实体:

1. **C2–C13** `ui/platform/Default*.kt` 与 `Platform*.kt` 共 11 个文件 —— 逐个读内容,区分"已 MC 适配 / desktop 实体 / 占位已存在"。
2. **E1** `ComposeUiFlags` 的 4 个标志各自读取点 —— 确认哪些已无对应实现(可删)、哪些仍被读取(保留)。
3. **`ui/scene/ComposeSceneDragAndDropNode.kt`** —— 目录树中出现,可能与 B 类 interop/拖拽关联,核实是否死代码。
4. **`ui/dragandrop/`、`ui/autofill/`、`ui/input/rotary/`、`ui/input/indirect/`** 整个子模块 —— MC GUI 无对应能力,但可能被核心 trait 引用,需整组核实引用链后决定(整组删除 / 保留契约占位)。
5. **`foundation/content/`(ReceiveContent 拖拽接收)、`foundation/contextmenu/`(右键菜单)** —— desktop 功能模块,核实 foundation 是否强制引用。

---

## 验证要求(实施阶段)

- 每删一个文件前先 `mcp__idea__analyze_calls INCOMING_CALLS` 确认零调用,不以文件名贸然删。
- 每阶段改动后用 `mcp__idea__build_project` 的 `filesToRebuild` 增量验证(Gradle 命令已禁,IDE 终端跑 Gradle 也已禁,见 AGENTS.md)。
- 收尾用 `build_project(rebuild=true)` 全量构建。
- 收尾 grep 确认:`import java.awt` 仅剩 `Key.kt`;`javax.swing` 零;`Skiko`(实体 import)零。