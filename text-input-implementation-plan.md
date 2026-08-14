# 文本输入(TextField)实施计划

> 目标:让 `BasicTextField` 在 Minecraft 平台真正可用 —— 中英文字符输入、
> 光标移动、选区、退格/删除,以及剪贴板。
>
> **范围澄清:我们只做"文本输入",不做"输入法提示"**(候选窗口定位、组合态
> 下划线渲染、双向光标反馈均不在范围内;组合过程由系统输入法自己的候选窗口负责)。
>
> **状态:计划已确认,实现留待后续会话执行。**

## 1. 现状与缺口

### 已有(源码已随平台 JAR 内嵌)

- `BasicTextField` / `CoreTextField` / `TextFieldState` / `TextFieldDelegate` 全量源码;
- 输入管线:`TextFieldKeyEventHandler`、`TextFieldBuffer`、`GapBuffer`、
  `TextInputSession.platform.kt`、`PlatformTextInputModifierNode` 等;
- **焦点系统已通**:TextField 可获得焦点、接收硬件键盘 KeyDown/KeyUp;
- 硬件键盘的**控制键**(方向键、退格、Delete、Home/End 等)已经能经
  `dispatchKeyEvent` 路由到 TextField 的 KeyInput 节点。

### 缺口

| 缺口 | 影响 | 本次 |
|---|---|---|
| `charTyped` 未转发 | 中英文字符无法插入 | 必做(I.1) |
| 剪贴板空实现 | Ctrl+C/V/X 无效 | 必做(I.2) |

## 2. 技术链路(已核实)

MC 的 `Screen.charTyped(CharacterEvent)` 在字符上屏时被调用,
`CharacterEvent(int codepoint)` 提供 `codepointAsString()`。

Compose 侧字符输入**不经过** `TextInputService`,而是通过
`KeyEvent` 的 **codePoint** 字段:

1. `KeyEvent.isTypedEvent = utf16CodePoint != 0`
2. `TextFieldKeyEventHandler.onKeyEvent` 见到 `isTypedEvent` →
   `deadKeyCombiner.consume(event)` 取 codePoint → 插入文本

因此最小闭环是:

```
MC charTyped(CharacterEvent)
  → KeyEvent(key = Key.Unknown, type = KeyDown, codePoint = event.codepoint)
  → scene.sendKeyEvent(...)
  → TextFieldKeyEventHandler(isTypedEvent) → 插入字符
```

关键 API 均已确认:
- `androidx.compose.ui.input.key.KeyEvent(key, type, codePoint=0, ...)` 工厂(已拷贝);
- `MCKeyEvent` / `CharacterEvent` 结构(已反编译核实);
- `ComposeScreen` 已 override 键盘/鼠标,补齐 `charTyped` 即可。

## 3. 实施步骤

### 阶段 I.1 —— 中英文字符输入(charTyped 转发,核心)

1. `ComposeScreen` 新增 `charTyped(CharacterEvent)` override:
   - 构造 `KeyEvent(Key.Unknown, KeyDown, codePoint = event.codepoint)`;
   - 经字符过滤开关(见 §5)决定是否转发;
   - `composeScene.sendKeyEvent(...)`,返回是否被消费;
2. `MinecraftComposeScene` 复用现有 `sendKeyEvent`(无需新 API);
3. dev scene 增加一个 `BasicTextField`,验证:
   - 英文/数字/标点直接输入;
   - **中文输入法**:拼音→候选→上屏,确认上屏字符正确进入 TextField;
   - 退格、方向键移动光标、Home/End、Delete;
   - 选区(Shift+方向键)。

**验收**:中英文均可输入;退格与方向键正常;不崩溃。

> 说明:中文输入法上屏时系统同样触发 `charTyped`,因此本阶段天然覆盖中文,
> 无需额外工作。输入法组合过程(拼音显示、候选选择)由系统自己的候选窗口
> 负责,不在范围内。

### 阶段 I.2 —— 剪贴板(必做)

- `PlatformContext` 的 `clipboardManager` 当前为空实现;
- **参考 ibuki_gourd 的剪贴板实现,但排除 AWT 部分**:
  - 不用 `java.awt.Toolkit.getSystemClipboard()`;
  - 改用 MC 侧能力(GLFW 剪贴板 `glfwGetClipboardString` / `glfwSetClipboardString`,
    或 MC 自己的剪贴板封装);
- 接通 Ctrl+C/V/X 快捷键(`TextFieldKeyEventHandler` → 剪贴板服务)。

**验收**:选中文本 Ctrl+C 复制、Ctrl+V 粘贴、Ctrl+X 剪切。

### 可选增强(默认不做)—— preedit 组合态显示

输入框内显示未上屏拼音组合串(下划线)—— 属于"输入法提示"层面,
仅在需要"输入框内预览拼音"时再做,不影响字符输入本身。

## 4. 已确认决策(用户拍板)

| 决策点 | 结论 |
|---|---|
| 范围 | 文本输入(charTyped 覆盖中英文)+ 剪贴板;不做输入法提示 |
| 剪贴板 | 必做,参考 ibuki_gourd 但排除 AWT |
| 字符过滤 | **不实现具体过滤**,提供 ComposeLocal 开关(见 §5) |
| preedit 组合态 | 可选增强,默认不做 |
| 实现 | 留待后续会话,本对话只固化计划 |

## 5. 字符过滤 ComposeLocal 开关

不做任何具体字符过滤逻辑(如 MC 的 `isAllowedChatCharacter`),但暴露一个
`ComposeLocal` 开关,由业务方决定过滤策略:

```kotlin
// 平台提供(默认不过滤)
val LocalCharFilter: CompositionLocal<(Int) -> Boolean> =
    compositionLocalOf { { true } }  // 接受所有 codepoint

// 业务方按需覆盖
CompositionLocalProvider(LocalCharFilter provides { codepoint ->
    codepoint != 0x00A7  // 例:屏蔽节号 §
}) {
    // TextField 内容
}
```

设计要点:
- `charTyped` 的 codepoint 在进入 TextField **之前**经过此过滤器;
- 过滤器的读取发生在输入分发链上(场景/节点树内,可访问 CompositionLocal);
- 默认 `{ true }` 全放行,不影响现有行为。

## 6. 验证方案

- dev scene 加 `BasicTextField`(Fabric 端,复用现有 devOnly 机制);
- 手动验证:英文输入、**中文输入法上屏**、退格、方向键、Home/End、Delete、
  选区、剪贴板复制/粘贴/剪切;
- 打包验证:`build + :common:compileDevOnlyKotlin`,确认无新引入
  AWT/Skiko/desktop 依赖(剪贴板部分尤其要复查 AWT);
- 回归:确认现有矩形/文字/输入/焦点阶段不回归。

## 7. 风险

- `KeyEvent.Unknown` 作为 typed event 的 key 是否满足 `TextFieldKeyEventHandler`
  的所有分支(需实测,若 type 需为 `KeyEventType.Unknown` 则调整);
- 剪贴板去 AWT 后,GLFW 剪贴板与 MC 环境/操作系统的兼容性(需实测);
- 中文输入法上屏路径因平台/输入法而异,需实测确认 `charTyped` 覆盖;
  (若个别输入法走 `preeditUpdated` 而非 `charTyped` 上屏,再评估组合态降级方案)。
