# 输入体系 MC 原生化改造计划书

> 目标:把移植源码中所有"desktop 平台实现"的输入链路,替换为 Minecraft 原生的输入体系。
> 原则:只替换输入**接入层**(事件来源/平台 actual),不修改 Compose 行为逻辑
> (AGENTS.md 约束 #7);不引入 AWT/Swing/Skiko/LWJGL 直调(约束 #2、#5)。
> **状态:✅ 已完成** —— 阶段 1-4 已实现并落地(`0ce176f` IME 接入、`7915c28` 候选窗
> 位置修复 T.31、`96b403e` 移除详细设计文档、I9 指针图标经原版光标管线接入)。
> 本文档保留为设计记录,实际实现以代码为准。

---

## 1. 背景与动机

当前移植源码(androidx/compose/**)的输入链路是 CMP desktop(Skiko)时代的
expect/actual 剥离产物,大量输入源仍是"桌面窗口事件"模型:
- 键码走 GLFW→AWT VK 转换(桥接层自维护映射表);
- IME 是空实现(`EmptyPlatformTextInputService` + `startInputMethod` 挂死);
- 指针/滚轮/焦点等事件由 Screen 桥接手工转发。

而 MC 26.2 原生已有完整输入体系(KeyboardHandler / MouseHandler /
TextInputManager / preedit 回调 / Screen 事件分发),且**所有输入事件都汇聚到
原版 Screen**(我们的 ComposeScreen 就是 Screen)。目标:让 Compose 消费
MC 原生输入事件,消除桌面模型残留与手工桥接。

---

## 2. 现状盘点(输入链路清单)—— ✅ 已全部落地

| # | 链路 | 当前实现 | 状态 |
|---|---|---|---|
| I1 | 键盘按键 | `ComposeInputBridge.toCompose`:MC KeyEvent(GLFW 键码)→ `mcKeyToComposeKey` 映射表 → Compose Key(AWT VK 编码) | ✅ 映射表已收敛为唯一适配点(`ComposeInputBridge.kt`,显式映射 + A-Z/0-9 直通 + Unknown 降级) |
| I2 | 字符输入(charTyped) | `ComposeScreen.charTyped` → typed KeyEvent → TextField | ✅ 保留(上屏通道),与 I3 配合(组合期计数定位光标) |
| I3 | IME 组合串(preedit) | `MinecraftTextInputService`(`platform/textinput/`)经 `ComposeScreen.preeditUpdated` 转发;`TextInputManager.start/stopTextInput` 启停;`SetComposingTextCommand` 写组合区(下划线);候选窗 `setTextInputArea` 像素直传(T.31);组合期 Backspace/Delete/Esc 按键隔离(`IME_COMPOSITION_KEYS`) | ✅ 已完成(阶段 1,`0ce176f` + `7915c28`) |
| I4 | 鼠标指针 | `ComposeScreen.mouseClicked/mouseReleased/mouseMoved/mouseDragged` 转发 | ✅ 保持(Screen 事件即 MC 原生,已达标) |
| I5 | 滚轮 | `ComposeInputBridge.scrollDelta`(MC scroll → Compose Offset,54px/格) | ✅ 保持并核对(T.32 对齐官方桌面 ≈53px/格) |
| I6 | 焦点导航 | `RootNodeOwner.handleFocusKeys`(Tab/Enter/Back) | ✅ 保持(Compose 内部焦点,与 MC 不冲突) |
| I7 | 剪贴板 | `MinecraftClipboard`(MC KeyboardHandler) | ✅ 已完成,不涉及 |
| I8 | 软键盘 | `EmptyPlatformTextInputService.showSoftwareKeyboard` 空 | ✅ 保持空(MC 桌面无软键盘) |
| I9 | 指针图标 | `MinecraftComposeScene.setPointerIcon`(PlatformContext 覆写)映射到 MC 原版 `CursorTypes`(ARROW/CROSSHAIR/IBEAM/POINTING_HAND),经 `ComposeScreen.extractRenderState` → `GuiGraphicsExtractor.requestCursor` 走原版 per-frame 管线 | ✅ 已完成(阶段 4;`Window.selectCursor` 带去重,尊重原版「允许光标变化」设置项) |
| I10 | 拖拽 | `EmptyDragAndDropManager` 空 | ✅ 保持空(MC 无原生拖拽语义) |

---

## 3. MC 原生输入能力(已调研确认,26.2)

| MC 组件 | 能力 | 对应改造 |
|---|---|---|
| `KeyboardHandler`(client) | 键按下/释放、charTyped、**IME preedit 回调注册**、剪贴板 | I1/I2/I3/I7 |
| `MouseHandler`(client) | 鼠标点击/移动/拖拽/滚轮 → Screen 事件 | I4/I5(已走 Screen,无需改) |
| `com.mojang.blaze3d.platform.TextInputManager` | `startTextInput()`/`stopTextInput()`/`setTextInputArea()`/`onTextInputFocusChange()` | I3(IME 启停 + 候选窗) |
| `net.minecraft.client.input.PreeditEvent` | 组合串:fullText/caretPosition/blocks/focusedBlock;null=提交 | I3(组合态→SetComposingTextCommand) |
| `GuiEventListener.preeditUpdated(PreeditEvent?)` | Screen 级组合串接收点(默认 false) | I3(`ComposeScreen` 已重写) |
| `Screen` 事件分发 | 所有输入经原版 Screen 汇聚 | 全部(`ComposeScreen` 即 Screen) |

---

## 4. 改造设计

### 4.1 输入事件接入统一入口

原则:**ComposeScreen(原版 Screen 子类)是唯一输入入口**,所有 MC 原生事件
在此转换为 Compose 事件后进入 `MinecraftComposeScene`。禁止在
androidx/compose 移植源码内新增 MC 依赖;转换代码放自有包
`moe/forpleuvoir/compose_minecraft/platform/`。

```
MC 输入(KeyboardHandler / MouseHandler / Screen 事件)
  └─ ComposeScreen(桥接)
       ├─ keyPressed/keyReleased → ComposeInputBridge → scene.sendKeyEvent
       ├─ charTyped → typed KeyEvent + service.onCharTyped 计数
       ├─ preeditUpdated → MinecraftTextInputService.onPreeditChanged(已实现)
       ├─ mouseClicked/Moved/Dragged/Scrolled → scene.sendPointerEvent(已有)
       └─ 生命周期(removed/onClose) → scene.close + stopInput 清理
```

### 4.2 I3:IME 接入(核心新功能)—— ✅ 已实现

落地为 `MinecraftTextInputService : PlatformTextInputService`
(`common/src/main/kotlin/moe/forpleuvoir/compose_minecraft/platform/textinput/`):

1. `MinecraftComposeScene` 持有 `textInputService = MinecraftTextInputService()`;
2. `ComposeScreen.preeditUpdated` 重写,转发组合串;
3. preedit 非空 → `SetComposingTextCommand`(官方桌面语义,自动替换整个旧组合区;
   不额外发 `SetComposingRegionCommand`——退格缩短场景会提前缩小组合区导致残留);
4. preedit null(提交/取消)→ `SetComposingRegionCommand + CommitTextCommand("")` 清理
   残留组合文本 + 光标定位(提交场景光标在提交文本后,取消场景回到组合起点);
5. 提交语义:IME 提交文本永远走 charTyped(原链路不变),preedit 不负责上屏,
   **与 charTyped 无重复**(GCS_RESULTSTR 先触发字符回调再触发 preedit null);
6. 候选窗:组合更新时把光标矩形交 `TextInputManager.setTextInputArea`
   (T.31:其内部乘 guiScale,场景像素化后先除回,净效果 = 像素直传);
7. 组合期按键隔离:Backspace/Delete/Esc 由输入法处理,不进入 Compose
   (`ComposeScreen.keyPressed/keyReleased` 的 `IME_COMPOSITION_KEYS` 分支)。

### 4.3 I1:键码映射收敛(重构而非重写)—— ✅ 已完成

- 保留 `Key.kt`(AWT VK 契约,官方移植源码,不动);
- 映射表 `mcKeyToComposeKey` 已是唯一转换点(`ComposeInputBridge.kt`),无散落转换;
  显式映射覆盖导航/编辑/标点/修饰/功能/小键盘全表,A-Z/0-9 直通段与显式映射段
  边界已核对,无法映射键返回 `Key.Unknown`。

### 4.4 I6:焦点/导航核对—— ✅ 已完成

- 保留 `RootNodeOwner.handleFocusKeys`(Compose 内部焦点);
- Tab 键由 Compose 焦点优先消费,未消费回落 MC 默认,无冲突。

### 4.5 I9:指针图标(可选)—— ✅ 已完成

- MC 26.2 原版光标 API:`com.mojang.blaze3d.platform.cursor.CursorType`(封装 GLFW
  光标句柄,`select(Window)` 调 `glfwSetCursor`)/`CursorTypes`(ARROW/IBEAM/
  CROSSHAIR/POINTING_HAND/RESIZE_*/NOT_ALLOWED),由 `Window.selectCursor` 带去重;
- 接入:`MinecraftComposeScene` 覆写 `PlatformContext.setPointerIcon`,按
  `MinecraftPointerIconKind`(Default/Crosshair/Text/Hand,平台适配点新增)映射到
  原版光标;`ComposeScreen.extractRenderState` 经 `GuiGraphicsExtractor.requestCursor`
  并入原版 per-frame 管线(帧末 applyCursor 生效)—— 单写者、无闪烁、零新 mixin;
- 原版设置项 `Options.allowCursorChanges`(默认开)由 `Window.selectCursor` 统一拦截,
  自动尊重玩家关闭该设置的意愿;
- 映射:Default→ARROW、Crosshair→CROSSHAIR、Text→IBEAM、Hand→POINTING_HAND、
  自定义图标回退 ARROW。

---

## 5. 分阶段执行计划 —— ✅ 阶段 1-3 完成

### 阶段 1:IME 接入(I3)—— ✅ 完成(`0ce176f` + `7915c28`)
- `MinecraftTextInputService` + preedit 转发 + 候选窗(T.31 像素直传);
- dev scene 实测:中文拼音组合态、候选上屏、光标跟随;
- 交付:输入框聚焦弹系统 IME、组合态下划线可见。

### 阶段 2:键码映射收敛与核对(I1/I5)—— ✅ 完成
- `mcKeyToComposeKey` 全键映射核对/补齐(导航/编辑/标点/修饰/功能/小键盘);
- 滚轮常量对齐官方桌面语义(54px/格,T.32);
- 方向键/Home/End/Tab/标点/小键盘实测通过。

### 阶段 3:焦点/生命周期收尾(I6 + 输入清理)—— ✅ 完成
- Tab 焦点不冲突;
- `stopInput` + preedit 状态清理(`MinecraftTextInputService.stopInput` 重置
  composing/composedStart/composedLength/committedCharCount/focusedRect);
- 全量构建 + runClient 回归通过。

### 阶段 4(可选):指针图标(I9)—— ✅ 完成
- MC 原版光标接入 `setPointerIcon`:`MinecraftPointerIcon` 携带种类 → 映射
  `CursorTypes` → `extractRenderState` 经 `requestCursor` 走原版管线;
- dev scene 实测:悬停 TextField 出 I 形光标、悬停链接出手型、移出回箭头。

---

## 6. 验证标准 —— ✅ 已达成

- 每阶段 `build_project` 全量构建零错误;
- dev scene(`TextInputDevScene`):
  - 中文输入法:聚焦弹出 IME、拼音组合态显示、候选上屏、光标移动候选窗跟随 ✅;
  - 英文/数字/标点直输、退格/方向键/Home/End/Delete ✅;
  - Tab 焦点导航不受影响 ✅;
  - 鼠标点击定位光标、选区拖拽 ✅;
- 回归:既有 dev scene(样式/滚动/几何)不回归 ✅。

---

## 7. 风险与边界 —— 已实测闭环

- **preedit 与 charTyped 重复**:✅ 无重复 —— MC GLFW 分支 GCS_RESULTSTR 先触发
  字符回调(charTyped 上屏)再触发 preedit(null),提交文本永远走 charTyped,
  preedit 只负责组合区显示与清理;
- **组合区与选区互斥**:组合期间普通选区不显示,官方 SetComposingText 语义;
- **Key 契约不改**:AWT VK 编码是官方契约,只改桥接层,不改移植源码;
- **不引入依赖**:无新增 Gradle 依赖;
- **MC 版本耦合**:TextInputManager/PreeditEvent 为 26.2 API,升级 MC 需复核。

---

## 8. 相关文档与文件

- `mc-ime-service-plan.md` —— **已移除**(`96b403e`:IME Service 已实现落地),
  设计要点已并入本文档 §4.2;
- `mc-narration-research.md`(读屏调研,与输入体系独立);
- 输入链路文件:
  - `platform/ComposeScreen.kt`(输入桥接入口:key/charTyped/preeditUpdated/鼠标/滚轮)
  - `platform/ComposeInputBridge.kt`(键码/修饰键/滚轮映射,唯一转换点)
  - `platform/MinecraftComposeScene.kt`(场景输入转发 + textInputService 持有)
  - `platform/textinput/MinecraftTextInputService.kt`(IME Service:preedit 转换/
    候选窗/组合期按键隔离)
  - `androidx/.../text/input/TextInputService.kt`(PlatformTextInputService 接口)
  - `androidx/.../text/input/internal/TextInputSession.platform.kt`(MinecraftPlatformTextInputMethodRequest)
  - `androidx/.../ui/platform/PlatformContext.kt`(textInputService 接入点)
