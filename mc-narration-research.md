# Minecraft 读屏(Narration)系统调研与 Compose 接入方案

> 调研对象:MC 26.2(vanilla-26.2-2-sources.jar,路径 `common/build/moddev/artifacts/`)
> 目的:梳理 MC 原版叙述者(Narrator)读屏机制的完整链路,评估 Compose 场景
> (经 `ComposeScreen` 桥接)接入原版朗读链的可行性与方案。
> 结论先行:MC 自带完整 TTS 读屏链路;Compose 侧只需在 `ComposeScreen` 桥接层
> 把 Compose 语义树映射到 MC 的 `NarrationElementOutput`,即可复用原版朗读,
> 无需自建任何 TTS/读屏能力。

---

## 1. MC 读屏系统全貌

### 1.1 相关类一览(均在 `net.minecraft.client.*`)

| 类 | 作用 |
|---|---|
| `gui/narration/NarratableEntry` | 可朗读组件的接口。`NarrationSupplier` + `TabOrderedElement`;含 `narrationPriority()`(NONE / HOVERED / FOCUSED)、`getNarratables()`(默认返回自身列表) |
| `gui/narration/NarrationSupplier` | 单一方法 `updateNarration(NarrationElementOutput)`——组件把自身描述写进输出 |
| `gui/narration/NarrationElementOutput` | 输出收集器:按 `(NarratedElementType, depth)` 追加 `NarrationThunk`;`nest()` 进入子层 |
| `gui/narration/NarratedElementType` | 元素类型枚举:`TITLE` / `POSITION` / `HINT` / `USAGE` |
| `gui/narration/NarrationThunk` | 一段朗读文本的载体(可延迟求值) |
| `gui/narration/ScreenNarrationCollector` | 屏幕级收集器:按 `(type, depth)` 树形聚合,去重"已朗读"条目,`collectNarrationText(force)` 拼装最终文本 |
| `client/GameNarrator` | 输出端封装:按 `NarratorStatus` 过滤,调 `com.mojang.text2speech.Narrator.say(...)` 真发声 |
| `client/NarratorStatus` | 朗读开关枚举:`OFF` / `ALL` / `CHAT` / `SYSTEM`(游戏选项 `options.narrator`) |
| `gui/screens/AccessibilityOptionsScreen` | 无障碍选项屏幕(含叙述者开关按钮) |
| `gui/screens/AccessibilityOnboardingScreen` | 首次启动无障碍引导屏 |

### 1.2 朗读触发链路(关键路径)

```
用户操作(鼠标/键盘)
  └─ Screen.afterMouseMove() / afterMouseAction() / afterKeyboardAction()
       └─ scheduleNarration(delay, ignoreSuppression)   // 移动 750ms,操作 200ms
            └─ 设 nextNarrationTime

每帧驱动(Gui.java:138)
  └─ Gui.render → screen.handleDelayedNarration()
       ├─ shouldRunNarration(): DEBUG_UI_NARRATION || Narrator.isActive()
       ├─ 到点且未被抑制 → runNarration(true)
       │    ├─ narrationState.update(this::updateNarrationState)   // 重新收集
       │    │    ├─ add(TITLE, getNarrationMessage())              // 屏幕标题
       │    │    └─ updateNarratedWidget(output)                   // 遍历 narratables
       │    │         ├─ 过滤 isActive、按 tabOrderGroup 排序
       │    │         ├─ findNarratableWidget(...)                 // 找 FOCUSED/HOVERED 项
       │    │         ├─ add(POSITION, "narrator.position.screen")
       │    │         ├─ add(USAGE, getUsageNarration())
       │    │         └─ result.entry.updateNarration(output.nest()) // 组件自述
       │    └─ narrationState.collectNarrationText(onlyChanged)    // 拼 ". " 分隔文本
       │         └─ minecraft.getNarrator().saySystemNow(narration)
       │              └─ Narrator.say(text, interrupt, voiceVolume)  // com.mojang.text2speech TTS
```

要点:
- **朗读对象 = 屏幕的 `narratables` 列表**(`Screen.addWidget` / `addRenderableWidget` 自动登记实现 `NarratableEntry` 的组件);
- **优先级**:`NarrationPriority.FOCUSED` 为终态优先朗读,其次 `HOVERED`,再 `NONE`;只有"变化"的内容会重复朗读(`ScreenNarrationCollector.alreadyNarrated` 去重);
- **输出**:`GameNarrator` → `com.mojang.text2speech.Narrator`(TTS 库),音量走 `SoundSource.VOICE`;
- **开关**:游戏选项 `options.narrator`(NarratorStatus),聊天/系统消息朗读也复用同一输出端。

---

## 2. Compose 接入方案

### 2.1 可行性:高

- 我们的 `ComposeScreen` 直接继承原版 `Screen`(`platform/ComposeScreen.kt`),天然具备 narration 挂点;
- Compose 侧语义树(`androidx.compose.ui.semantics.*`)已完整移植,焦点节点的 `text` / `contentDescription` / `role` / `stateDescription` 等语义属性都在;
- 输出端 `Minecraft.getInstance().getNarrator()` 直接可用,零额外依赖。

### 2.2 建议接入路径(分步)

1. **桥接层收集 Compose 语义**:在 `ComposeScreen` 内重写/补充 narration 数据源——
   - 方案 A(推荐):重写 `updateNarratedWidget(NarrationElementOutput)`,从 `MinecraftComposeScene` 的当前焦点语义节点(经 `SemanticsOwner` / 焦点链)读取 `SemanticsConfiguration`,把 `text`/`contentDescription` 转成 `output.add(TITLE, ...)`;
   - 方案 B:把整个 Compose 场景注册为单个 `NarratableEntry`(在 `getNarratables()` 返回),由桥接层内部自行决定朗读哪段文本——封装性更好,与原版组件级朗读解耦。

2. **触发沿用原版**:复用 `afterMouseMove` / `afterKeyboardAction` / `handleDelayedNarration` 机制(默认实现已可用,无需自建调度)。

3. **朗读内容策略**(业务侧):
   - 焦点变化时朗读当前聚焦控件的 `text` / `contentDescription`;
   - 组合/折叠状态变化可附加 `stateDescription`(`NarratedElementType.HINT`);
   - 多控件屏幕可输出 `POSITION`("第 n 个,共 m 个")对齐原版习惯。

4. **与已知限制的边界**:
   - 输入框 preedit(IME 组合态)不朗读——读屏/朗读本身未实现(见 AGENTS.md 已知限制,
     与输入侧 preedit 渲染无关);
   - 朗读文本来自 Compose 语义,MC 的 `NarratorStatus`(OFF/CHAT/SYSTEM)与 VOICE 音量直接生效。

### 2.3 与本项目已删代码的关系

- 之前移除的 `AccessibilityManager` / `DefaultAccessibilityManager`(C2)是"推荐交互超时"接口(Android 语义),**与朗读无关**,不影响本方案;
- 本方案不新增任何 AWT/Swing/Skiko/Desktop 依赖,符合 AGENTS.md 约束 #2。

---

## 3. 备忘:未来开发时的注意点

- `Screen.getNarrationMessage()` 默认返回屏幕标题(`Component.literal("Compose Screen")`),接入时应让标题来自场景(或置空);
- `shouldRunNarration()` 依赖 `Narrator.isActive()`——TTS 库未初始化时整链静默,开发调试可用 `SharedConstants.DEBUG_UI_NARRATION`(IDE 内 `IS_RUNNING_IN_IDE` 时 `GameNarrator` 也会 log);
- `ScreenNarrationCollector` 按 `(type, depth)` 聚合、只朗读"变化"内容,桥接层若直接塞整段文本会因内容不变被去重——需要按焦点变化输出;
- `ComposeScene` 的焦点系统(`FocusOwnerImpl` 等)与 MC 焦点是两套,桥接需在"Compose 焦点变化"时触发 `triggerImmediateNarration(false)`(原版仅在鼠标/键盘操作后调度);
- 参考原版实现文件:Guava 的 `NarratableEntry`、`Screen.updateNarratedWidget`、`GameNarrator`(路径见第 1 节表)。