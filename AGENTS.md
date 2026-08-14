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
> 必须通过 IDEA MCP 或 Gradle 完成**,禁止绕过构建直接手改产物。

## 项目概述

在 Minecraft(Fabric + NeoForge 双 Loader)中运行 Compose Multiplatform UI 的基座 Mod。

- **无 Skia / Skiko / Desktop / Material**:所有绘制进入 Minecraft 当前帧的
  `GuiRenderState`(与 Vulkan/OpenGL 渲染后端无关);
- **原版 `Screen` 桥接**:Compose 场景通过 `net.minecraft.client.gui.screens.Screen`
  挂入 Minecraft(无 mixin、无帧钩子、无 loader 事件);
- **场景密度固定 1**:1dp == 1 GUI 单位,坐标无需换算;
- **文字**:MC Font 度量统一,行高 9px 固定,`fontSize` 第一版被忽略;
- **发布 JAR 内嵌完整 Compose 运行时**(约 4000+ 个 `androidx.compose.*` 类),
  消费者无需引入任何 Compose/Skiko 依赖。

## 目录结构

```
buildSrc/                      # Gradle 约定插件(multiloader-common / multiloader-loader)
common/                        # 平台核心(单模块)
  src/main/kotlin/
    moe/forpleuvoir/compose_minecraft/minecraft/   # 自有代码:Screen 桥接、场景宿主、渲染上下文
    androidx/compose/**                            # 从 CMP 1.11 移植的运行时源码(expect/actual 剥离)
  src/devOnly/kotlin/           # dev 测试代码(dev scene),仅 Fabric dev run 生效
fabric/                        # Fabric loader 模块(includeInternal 内嵌依赖)
neoforge/                      # NeoForge loader 模块(jarJar 内嵌依赖,无 devOnly)
```

## 构建与运行

```bash
# 完整构建(必须使用完整 build;单任务会漏编译 devOnly)
gradlew build :common:compileDevOnlyKotlin

# 打包发布 JAR
gradlew :fabric:jar :neoforge:jar

# 或统一输出到 modJar/<mc>/<version>/
gradlew buildAllModJar
```

- **dev 测试统一在 Fabric 端**(`Compose-Minecraft [:fabric:runClient]` run configuration);
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

## 已知限制

- 文本输入(charTyped/IME)未接通,`BasicTextField` 源码存在但链路未通;
- Popup 部分可用(foundation 依赖),Dialog 未移植;
- 剪贴板 / 指针图标为占位实现;
- 平台未配置 maven 发布,消费者直接依赖发布 JAR。

## 许可

Apache License 2.0,详见 `LICENSE` / `NOTICE`(内嵌 CMP 源码保留 AOSP 版权声明)。
