# MC 输入法(IME)连接 Service 实施计划

> 目标:让 Compose TextField 聚焦时像原版文本编辑框一样弹出系统输入法,
> 并支持 IME 组合态提示(preedit)与候选窗跟随光标。
> 当前状态:charTyped(字符上屏,含中文输入法上屏)已接通;IME 弹窗与
> 组合态 preedit 未实现(AGENTS.md 已知限制)。
> 本文档为实施指引,由后续执行者按步骤落地。

---

## 1. 背景与现状

### 1.1 当前输入链路(已工作部分)

```
TextField 聚焦 → TextInputSession 构造 MinecraftPlatformTextInputMethodRequest
  → PlatformContext.startInputMethod(request)  (PlatformContext.kt:142, 当前 awaitCancellation 挂起)
       └─ RootNodeOwner.TextInputSession.startInputMethod
            └─ textInputService.startInput()/stopInput()
                 └─ PlatformContext.textInputService (PlatformContext.kt:140)
                      = EmptyPlatformTextInputService  ← 空实现:什么都不做

字符上屏(已通):
MC KeyboardHandler.charTyped → ComposeScreen.charTyped(CharacterEvent)
  → composeScene.sendKeyEvent(typed KeyEvent) → TextField 插入文本
```

### 1.2 缺失部分

1. **不弹输入法**:`EmptyPlatformTextInputService` 空实现,从未调用 MC 的
   `TextInputManager.startTextInput()` → GLFW IME 未启用,输入法候选窗不出现;
2. **无组合态提示(preedit)**:MC 有 preedit 回调(`PreeditEvent`),但
   `ComposeScreen` 未重写 `preeditUpdated` 转发到 Compose 编辑缓冲;
3. **候选窗不跟随光标**:`notifyFocusedRect`/`updateTextLayoutResult` 未接
   `TextInputManager.setTextInputArea`。

---

## 2. MC 侧能力(已调研确认,26.2)

### 2.1 IME 启停与候选窗位置 — `com.mojang.blaze3d.platform.TextInputManager`

```java
// Minecraft.getInstance().textInputManager()
public void startTextInput();                      // 启用文本输入(IME 保持开启)
public void stopTextInput();                       // 禁用
public void setTextInputArea(int x0, int y0, int x1, int y1);
    // glfwSetPreeditCursorRectangle,坐标乘 guiScale,候选窗跟随
public void notifyIMEChanged();                    // 由 KeyboardHandler 在 IME 状态变化时调用
public void onTextInputFocusChange(boolean focused); // 焦点变化(内部调 start/stop)
```

### 2.2 组合串回调 — `net.minecraft.client.input.PreeditEvent`

```java
// KeyboardHandler.setup 注册的 GLFW preedit callback 构造:
public record PreeditEvent(String fullText, int caretPosition, List<String> blocks, int focusedBlock)
// null = 组合结束(提交或取消)
```

- 转发点:`KeyboardHandler.submitPreeditEvent(GuiEventListener, PreeditEvent?)`
  → `GuiEventListener.preeditUpdated(PreeditEvent?)`(默认返回 false,Screen 可重写);
- `KeyboardHandler.resubmitLastPreeditEvent(screen)` 可重发最后组合串(焦点恢复时用)。

### 2.3 接入点确认

- `Minecraft.getInstance().textInputManager()` — 存在(KeyboardHandler.java:624 引用);
- `Screen` 继承 `GuiEventListener` → 重写 `preeditUpdated` 即可接收组合串;
- IME 输入模式常量 `GLFW_IME = 208903`(TextInputManager 内部使用,勿直接依赖 LWJGL,
  走 `TextInputManager` 抽象)。

---

## 3. Service 设计

### 3.1 新文件

**`common/src/main/kotlin/moe/forpleuvoir/compose_minecraft/platform/textinput/MinecraftTextInputService.kt`**

实现 Compose 接口 `androidx.compose.ui.text.input.PlatformTextInputService`
(定义于 `common/src/main/kotlin/androidx/compose/ui/text/input/TextInputService.kt:290`):

```kotlin
internal class MinecraftTextInputService : PlatformTextInputService {

    // ── 会话状态 ──
    private var onEditCommand: ((List<EditCommand>) -> Unit)? = null
    private var currentValue: TextFieldValue = TextFieldValue("")

    // ── 会话生命周期 ──
    override fun startInput(value, imeOptions, onEditCommand, onImeActionPerformed) {
        this.onEditCommand = onEditCommand
        this.currentValue = value
        Minecraft.getInstance().textInputManager().startTextInput()
    }

    override fun stopInput() {
        Minecraft.getInstance().textInputManager().stopTextInput()
        onEditCommand = null
    }

    override fun showSoftwareKeyboard() { Minecraft.getInstance().textInputManager().startTextInput() }
    override fun hideSoftwareKeyboard() { Minecraft.getInstance().textInputManager().stopTextInput() }

    // ── 状态同步 ──
    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {
        currentValue = newValue
    }

    // ── 候选窗跟随光标 ──
    override fun notifyFocusedRect(rect: Rect) { /* 见 3.3 */ }
    override fun updateTextLayoutResult(...) { /* 缓存布局,见 3.3 */ }

    // ── preedit 转发(由 ComposeScreen 调用)──
    fun onPreeditChanged(event: PreeditEvent?) { /* 见 3.2 */ }
}
```

> 注:接口方法签名以 `TextInputService.kt:290-364` 为准(startInput 有两个重载,
> updateTextLayoutResult 带 OffsetMapping/Matrix 参数)。

### 3.2 preedit → EditCommand 转换(核心)

```
PreeditEvent(fullText, caretPosition, blocks, focusedBlock)

event != null(组合中):
  onEditCommand(listOf(
      SetComposingRegionCommand(start, end),   // 标记组合区(替换现有选区)
      SetComposingTextCommand(fullText, caretPosition)  // 组合文本+光标
  ))

event == null(组合结束,提交/取消):
  onEditCommand(listOf(CommitTextCommand(组合串或空串)))  // 按最终文本提交
```

- 组合区范围:替换当前 `currentValue.selection`(或从 0 到文本长度);
- `SetComposingTextCommand(text, cursor)` 语义见移植源码
  `androidx/compose/ui/text/input/EditCommand.kt`(Compose 标准命令,行为保持官方)。

### 3.3 候选窗跟随光标

- `notifyFocusedRect(rect)` / `updateTextLayoutResult(...)`:缓存光标所在矩形
  (Compose 布局坐标,密度 1 = GUI 单位);
- 在每次 preedit 更新时调用:
  `Minecraft.getInstance().textInputManager().setTextInputArea(l, t, r, b)`
  (方法内部乘 guiScale,与 TextInputManager 语义一致);
- 参考 ibuki_gourd `MinecraftPlatformContext.imeVerticalOffset()`(可选偏移,第一版可不做)。

---

## 4. 落地步骤

1. **新建 `MinecraftTextInputService.kt`**(3.1/3.2/3.3 内容);
2. **`PlatformContext.textInputService` 默认值替换**(PlatformContext.kt:140):
   `EmptyPlatformTextInputService` → `MinecraftTextInputService()`;
   `EmptyPlatformTextInputService` 若再无引用可删除;
3. **`ComposeScreen` 重写 `preeditUpdated`**(ComposeScreen.kt):
   ```kotlin
   override fun preeditUpdated(event: PreeditEvent?): Boolean {
       composeScene.textInputService?.onPreeditChanged(event)
       return true   // 已消费
   }
   ```
   需要把 service 引用从 PlatformContext 传递到 ComposeScreen(经
   `MinecraftComposeScene` 持有,或 `PlatformContext.textInputService` 全局读取);
4. **构建验证**:`build_project` 全量;
5. **dev scene 实测**(fabric runClient):
   - TextInputDevScene 聚焦输入框 → 应弹出系统输入法;
   - 中文拼音组合态 → 输入框内显示下划线组合文本;
   - 候选选择上屏 → 组合态消失、文本提交;
   - 光标移动 → 候选窗跟随。

---

## 5. 边界与注意事项

- **不引入 LWJGL/GLFW 直调**:IME 开关与候选窗全走 `TextInputManager` 抽象
  (AGENTS.md 约束 #5);
- **组合区与选区互斥**:preedit 期间应隐藏/替换普通选区显示(Compose 官方语义
  SetComposingRegion 处理);
- **与 charTyped 并存**:组合态字符走 preedit,确认上屏的最终字符可能同时触发
  charTyped —— 需实测去重(MC 行为:IME 上屏走 commit,charTyped 是否重复待验证);
- **焦点丢失**:`ComposeScreen.removed`/失焦时调 `stopInput` + `onPreeditChanged(null)`;
- **候选窗偏移**:第一版直接用 `textFieldRectInRoot` 的左上角,不做 IME 垂直偏移;
- **保持 Compose 行为逻辑**:只替换"与 MC IME 的通信",文本编辑/光标/选区逻辑
  保持 Compose 原版(AGENTS.md 约束 #7)。

---

## 6. 相关参考

- MC 源码:vanilla-26.2-2-sources.jar(common/build/moddev/artifacts/)
  - `com/mojang/blaze3d/platform/TextInputManager.java`
  - `net/minecraft/client/input/PreeditEvent.java`
  - `net/minecraft/client/KeyboardHandler.java`(setup 注册 preedit callback、submitPreeditEvent)
  - `net/minecraft/client/gui/components/events/GuiEventListener.java`(preeditUpdated)
- Compose 接口:`common/src/main/kotlin/androidx/compose/ui/text/input/TextInputService.kt`
  (PlatformTextInputService:290-364;TextInputSession 会话管理)
- 参考实现(ibuki_gourd,不直接抄):`MinecraftPlatformContext.startInputMethod` +
  `IMBlockerFocusSession`(含候选窗位置/光标矩形跟踪思路)
- 本平台现有:`MinecraftPlatformTextInputMethodRequest`(TextInputSession.platform.kt:234,
  已从 SkikoPlatformTextInputMethodRequest 改名)