# AGENTS.md — Compose Minecraft Platform Mod

> 本文件是面向 AI 编码代理(agent)的项目约定。请先完整阅读再开始工作。

## ⚠️ 特别标注:必须优先使用 IntelliJ IDEA 的 MCP 工具

本项目通过 **JetBrains IntelliJ IDEA 的 MCP(Model Context Protocol)服务器**进行开发。
Agent 的 IDE 工具集中以 `mcp__idea__*` 前缀暴露。**所有代码阅读、检索、构建与运行
操作都应优先使用 IDEA MCP 工具,而不是 shell / 文件系统工具**:

| 场景 | 使用工具 |
|---|---|
| 读取文件内容 | `mcp__idea__read_file`(支持 jar/class 反编译,如 MC 源码) |
| 检索符号 / 类 / 方法 | `mcp__idea__search_symbol`(语义检索,优于文本搜索) |
| 分析调用关系 | `mcp__idea__analyze_calls`(INCOMING/OUTGOING) |
| 编译 / 构建验证 | `mcp__idea__build_project`(触发 Gradle 构建并返回错误) |
| 静态检查 | `mcp__idea__get_file_problems` / `mcp__idea__lint_files` |
| 查看可运行配置 | `mcp__idea__get_run_configurations` |
| 运行 / 调试(如 fabric runClient) | `mcp__idea__execute_run_configuration`(或 `mcp__idea__xdebug_*` 调试) |
| IDE 终端执行命令 | `mcp__idea__execute_terminal_command` |
| 文件树 / 目录结构 | `mcp__idea__list_directory_tree` |

> 例外:批量文件操作(如删除、移动多个文件)可回退到 shell;但**所有构建操作
> 必须通过 IDEA MCP 完成**,禁止使用 `gradlew` shell / IDE 终端跑 Gradle 任务,
> 禁止绕过构建直接手改产物。

## 项目概述

在 Minecraft(Fabric + NeoForge 双 Loader)中运行 Compose Multiplatform UI 的基座 Mod。

- **无 Skia / Skiko / Desktop / Material**:所有绘制进入 Minecraft 当前帧的
  `GuiRenderState`(与 Vulkan/OpenGL 渲染后端无关);
- **原版 `Screen` 桥接**:Compose 场景通过 `net.minecraft.client.gui.screens.Screen`
  挂入 Minecraft(无帧钩子 mixin、无渲染注入 mixin、无 loader 事件;
  仅 StyleAccessor 只读字段 mixin,见 `common/src/main/.../mixin/StyleAccessor.java`);
- **场景密度固定 1**:1dp == 1 GUI 单位,坐标无需换算;
- **文字**:MC Font 度量统一,行高 9px 固定,`fontSize` 第一版被忽略;
- **发布 JAR 内嵌完整 Compose 运行时**(约 4000+ 个 `androidx.compose.*` 类),
  消费者无需引入任何 Compose/Skiko 依赖。

## 目录结构

```
buildSrc/                      # Gradle 约定插件(multiloader-common / multiloader-loader)
common/                        # 平台核心(单模块)
  src/main/kotlin/
    moe/forpleuvoir/compose_minecraft/platform/     # 自有代码:Screen 桥接、场景宿主、渲染上下文
    androidx/compose/**                            # 从 CMP 1.11 移植的运行时源码(expect/actual 剥离)
  src/devOnly/kotlin/           # dev 测试代码(dev scene),仅 Fabric dev run 生效
fabric/                        # Fabric loader 模块(includeInternal 内嵌依赖)
neoforge/                      # NeoForge loader 模块(jarJar 内嵌依赖,无 devOnly)
```

## 构建与运行

> **所有构建/编译验证一律通过 IDEA MCP 的 `mcp__idea__build_project` 完成,
> 绝不使用 `gradlew` shell 命令或 IDE 终端跑 Gradle 任务。**
> Agent 需要在构建章节处理 Gradle 任务时,用 `build_project` 触发 IDE 的
> Gradle 集成(支持 `filesToRebuild` 指定文件做增量验证 / `rebuild=true` 全量构建)。

```
# 完整构建(必须全量 build;单任务会漏编译 devOnly)
build_project(rebuild=true)

# 指定文件增量验证(如只验 devOnly 改动)
build_project(filesToRebuild=[common/src/devOnly/.../*.kt, ...])

# 打包发布 JAR:经 build_project 全量构建触发各 loader 的 jar 任务
# (IDE 构建 = Gradle build,产物在 fabric/build/libs、neoforge/build/libs)
build_project(rebuild=true)

# 统一输出到 modJar/<mc>/<version>/:由全量构建后 Gradle 任务产出
# (AGENTS 不再手写 shell,由 build_project 触发)
```

- **dev 测试统一在 Fabric 端**(`Compose-Minecraft [:fabric:runClient]` run configuration,
  经 `mcp__idea__execute_run_configuration` 调用,**不经终端**);
  NeoForge(ModDevGradle)不支持 devOnly 源码集,只做发布构建;
- dev scene(`MinecraftDevSceneContent`)在主菜单自动打开,用于验证渲染/输入/焦点;
- 首次运行 runClient 较慢(Gradle 预热),确认 java 进程存在即可。

## 架构约束(必须遵守)

1. **架构决策必须询问用户**,不要自行决定架构/设计方向;
2. **不引入** Skia/Skiko/Desktop/Material 依赖或代码;
3. **平台只提供基础能力,不做风格化**:定位类似 compose-ui/foundation 的
   Basic 层级,类似 Compose Material 的主题系统/默认组件外观不考虑;
   文本组件的 `McTextStyle` 是 MC `Style` 的基础封装,`McText`/`McTextField`
   无默认外观,UI 长什么样由业务方决定;
4. 渲染只消费 MC 的 `GuiRenderState`(`extractRenderState` 每帧驱动),不做离屏渲染;
5. 输入键码映射使用 MC 的 `InputConstants` 抽象(不直接绑定 GLFW/LWJGL);
6. Compose 的 `Key` 编码即 AWT VK 值(库源码契约),桥接层不要引用 AWT 类型;
7. 修改 `androidx/compose/**` 移植源码时保持与官方语义一致,标注平台适配点;
8. 焦点相关:`onFocusChanged` 必须放在焦点目标(`focusable`/`clickable`)**之前**;
   `clickable` 自带焦点目标,不要与 `focusable` 叠加(会造成 Tab 循环);
   `onKeyEvent` 不要无条件消费导航键(Tab/方向键),否则焦点导航失效。
9. **反射必须经用户确认**:禁止未经确认直接写反射代码;优先用 Mixin @Shadow/
   注入点、公开 API 等非反射方式。
10. **Mixin 可注入 lambda 合成方法**:`@Redirect(method = "lambda$sortElements$0", ...)`
    可定向到 JDK 编译器生成的 lambda 合成方法(命名规则 `lambda$<方法名>$<序号>`,
    稳定)。lambda 体内的调用无法用普通方法名命中。

## 移植源码维护经验(与调试)

- **对照官方源码**:`androidx/compose/**` 是 CMP 1.11 移植源码,官方 sources jar 在本地
  Gradle 缓存中(foundation-desktop / ui-text-desktop 等)。改动前先对照官方实现,
  避免引入与官方语义不符的自研逻辑。
- **警惕占位实现**:移植中常见 `= this` / `= null` / `= false` / `map` 恒返回 `null`
  等占位(expect/actual 剥离后未补全)。"某功能完全失效/行为怪异"时优先排查这类占位。
- **只替换渲染,不动行为**:平台适配点只替换"实际绘制到 MC GuiRenderState"的部分;
  滚动/光标/选区/输入等行为逻辑保持 Compose 原版。自研行为逻辑(额外截断、偏移、截取)
  易破坏多行与滚动场景。
- **文本行边界语义**:行尾为 exclusive end(含 `\n`),行归属用半开区间 `[start, end)`;
  选区、光标、命中测试的行换算均依赖此语义,改动需保持一致。
- **运行时诊断**:静态分析到极限时,在关键路径加 `println`(输出到 MC 客户端日志
  `fabric/runs/client/logs/latest.log`);用 `System.identityHashCode` 区分同类的多个
  节点实例;定位后移除日志再提交。
- **输入桥接**:MC `KeyEvent.modifiers` 为位标志(Control=2 / Shift=1 / Alt=4,
  见 `InputWithModifiers`),键码为 GLFW 值(经 `InputConstants` 抽象),Compose `Key`
  为 AWT VK 编码;桥接层只做映射,不直接绑定 LWJGL/AWT 类型。

### 阴影实现踩坑记录(T.14,GPU 距离场方案)

**方案演进**:CPU alpha 场 + 高斯卷积 + 纹理上传(逐像素 + 上传)性能不可接受
(40+ 阴影 16FPS)→ 重构为 **GPU 距离场**:CPU 只三角化 + 每顶点距离
(`GeometryTessellator.shadowFill`),`gui_shadow` shader 用高斯模糊解析解
`alpha = A/2·erfc(d/(σ√2))` 生成软阴影,网格 LRU 缓存后静态场景零 CPU。

- **Skia SkShadowUtils 参数语义**(官方 Compose/Skiko 的阴影):`σ = e·lightRadius/lightHeight/2`
  (默认 800/600 → 0.667e,扩散**随 elevation 增大**);`alpha = (0.039+0.19)·(1-e/600)`
  (ambient 0.039 无偏移 + spot 0.19 偏移);spot 偏移 `= -(lightXY-center)·zRatio`,
  `zRatio = e/(600-e)`。**elevation 不是独立参数,扩散/浓度/偏移全部由它派生**。
- **erfc 符号(致命坑)**:顶点 coverage 语义是"**内部为正、外部为负**"
  (GeometryTessellator 约定)。高斯模糊半平面解 `A/2·erfc(d')` 在**内部 d' 正时
  衰减到 0** —— 直接套用会得到"阴影中心空心、边缘(轮廓处 0.5)反而浓、
  外部实心"的完全相反效果。正确写法 `alpha = A/2·(1+erf(d'))`。**符号写反时
  视觉 = "描边投影、中间空、最外硬边",与预期完全相反,极易误判为其他问题。**
- **外扩带 coverage 必须为负**(外部 = 负距离),与 `fillPolygon` 三环结构一致;
  写成正数会导致外部区域 alpha ≥ 0.5(全黑) + 3σ 硬截止。
- **自交 Path 有向面积恰为 0**:`fillPolygon` 的退化检查(面积 < 1e-6 直接 return)
  会把自交对称图形(蝴蝶结 X 形)当退化丢弃 → 图形"不见"且 `splitFill` 永不执行。
  **修复:findCrossings 自交检测必须优先于面积检查**。
- **splitFill 环遍历遇"已用子边"丢环**:EvenOdd 子环遍历到 used 边时 `ok=false`
  丢弃整个环 → 自交图形只渲染一半;已收集 ≥3 点时补起点闭合输出(部分环兜底)。
- **`Modifier.shadow` 顺序**:shadow 必须写在内容(background)之前 —— graphicsLayer
  包裹其后所有修饰符;写在 background 后则背景在阴影图层外(先绘制),阴影命令
  排后 → 阴影盖内容。
- **官方 `Modifier.shadow` 默认 `clip = elevation > 0`**:描边/线/点超出 shape
  轮廓会被裁掉,测试时显式 `clip = false`。
- **`replayFrom` 回放命令丢参数**:回放 `DrawShadowCommand` 时漏传 `pathSegments`
  → 回放后命令走矩形分支(0,0,0,0 尺寸)静默跳过,Path 阴影全部丢失。**回放分支
  必须透传全部字段。**
- **`drawShadow` 的 `Outline.Generic` 分支不能 `return` 跳过**:否则 GenericShape
  阴影全部丢失(矩形/圆角矩形正常,容易误判为"只有 Path 阴影没实现")。
- **Mixin 置底排序风险**(`GuiRenderStateMixin`):置底只在节点内生效(阴影被
  `findAppropriateNode` up 到上层节点时不跨节点)、`@Redirect` lambda 结构脆弱、
  不保证全场景最底 —— 已记录在类注释,待渲染链路拆分后处理。

### 矩阵/旋转调试踩坑记录(T.13 旋转中心"公转"案)

- **MC 26.2 运行时 JOML 的 pose 乘法是"行主序"**:`org.joml.Matrix3x2f.transformPosition`
  实测按 `x' = m00*x + m10*y + m20` 计算(标准列主序应为 `x' = m00*x + m01*y + m20`)。
  向 MC 提交 pose 时若按列主序直接构造 `Matrix3x2f(values[0], values[4], values[1], ...)`,
  2x2 旋转矩阵会被**转置** → 旋转方向反转,且绕 pivot 旋转时**中心随角度摆动**
  (幅度 `2·|sinθ|·|p|`),视觉表现为"旋转中心偏移 / 公转 / 被抛起"。
  修复:提交前交换 m01/m10,即 `Matrix3x2f(values[0], values[1], values[4], values[5], values[12], values[13])`
  (见 `MinecraftRenderContext.toMatrix3x2f`,T.13)。纯缩放/平移不受影响(对角矩阵转置不变),
  所以**"只有旋转异常、缩放平移都正常"时优先怀疑 pose 的 2x2 行列语义**。
- **不要只信源码与数学推导,用运行时探针验证依赖库行为**:本案例命令矩阵正确
  (612 帧文本中心恒定)、MC 源码也"标准",但实际渲染错误,最终靠**运行时打印
  JOML 的 transformPosition 实测值**才定位(标准应得 (10,21),实测 (10,19))。
  排查"旋转中心不对"时:① 多帧采样命令矩阵,算中心轨迹(恒定 ≠ 视觉正确,
  只证明命令层);② 在渲染提交端加一次性探针直接调用依赖库的矩阵乘法打印结果。
- **旋转中心的判断方法**:绕中心旋转时,参考点(画在 pivot 的固定标记)不动、
  元素绕它转;若参考点随元素移动 = 中心错误。区分"观感"与"真实偏移"用**短元素**
  (40px 方块/单字符 "A")实验——短元素绕中心几乎无可见移动,若仍大幅运动必是渲染问题。
- **GraphicsLayer.draw 变换为 post-concat**(与 Skia 一致,顺序
  `T(topLeft+translation) * T(pivot) * R * S * T(-pivot)`),pivot 未指定时默认图层中心;
  命中测试矩阵 `prepareTransformationMatrix` 与绘制矩阵的旋转中心不同是官方既有行为,
  不要"统一"它们。
- **computeLines 死循环(T.12,已修复)**:`MinecraftTextLayout.computeLines` 换行逻辑中,
  单字符宽度 > maxWidth 时内层 while 与外层 `start` 均不推进 → 无限添加空行 → OOM。
  已加防御(单字符强制占行并推进)。**遇到 OOM 优先怀疑这类"看似不会触发"的推进死循环**。

## 已知限制

- 文本输入:charTyped 已接通(经 typed KeyEvent 转发);IME 候选窗口由系统输入法负责,
  组合态提示(preedit)未实现;
- Popup 部分可用(foundation 依赖),Dialog 未移植;
- 剪贴板 / 指针图标为占位实现;
- 平台未配置 maven 发布,消费者直接依赖发布 JAR。

## 许可

Apache License 2.0,详见 `LICENSE` / `NOTICE`(内嵌 CMP 源码保留 AOSP 版权声明)。
