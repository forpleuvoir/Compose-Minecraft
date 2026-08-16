# 输入体系 MC 原生化改造计划书

> 目标:把移植源码中所有"desktop 平台实现"的输入链路,替换为 Minecraft 原生的输入体系。
> 原则:只替换输入**接入层**(事件来源/平台 actual),不修改 Compose 行为逻辑
> (AGENTS.md 约束 #7);不引入 AWT/Swing/Skiko/LWJGL 直调(约束 #2、#5)。
> 状态:规划中,待用户逐步批准执行。

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

## 2. 现状盘点(输入链路清单)

| # | 链路 | 当前实现 | 问题 | 目标 |
|---|---|---|---|---|
| I1 | 键盘按键 | `ComposeInputBridge.toCompose`:MC KeyEvent(GLFW 键码)→ 自维护映射表 → Compose Key(AWT VK 编码) | 映射表硬编码、双编码转换 | 保留 VK 契约,但映射表收敛为唯一适配点,消除散落转换 |
| I2 | 字符输入(charTyped) | `ComposeScreen.charTyped` → typed KeyEvent → TextField(已通) | 无组合态;上屏即字符 | 保留(上屏通道),与 I3 配合 |
| I3 | IME 组合串(preedit) | 未实现(`startInputMethod` 挂死、`EmptyPlatformTextInputService`) | 不弹输入法、无组合态提示 | 接 MC `TextInputManager` + `PreeditEvent` 回调(见 mc-ime-service-plan.md) |
| I4 | 鼠标指针 | `ComposeScreen.mouseClicked/mouseReleased/mouseMoved/mouseDragged` 手工转发 | 手工转发,多入口 | 保持(Screen 事件即 MC 原生,已达标) |
| I5 | 滚轮 | `ComposeInputBridge.scrollDelta`(MC scroll → Compose Offset,27px/格) | 常量与 MC 语义耦合 | 保持,核对与 MC 滚动语义一致 |
| I6 | 焦点导航 | `RootNodeOwner.handleFocusKeys`(Tab/Enter/Back) | 与 MC 焦点体系无关 | 保持(Compose 内部焦点),核对 Tab 与 MC 交互不冲突 |
| I7 | 剪贴板 | `MinecraftClipboard`(MC KeyboardHandler,已完成) | 无 | 已完成,不涉及 |
| I8 | 软键盘 | `EmptyPlatformTextInputService.showSoftwareKeyboard` 空 | MC 无软键盘概念 | 保持空(MC 桌面无软键盘) |
| I9 | 指针图标 | 占位(AGENTS.md 已知限制) | 未接 MC 光标 | 可选:接 MC 光标系统 |
| I10 | 拖拽 | `EmptyDragAndDropManager` 空 | MC 无原生拖拽语义 | 保持空 |

---

## 3. MC 原生输入能力(已调研确认,26.2)

| MC 组件 | 能力 | 对应改造 |
|---|---|---|
| `KeyboardHandler`(client) | 键按下/释放、charTyped、**IME preedit 回调注册**、剪贴板 | I1/I2/I3/I7 |
| `MouseHandler`(client) | 鼠标点击/移动/拖拽/滚轮 → Screen 事件 | I4/I5(已走 Screen,无需改) |
| `com.mojang.blaze3d.platform.TextInputManager` | `startTextInput()`/`stopTextInput()`/`setTextInputArea()`/`onTextInputFocusChange()` | I3(IME 启停 + 候选窗) |
| `net.minecraft.client.input.PreeditEvent` | 组合串:fullText/caretPosition/blocks/focusedBlock;null=提交 | I3(组合态→SetComposingText 命令) |
| `GuiEventListener.preeditUpdated(PreeditEvent?)` | Screen 级组合串接收点(默认 false) | I3(ComposeScreen 重写) |
| `Screen` 事件分发 | 所有输入经原版 Screen 汇聚 | 全部(ComposeScreen 即 Screen) |

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
       ├─ charTyped → typed KeyEvent(已有)
       ├─ preeditUpdated → MinecraftTextInputService.onPreeditChanged(新)
       ├─ mouseClicked/Moved/Dragged/Scrolled → scene.sendPointerEvent(已有)
       └─ 生命周期(removed/onClose) → scene.close + textInput 清理
```

### 4.2 I3:IME 接入(核心新功能)

按 `mc-ime-service-plan.md` 实施:
1. 新建 `MinecraftTextInputService : PlatformTextInputService`;
2. `PlatformContext.textInputService` 默认值替换(PlatformContext.kt:140);
3. `ComposeScreen.preeditUpdated` 重写,转发组合串;
4. preedit → `SetComposingRegionCommand` / `SetComposingTextCommand` / `CommitTextCommand`;
5. 候选窗:缓存光标矩形 → `TextInputManager.setTextInputArea`。

### 4.3 I1:键码映射收敛(重构而非重写)

- 保留 `Key.kt`(AWT VK 契约,官方移植源码,不动);
- 映射表 `mcKeyToComposeKey` 已是唯一转换点(ComposeInputBridge.kt),确认无散落
  转换;补充完整键覆盖(GLFW→VK 全表核对)与注释;
- 核对 A-Z/0-9 直通段与显式映射段的边界。

### 4.4 I6:焦点/导航核对

- 保留 `RootNodeOwner.handleFocusKeys`(Compose 内部焦点);
- 核对 Tab 键在 Compose 焦点与 MC Screen 导航之间不冲突(Compose 优先消费,
  未消费才回落 MC 默认)。

### 4.5 I9:指针图标(可选)

- 评估 MC 光标接口(如 `Mouse`/`GLFW` 光标设置),接 `setPointerIcon`;
- 低优,单独排期。

---

## 5. 分阶段执行计划

### 阶段 1:IME 接入(I3)——文本输入原生化核心
- 新建 `MinecraftTextInputService` + preedit 转发 + 候选窗;
- 构建验证 + dev scene 实测(中文拼音组合态、候选上屏、光标跟随);
- 交付:输入框聚焦弹系统 IME、组合态可见。

### 阶段 2:键码映射收敛与核对(I1/I5)
- 核对/补齐 `mcKeyToComposeKey` 全键映射;
- 核对滚轮常量与 MC 语义;
- 构建 + dev scene 实测(方向键/Home/End/Tab/标点/小键盘)。

### 阶段 3:焦点/生命周期收尾(I6 + 输入清理)
- 核对 Tab 焦点不冲突;
- 失焦/关闭时 `stopInput` + preedit 清理(removed/onClose);
- 全量构建 + runClient 回归。

### 阶段 4(可选):指针图标(I9)
- MC 光标接入 `setPointerIcon`。

---

## 6. 验证标准

- 每阶段:`build_project` 全量构建零错误;
- dev scene(`TextInputDevScene`):
  - 中文输入法:聚焦弹出 IME、拼音组合态显示、候选上屏、光标移动候选窗跟随;
  - 英文/数字/标点直输、退格/方向键/Home/End/Delete;
  - Tab 焦点导航不受影响;
  - 鼠标点击定位光标、选区拖动手柄(无触觉反馈但行为正常);
- 回归:既有 dev scene(样式/滚动/几何)不回归。

---

## 7. 风险与边界

- **preedit 与 charTyped 重复**:IME 上屏可能同时触发 charTyped 与 preedit 提交,
  需实测去重(阶段 1 重点验证);
- **组合区与选区互斥**:preedit 期间隐藏普通选区,官方 SetComposingRegion 语义;
- **Key 契约不改**:AWT VK 编码是官方契约,只改桥接层,不改移植源码;
- **不引入依赖**:无新增 Gradle 依赖(IMBlocker 等后续按需评估,仅保留接口);
- **MC 版本耦合**:TextInputManager/PreeditEvent 为 26.2 API,升级 MC 需复核。

---

## 8. 相关文档与文件

- `mc-ime-service-plan.md`(IME 接入详细设计,阶段 1 直接依据);
- `mc-narration-research.md`(读屏调研,与输入体系独立);
- 输入链路文件:
  - `platform/ComposeScreen.kt`(输入桥接入口)
  - `platform/ComposeInputBridge.kt`(键码/修饰键/滚轮映射)
  - `platform/MinecraftComposeScene.kt`(场景输入转发)
  - `androidx/.../text/input/TextInputService.kt`(PlatformTextInputService 接口)
  - `androidx/.../text/input/internal/TextInputSession.platform.kt`(MinecraftPlatformTextInputMethodRequest)
  - `androidx/.../ui/platform/PlatformContext.kt`(textInputService 默认值)
