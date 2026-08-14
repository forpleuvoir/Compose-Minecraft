# Compose Minecraft Platform Mod

在 Minecraft(Fabric + NeoForge 双 Loader)中运行 Compose Multiplatform UI 的基座 Mod。

## 特性

- **无 Skia / Skiko / Desktop / Material**:所有绘制直接进入 Minecraft 当前帧的
  `GuiRenderState`,与渲染后端(Vulkan/OpenGL)无关;
- **原版 `Screen` 桥接**:Compose 场景通过原版 Screen 挂入 Minecraft,
  渲染、输入、生命周期全走原版机制(无 mixin、无帧钩子);
- **完整输入与焦点**:指针(点击/拖拽/滚轮)、键盘、Tab 与方向键焦点导航;
- **MC Font 文本**:测量与绘制统一使用 Minecraft 字体;
- **内嵌完整 Compose 运行时**:发布 JAR 自带 `androidx.compose.*` 运行时,
  消费者无需再引入任何 Compose/Skiko 依赖。

## 快速开始

```kotlin
import moe.forpleuvoir.compose_minecraft.minecraft.ComposeScreen

// 在客户端主线程打开 Compose 屏幕
ComposeScreen.open {
    // 你的 Compose UI
}
```

详见 [PLATFORM_MIGRATION_GUIDE.md](PLATFORM_MIGRATION_GUIDE.md)(业务迁移指南)。

## 许可

本项目以 **Apache License 2.0** 授权,详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

- 本项目自有代码(桥接层与平台实现):Copyright 2026 forpleuvoir;
- 内嵌的 Compose Multiplatform / AndroidX Compose 运行时源码
  (`common/src/main/kotlin/androidx/compose/**`):由 JetBrains s.r.o. /
  The Android Open Source Project 按 Apache License 2.0 授权,
  各源码文件头部保留原始版权声明;
- 随发布 JAR 内嵌的第三方依赖均为宽松许可(主要为 Apache License 2.0);
- 本 Mod 不包含任何 Minecraft 代码与资源。
