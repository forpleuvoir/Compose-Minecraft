# 独立 Compose UI Minecraft Platform Mod 完整实施计划

## 0. 文档定位

本计划用于一个由用户预先搭建好的空白双 Loader 模组：

- Fabric
- NeoForge

该模组将作为独立的 **Compose UI Minecraft Platform 基础 Mod**，不承载任何业务 UI。其他业务 Mod通过依赖它来使用 Compose Runtime、Compose UI 以及 Minecraft 平台实现。

本任务不是：

- 在业务 Mod内部继续维护 Compose Desktop 渲染桥。
- 给 `ui-desktop` 换一个 Canvas。
- 创建 Material 组件库。
- 创建通用跨游戏渲染框架。

本计划取代此前“直接在现有业务 Mod中实现 Minecraft Platform”的方案。

### 强制门禁

> 执行 AI 必须先只读检查用户提供的新基础 Mod和现有业务 Mod，完成架构、依赖与代码复用对比，向用户报告并询问是否继续。在用户明确确认之前，不得修改任何文件。

---

## 1. 已确定目标

- 新建独立基础 Mod，由用户提供空白 Fabric + NeoForge 双平台工程。
- 目标操作系统仅为 Windows。
- 目标游戏版本为 Minecraft 26.2。
- 使用 Minecraft 当前 Vulkan 渲染流程。
- Compose 目标版本为 1.11.0；执行 AI必须从两个仓库确认实际版本。
- Kotlin 预期版本为 2.4；执行 AI必须从仓库确认。
- 平台 Mod只提供 Compose Runtime、Compose UI 及 Compose UI 编译和运行必需的基础模块。
- 默认不包含 Material、Material 3、Material Icons。
- 默认不包含完整 Foundation、Foundation Layout 和 Animation；如果现有代码确实需要，必须先报告并询问用户。
- 自行实现 Compose UI 的 Minecraft JVM 平台 actual。
- Compose 负责重组、状态、布局、Modifier、焦点、命中测试和输入分发。
- Minecraft Platform 负责图形、文字、图片、Scene 宿主和 Minecraft 事件适配。
- 所有绘制直接进入 Minecraft 当前帧和当前 RenderTarget。
- 不创建 Compose 专用 VkImage、Framebuffer、RenderTarget、Skia Surface 或离屏纹理。
- 不执行离屏渲染后 Blit。
- 不创建 Vulkan Device、Queue、Swapchain 或独立渲染循环。
- 不依赖 Compose Desktop、Skiko 或 Skia。
- 不保留 Desktop/Skia 与 Minecraft 的运行时双后端。
- 不 relocate `androidx.compose.*` 包。
- 不重写 Compose Runtime、Recomposer、LayoutNode 或通用输入算法。

---

## 2. 两个仓库的边界

### 2.1 新基础 Mod仓库

这是唯一允许实施修改的目标仓库，包含：

- Compose Minecraft Platform 源码和构建。
- Fabric Mod入口与打包。
- NeoForge Mod入口与打包。
- 公共 Minecraft Platform API。
- 开发环境专用的最小验证页面。

执行 AI必须保留用户搭建好的双平台工程结构，不得未经确认更换 Gradle 架构、Mapping、Loader Plugin 或发布方式。

### 2.2 现有业务 Mod仓库

第一阶段仅作为只读参考，用来确定：

- 当前 Compose/Skiko 渲染桥的实现。
- 可迁移的 Scene、输入、资源和生命周期逻辑。
- 业务 UI 实际使用的 Compose API。
- 将来接入平台 Mod时需要删除或替换的依赖。

本任务默认不修改现有业务 Mod。平台 Mod完成以后，业务 Mod迁移必须作为单独步骤并再次取得用户授权。

如果执行 AI无法访问现有业务 Mod的本地仓库，必须停止并向用户索要路径；不得只凭聊天描述或远程仓库猜测当前架构。

---

## 3. 执行规则

1. 完整阅读两个仓库中的 `AGENTS.md` 及相关子目录指令。
2. 保持各仓库当前分支，不得擅自切换、创建或重置分支。
3. 保留用户已有修改，不得清理脏工作区或覆盖无关内容。
4. 构建和测试优先使用仓库约定的 IntelliJ IDEA MCP；不可用时再使用项目现有 Gradle Wrapper。
5. 首轮只允许读取、搜索、依赖分析和基线构建。
6. 所有结论必须来自仓库代码、Gradle 解析结果和 Compose 1.11.0 固定源码。
7. 能从代码中确认的问题不得询问用户。
8. 只有会实质改变模块范围、打包方式或平台能力的选择才交给用户决定。
9. 不得为了“未来可能使用”加入 Foundation、Material、复杂文字、完整 GraphicsLayer 或通用后端。
10. 发现计划与实际仓库不一致时先报告，禁止自行扩大或改变目标。

---

## 4. 强制阶段 A：只读审查与对比

### 4.1 新基础 Mod基线

执行 AI首先记录：

- 当前分支和工作区状态。
- Root、Common、Fabric、NeoForge 等实际模块结构。
- Minecraft、Fabric Loader、Fabric API、NeoForge、Mapping、Kotlin 和 Java 版本。
- Gradle Plugin、Convention Plugin、Version Catalog 和发布配置。
- 公共源码如何在 Fabric 与 NeoForge 间复用。
- 两个 Loader 当前如何打包普通 JVM Library。
- 是否已有 Jar-in-Jar、JarJar、Include、Shadow 或其他机制。
- Fabric 与 NeoForge 的空项目基线能否构建和启动。

不得在基线失败时直接修改项目；先记录原始失败原因并向用户报告。

### 4.2 现有业务 Mod依赖

从 Gradle 的真实解析结果确认：

- Compose Runtime、Runtime Saveable、Runtime Retain。
- Compose UI、UI Graphics、UI Text、UI Geometry、UI Unit、UI Util。
- Foundation、Foundation Layout、Animation。
- Material、Material 3、Material Icons。
- `ui-desktop`、`compose.desktop.currentOs`。
- `org.jetbrains.skiko`、`org.jetbrains.skia` 和 Skiko Windows Native。
- `kotlinx-coroutines-swing`、AWT、Swing。
- Compose Resources、SVG、图片和字体解码依赖。
- 各 Loader 最终 Runtime Classpath 和发布 JAR 中实际包含的依赖。

必须使用依赖树和 `dependencyInsight` 确认传递来源，不能只查看 `build.gradle` 中的直接声明。

### 4.3 现有渲染调用链

查明并记录：

- Compose Scene 创建、`setContent`、逐帧 render、resize 和 close 的位置。
- 当前 Skia/Skiko Surface、Canvas、Texture、Framebuffer 或 RenderTarget 的创建位置。
- 当前离屏渲染、纹理上传、复制和 Blit 流程。
- Minecraft 当前帧调用 Compose 的实际 Hook。
- 当前 RenderTarget、GUI 绘制上下文和绘制顺序。
- GUI Scale、Density、窗口尺寸和全屏变化如何同步。
- 纹理、图片缓存和资源重载生命周期。
- 渲染线程和调度方式。

必须沿实际调用链确认，不得依据类名或注释推断。

### 4.4 现有输入与 Scene 生命周期

查明并记录：

- 鼠标移动、按下、释放和滚轮如何进入 Compose。
- 键盘按下、释放、修饰键和字符输入如何进入 Compose。
- 焦点、Screen 打开关闭和输入取消语义。
- Pointer Icon、Clipboard、IME、文本选择、拖放是否已经使用。
- Popup、Dialog、Tooltip 是否依赖 Desktop Window 或 Skiko Layer。
- 哪些逻辑可原样迁移，哪些仍依赖 AWT/Skiko。

现有可复用逻辑不得无理由重写。

### 4.5 现有 Compose API 使用清单

扫描生产代码 imports 和调用，分类列出：

- `androidx.compose.runtime.*`
- `androidx.compose.ui.*`
- `androidx.compose.foundation.layout.*`
- `androidx.compose.foundation.*`
- `androidx.compose.animation.*`
- `androidx.compose.material.*`、`androidx.compose.material3.*`
- `org.jetbrains.skia.*`、`org.jetbrains.skiko.*`
- `BasicText`、`BasicTextField`、Clickable、Scrollable、Lazy Layout
- `Row`、`Column`、`Box`、Padding、Size 等 Foundation Layout API
- `Canvas` Composable、Painter、Vector、SVG
- `drawBehind`、`drawWithContent` 和自定义 DrawModifier
- `graphicsLayer`、`alpha`、`rotate`、`scale`、`shadow`、复杂 `clip`
- Shader、Gradient、ColorFilter、PathEffect、RenderEffect、BlendMode
- Popup、Dialog、Tooltip
- Skia-backed `ImageBitmap`、Skia `Image`、Surface 和 Paragraph

特别提醒：

- `Layout`、Modifier、DrawScope 等低层能力属于 Compose UI。
- `Row`、`Column`、`Box` 通常属于 Foundation Layout。
- `BasicText`、滚动、Clickable、Lazy Layout 和 TextField 通常属于 Foundation。

因此执行 AI不能把“基础 UI”自动解释为 Foundation。必须先把实际使用量报告给用户。

### 4.6 代码迁移来源

对现有代码逐项分类：

| 当前代码 | 原样迁移 | 调整后迁移 | 平台 Mod重写 | 业务 Mod保留 | 最终删除 |
|---|---:|---:|---:|---:|---:|
| Scene 生命周期 |  |  |  |  |  |
| Minecraft 渲染 Hook |  |  |  |  |  |
| Skia Surface/Canvas |  |  |  |  |  |
| 输入桥 |  |  |  |  |  |
| GUI Scale/Density |  |  |  |  |  |
| 图片和纹理 |  |  |  |  |  |
| 文字 |  |  |  |  |  |
| Popup/Dialog |  |  |  |  |  |
| 资源重载 |  |  |  |  |  |

表格必须填写实际类名、文件和调用入口，不能只写抽象结论。

### 4.7 必须向用户汇报并停止

只读审查完成后，执行 AI必须提供：

1. 新基础 Mod的真实模块和打包结构。
2. 两个 Loader 的基线构建结果。
3. 现有业务 Mod的依赖闭包。
4. 现有渲染、输入、资源和 Scene 调用链。
5. 现有 UI 实际使用的 Compose API 清单。
6. 可以迁移到平台 Mod的代码清单。
7. 必须重新实现的 Compose 平台 actual 清单。
8. 建议的平台 Mod模块结构。
9. 建议的 Fabric/NeoForge 依赖打包方式。
10. 尚需用户决定的事项，每项给出证据、推荐方案和影响。

随后明确询问用户是否开始实施，并停止。

在得到确认前不得：

- 修改任何 Gradle 文件。
- 添加 Compose 源码、Git Submodule 或 Included Build。
- 创建平台 API、Canvas、Paragraph 或 GraphicsLayer。
- 复制现有业务 Mod代码。
- 修改现有业务 Mod。

不得重复询问已经确定的事项：

- 是否支持 Fabric 与 NeoForge。
- 是否支持其他操作系统。
- 是否使用 Material。
- 是否允许离屏。
- 是否保留双后端。

审查后可能需要用户决定的真实问题：

- Compose 1.11.0 源码采用仓库内模块、Included Build 还是其他单一维护方式。
- 最终 Mod JAR 使用现有工程支持的哪一种依赖携带方式。
- 第一版是否额外包含 Foundation Layout；默认答案为不包含。
- 现有 UI 的复杂文字或 GraphicsLayer 使用是否进入第一版。

---

## 5. 目标架构

只有用户确认阶段 A 报告后才实施。

### 5.1 逻辑结构

```text
Compose Minecraft Platform Mod
│
├── 官方 Compose Runtime JVM
│
├── Compose UI Minecraft 平台
│   ├── ui-graphics-minecraft
│   ├── ui-text-minecraft
│   └── ui-minecraft
│
├── Compose UI 必需的基础模块
│   ├── ui-geometry
│   ├── ui-unit
│   ├── ui-util
│   └── 由 Compose UI 1.11.0 编译闭包确定的其他基础依赖
│
├── Minecraft Platform Host
│   ├── MinecraftComposeScene
│   ├── MinecraftPlatformContext
│   ├── MinecraftRenderContext
│   └── Minecraft 输入适配
│
├── Fabric 薄入口
└── NeoForge 薄入口
```

业务 Mod只依赖对应 Loader 的平台 Mod，不再携带 Compose Runtime、Compose UI、Desktop、Skiko 或平台实现。

### 5.2 默认模块边界

默认包含：

- Compose Runtime JVM。
- Compose UI common 源码所需的 Runtime Saveable、Runtime Retain 等实际依赖。
- Compose UI Geometry、Unit、Util。
- 自定义 UI Graphics Minecraft 平台。
- 自定义 UI Text Minecraft 平台。
- 自定义 UI Minecraft 平台。
- Compose UI 1.11.0 编译所必需的 Annotation、Collection、Coroutines、Lifecycle/SavedState 等实现依赖。

默认不包含：

- Compose Desktop。
- Skiko/Skia。
- Foundation Layout。
- Foundation。
- Animation。
- Material、Material 3、Icons。
- Compose Desktop Window、AWT、Swing。
- 业务 UI 组件。

如果某个默认不包含的模块是 `ui-minecraft` 完成编译所必需的真实依赖，执行 AI必须展示依赖原因；不能为了维持表面上的模块列表而删除必要依赖。

### 5.3 平台 Mod不是组件库

第一版只提供平台能力，不提供按钮、文本框、主题或 Material 替代品。

仅有 Runtime + UI Core 时，消费者可以使用：

- Composable 和 State。
- `Layout`。
- Modifier Node。
- DrawScope 和 DrawModifier。
- Pointer/Key/Focus 等 UI Core 能力。

消费者不能默认使用：

- `Row`、`Column`、`Box`。
- `BasicText`、`BasicTextField`。
- `clickable`、滚动和 Lazy Layout。

是否添加 Foundation Layout 必须在现有代码扫描后由用户明确决定，不能因为测试方便自动加入。

---

## 6. Compose 源码与构建方案

### 6.1 固定基线

- JetBrains Compose Multiplatform Core tag：`v1.11.0`。
- 核对提交：`f796d9898491b390baa11a516590ed949521c6de`。
- 不使用 `main`、`jb-main` 或 Graphite/Vulkan 实验分支。
- 不 fork Compose Runtime；直接使用官方 JVM Runtime。
- 只引入实现 Minecraft UI 平台实际需要的 Compose UI 模块和源码。
- 所有相对上游的修改必须集中、最小并可追踪。

如果新基础 Mod或现有业务 Mod实际 Compose 版本不是 1.11.0，执行 AI必须先询问用户，不能擅自升级或降级。

### 6.2 expect/actual 约束

不能采用下面的错误方式：

```text
依赖已经编译好的 compose.ui Desktop Artifact
    +
在 Mod模块里编写同名 Minecraft actual
```

Desktop Artifact 在构建时已经绑定 Desktop/Skiko actual，消费者无法覆盖。

正确方式是让自定义 Artifact 在同一次 Compose UI 平台编译中包含：

- Compose UI common 源码。
- Minecraft JVM actual。

最终业务 Mod只能看到 Minecraft 版本，不能同时解析官方 Desktop UI。

### 6.3 源码组织原则

执行 AI根据空项目结构提出一个最小方案并让用户确认。无论最终目录名如何，必须满足：

- Compose 平台源码与 Fabric/NeoForge Loader 入口分离。
- Fabric 和 NeoForge 复用同一份 Minecraft Platform 实现。
- Loader 模块只处理加载、元数据和各自的依赖打包差异。
- 不复制整个 Compose Multiplatform Core 仓库中无关模块。
- 不同时维护仓库内源码、Included Build 和 Maven Fork 三套来源。

---

## 7. 依赖计划

具体 Alias、Configuration 和模块名以空项目实际结构为准。

### 7.1 平台 Mod保留或新增

- Kotlin JVM 和 `org.jetbrains.kotlin.plugin.compose`。
- 官方 Compose Runtime JVM 1.11.0。
- Runtime Saveable、Runtime Retain：仅按 UI 1.11.0 实际编译闭包引入。
- Kotlin Coroutines Core。
- UI Geometry、Unit、Util 等平台中立基础模块。
- 自定义 `ui-graphics-minecraft`。
- 自定义 `ui-text-minecraft`。
- 自定义 `ui-minecraft`。
- Compose UI 源码实际要求的 Annotation、Collection、Lifecycle/SavedState 等基础依赖。

### 7.2 平台 Mod禁止引入

- `compose.desktop.currentOs`。
- `ui-desktop`。
- `org.jetbrains.skiko:*`。
- `org.jetbrains.skia:*`。
- `kotlinx-coroutines-swing`。
- AWT/Swing Compose 宿主。
- Material、Material 3、Material Icons。
- 未经用户确认的 Foundation、Foundation Layout 和 Animation。

### 7.3 不得用 exclude 假装完成平台替换

禁止继续依赖 Desktop UI 后只写：

```kotlin
exclude(group = "org.jetbrains.skiko")
```

这会留下 Skia-backed actual 或运行时缺类。必须从依赖入口上使用自定义 Minecraft UI Artifact。

### 7.4 对消费者的依赖方式

平台 Mod必须发布或提供可用于编译的 API，使业务 Mod能够直接引用：

- `androidx.compose.runtime.*`
- `androidx.compose.ui.*`
- 平台 Mod公开的 Minecraft Scene API

业务 Mod应：

- 对平台 Mod声明对应 Loader 的强制 Mod依赖。
- 编译期从平台 Mod或其配套开发 Artifact 获得 Compose API。
- 不再次 Jar-in-Jar Compose Runtime/UI。
- 不声明 Desktop/Skiko 依赖。

平台 Mod不得 relocate：

- `androidx.compose.*`
- `kotlinx.coroutines.*`
- Kotlin 编译器生成代码所引用的运行时包

可以使用工程现有方式合并或嵌套依赖，但不得改变包名。

---

## 8. Fabric 与 NeoForge 打包计划

### 8.1 公共要求

- 两个 Loader 使用同一份 Compose Minecraft Platform 类。
- 两个最终 Mod JAR各自只携带一份 Compose Runtime 和 Minecraft UI。
- 消费者 Mod不重复携带这些类。
- 开发运行 Classpath 与发布 JAR Classpath 行为一致。
- 平台 Mod ID和版本规则在两个 Loader 中保持一致。
- 两个 Mod元数据都声明正确的 Minecraft、Loader、Java 和 Kotlin 前置条件。

### 8.2 Fabric

执行 AI检查空项目的 Loom/Include 配置后，使用其既有方式打包普通 JVM 依赖。必须验证：

- 嵌套或合并的 Compose 类在开发和发布环境都可见。
- 业务 Mod依赖平台 Mod时不会再解析第二份 Compose。
- 最终 JAR不包含 Skiko Native。

### 8.3 NeoForge

执行 AI检查空项目的 ModDevGradle/JarJar 配置后，使用其既有方式打包普通 JVM 依赖。必须验证：

- JarJar Metadata 与实际嵌套依赖一致。
- Compose 类对消费者编译期和运行期都可见。
- 不产生重复包、版本冲突或因包名/模块边界导致的类加载失败。
- 最终 JAR不包含 Skiko Native。

### 8.4 打包方式必须先询问

执行 AI在阶段 A后必须基于空项目真实配置，给出一个推荐方案，例如：

- 依赖合并进平台 Mod主 JAR但不 relocate；或
- 使用 Loader 原生的嵌套依赖机制，并提供一致的开发 Artifact/POM。

只实施用户确认的一种方式，不建立多套发布产物体系。

---

## 9. `ui-graphics-minecraft`

### 9.1 actual 清单

执行 AI必须从 Compose 1.11.0 源码机械扫描 expect 声明，并以编译结果校验，不得仅依赖手写清单。

至少包括：

- `NativeCanvas`、`ActualCanvas`
- `NativePaint`、`Paint()`
- `Path()`、`PathMeasure()`、`PathIterator`
- `ActualImageBitmap` 和图片创建入口
- Shader 及其工厂
- ColorFilter
- PathEffect
- RenderEffect、Blur 相关类型
- `GraphicsLayer`
- BlendMode、TileMode 平台支持判断
- JVM 注解和平台工具 actual

所有 actual 必须可以编译。暂不支持的功能必须返回 `isSupported=false` 或抛出带 API 名称的明确异常，禁止静默错误。

### 9.2 `MinecraftCanvas`

```kotlin
class MinecraftCanvas(
    private val renderContext: MinecraftRenderContext,
) : androidx.compose.ui.graphics.Canvas
```

第一版基础实现：

- `save`、`restore`
- `translate`、`scale`、`rotate`、`skew`、`concat`
- `clipRect` 的 `Intersect`
- `drawLine`
- `drawRect`
- `drawRoundRect`
- `drawOval`
- `drawCircle`
- `drawArc`
- `drawImage`
- `drawImageRect`

第一版默认不支持：

- `saveLayer`
- `clipPath`
- `drawPath`
- `drawPoints`
- `drawRawPoints`
- `drawVertices`
- `ClipOp.Difference`

如果现有代码使用其中某项，阶段 A必须报告，由用户决定是否进入第一版。

内部只维护：

- 变换状态栈。
- 矩形裁剪状态栈。
- 当前 Minecraft RenderContext。

不得创建第二套帧生命周期、渲染线程或 Vulkan 资源管理器。

### 9.3 Paint

第一版支持：

- Color、Alpha。
- Fill、Stroke。
- Stroke Width；Cap、Join 仅在实际使用时支持。
- `SrcOver`。
- 图片过滤方式。

Shader、Gradient、ColorFilter、PathEffect 和其他 BlendMode 默认不实现，除非阶段 A证明现有代码需要并经用户确认。

### 9.4 ImageBitmap

实现 Minecraft-backed ImageBitmap：

- 保存 Minecraft 纹理资源标识或平台纹理引用。
- `drawImage`、`drawImageRect` 直接使用 Minecraft 纹理。
- 不转换成 Skia Bitmap。
- 复用 Minecraft 资源加载和纹理生命周期。
- 资源重载时正确失效或重新解析。
- 不建立与 Minecraft 重复的通用图片缓存。

### 9.5 GraphicsLayer

必须提供 `GraphicsLayer` actual 以满足 UI 编译，但第一版禁止离屏实现。

- 普通平移、缩放、旋转和矩形裁剪映射到当前 Canvas。
- 只有现有 UI 需要时才实现 CPU 侧命令记录或重放。
- 不支持 `CompositingStrategy.Offscreen`。
- 不支持依赖离屏合成的 RenderEffect、模糊和复杂 BlendMode。
- 无法直接表达的语义必须明确报错。

把 Compose 1.11.0 `RootNodeOwner` 中硬编码的 `SkiaGraphicsContext` 替换为可注入或 Minecraft 版本的 GraphicsContext。只改平台边界，不重构 Owner/LayoutNode。

---

## 10. `ui-text-minecraft`

### 10.1 编译 actual

根据 Compose 1.11.0 源码实现：

- `Paragraph` actual。
- `ActualParagraph` 系列入口。
- `ParagraphIntrinsics`。
- PlatformTextStyle、PlatformSpanStyle、PlatformParagraphStyle。
- FontFamily Resolver 和平台 Typeface 适配。
- Locale、字符边界和换行支持。
- MultiParagraph 绘制入口。
- LineBreak、TextMotion、Saver。
- UI Text 所需 JVM 同步和 Atomic 工具 actual。

可以最小复用纯 JVM 代码；任何依赖 Skia Paragraph、Skia Font 或 `canvas.skiaCanvas` 的实现都必须替换。

### 10.2 第一版功能范围

文字最终使用 Minecraft Font 测量并绘制到当前 RenderContext。

执行 AI根据现有 UI 使用情况报告：

- 单行/多行。
- 自动换行。
- MaxLines、Ellipsis。
- 基线和行高。
- 字符位置与坐标互查。
- 光标、选区、Bounding Box。
- AnnotatedString 多样式。
- 字体 fallback、Bidi 和复杂 shaping。

默认只实现现有 UI 真正需要的最小集合。

本平台默认不包含 Foundation，因此不需要为了不存在的 `BasicTextField` 提前实现完整文本编辑、IME 和选择系统。若现有业务 Mod确实依赖，先询问用户。

---

## 11. `ui-minecraft` 与宿主 API

### 11.1 Compose Scene

基于 Compose 1.11.0 的 CanvasLayersComposeScene 和 RootNodeOwner 建立 Minecraft 版本：

- 保留 Composition、Recomposer、Measure/Layout、Hit Test、Focus 和 Semantics 树。
- 移除 SkiaGraphicsContext、Skia Layer 和 Skia Canvas 假设。
- 使用 Minecraft 主线程/渲染线程的既有调度。
- `invalidate` 只标记需要下一帧绘制，不创建独立帧循环。
- Scene 正确处理创建、内容设置、尺寸变化、渲染、输入取消和关闭。

### 11.2 最小公共 API

平台 Mod只公开消费者实际需要的宿主入口，建议形态：

```kotlin
object MinecraftComposePlatform {
    fun createScene(/* 必要配置 */): MinecraftComposeScene
}

class MinecraftComposeScene : AutoCloseable {
    fun setContent(content: @Composable () -> Unit)
    fun resize(width: Int, height: Int, density: Density)
    fun render(context: MinecraftRenderContext, nanoTime: Long)
    // Pointer/Key 入口以 Compose 1.11.0 和现有输入桥为准
    override fun close()
}
```

具体签名必须在现有代码对比后确定，不能先添加通用 Backend、Scene Registry 或全局 UI 管理器。

默认由业务 Mod或具体 Screen 持有自己的 Scene。平台 Mod不扫描和管理所有消费者 Scene。

### 11.3 MinecraftPlatformContext

按实际使用实现：

- WindowInfo、焦点状态。
- Density、窗口尺寸、GUI Scale。
- 本地、窗口和屏幕坐标转换。
- InputModeManager。
- Pointer Icon。
- Clipboard。
- Parent Focus。
- Text Input/IME：仅在确认需要时。
- Popup/Dialog：仅在确认需要时。
- Lifecycle/SavedState 所需的最小宿主。

未使用的屏幕阅读器、拖放、原生窗口互操作保持明确空实现，不增加 Windows Native 集成。

### 11.4 输入

- 优先迁移现有 Minecraft→Compose 输入桥。
- 移除其中只为 AWT/Skiko 存在的转换。
- 保持鼠标按钮、滚轮、键盘修饰键、字符输入和事件消费语义。
- GUI Scale 只能在一个统一边界转换一次。
- Scene 关闭、Screen 切换或失焦时取消 Pointer 输入并释放焦点。
- 不建立第二套相互竞争的输入系统。

---

## 12. Fabric 与 NeoForge 代码边界

### 12.1 公共代码

尽量放在空项目已有的公共模块：

- Compose Minecraft Platform actual。
- Canvas、Text、Image、GraphicsLayer。
- Scene、PlatformContext 和公共 API。
- Minecraft 渲染与输入适配中 Loader 无关的部分。

### 12.2 Fabric 专属代码

只放：

- Fabric 初始化入口。
- Fabric 元数据。
- Fabric 特有事件或 Mixin 接入。
- Fabric 依赖携带配置。

### 12.3 NeoForge 专属代码

只放：

- NeoForge 初始化入口。
- NeoForge 元数据。
- NeoForge 特有事件或 Mixin 接入。
- NeoForge JarJar/依赖携带配置。

不得复制两份平台核心实现。

---

## 13. 实施顺序

以下阶段只在用户确认阶段 A报告后开始。

### 阶段 B：确定源码和打包结构

1. 按用户确认方式引入 Compose 1.11.0 UI 源码。
2. 建立最小 UI Graphics、UI Text、UI Minecraft 平台编译。
3. 确定 Fabric/NeoForge 唯一依赖携带方式。
4. 让平台模块在尚未接入渲染前独立编译。
5. 验证依赖闭包没有 Skiko、Desktop、Material、未经确认的 Foundation。

### 阶段 C：基础图形

1. 实现 Paint 和 Canvas 状态栈。
2. 实现矩形、线、圆角、圆、裁剪和变换。
3. 实现 Minecraft-backed ImageBitmap。
4. 接入 Minecraft 当前 RenderContext。
5. 验证没有额外渲染目标和 Blit。

### 阶段 D：Scene

1. 移植 CanvasLayersComposeScene 所需逻辑。
2. 注入 Minecraft GraphicsContext。
3. 实现 resize、Density、invalidate、render 和 close。
4. 建立开发环境最小验证 Scene。

### 阶段 E：文字

1. 实现编译所需 UI Text actual。
2. 根据现有代码需求实现最小 Paragraph 测量。
3. 接入 Minecraft Font 直接绘制。
4. 验证测量结果与最终绘制一致。

### 阶段 F：输入与平台服务

1. 迁移现有输入桥的可复用部分。
2. 完成 Pointer、Key、Focus 和 GUI Scale。
3. 只按确认范围实现 Clipboard、Pointer Icon、Popup/Dialog 或 IME。

### 阶段 G：双 Loader 打包

1. Fabric 开发运行和发布 JAR验证。
2. NeoForge 开发运行和发布 JAR验证。
3. 发布或生成消费者编译所需 Artifact/POM。
4. 检查两个 Loader 最终 Classpath 和 JAR 内容。

### 阶段 H：平台完成后的业务迁移建议

本阶段只输出迁移文档，不默认修改业务 Mod：

- 业务 Mod如何依赖平台 Mod。
- 删除哪些 Desktop/Skiko 依赖。
- 哪些旧桥接文件可删除。
- 哪些 UI API 因 Foundation 未包含而需要调整。
- Fabric 与 NeoForge Mod元数据如何声明平台依赖。

业务 Mod实际修改需要用户另行授权。

---

## 14. 验证计划

### 14.1 构建

- 平台公共模块编译通过。
- Fabric 开发客户端和构建通过。
- NeoForge 开发客户端和构建通过。
- 优先使用 IDEA MCP执行仓库约定的构建。

### 14.2 依赖

- Runtime Classpath 无 `ui-desktop`。
- 无 Skiko、Skia 和 Skiko Native。
- 无 Material。
- 无未经用户确认的 Foundation/Foundation Layout。
- Fabric 和 NeoForge 最终 JAR各只有一份 Compose Runtime/UI。
- 无重复 `androidx.compose.*` 类。
- 消费者不会再次携带 Compose。

### 14.3 最小 UI Core 验证

验证页面不得为了方便引入 Foundation 或 Material。使用 UI Core 原语验证：

- Composition 和状态更新。
- 自定义 `Layout` 测量和放置。
- DrawModifier 绘制纯色矩形和边框。
- 圆角、圆和矩形裁剪。
- 平移、缩放、旋转。
- Minecraft 纹理。
- Minecraft 文字。
- Pointer、Hover、滚轮和 Key 输入。
- Focus。

### 14.4 Minecraft 集成

- 直接绘制到当前 RenderTarget。
- 与 Minecraft GUI 的绘制顺序正确。
- 无额外 VkImage、Framebuffer、Surface、读回、上传或 Blit。
- 每次绘制后状态正确恢复。
- GUI Scale、窗口缩放、全屏切换后布局和输入坐标准确。
- Scene 创建和关闭无资源泄漏。
- 资源重载后纹理引用正确。

### 14.5 功能边界

- 已支持 API 行为一致。
- 未支持 API 抛出包含具体 API 名称的错误。
- 关键绘制调用不得静默 no-op。
- 不要求 Foundation、Material、复杂文字和离屏 Effect 通过测试。

---

## 15. 完成标准

- 产出独立 Fabric 和 NeoForge Compose Minecraft Platform Mod。
- 两个平台共享同一套核心实现。
- 平台 Mod携带唯一一份 Compose Runtime 和 Minecraft UI。
- 业务 Mod可将其作为强制 Mod依赖并编译 Compose UI Core 代码。
- 使用自定义 UI Graphics、UI Text 和 UI Minecraft actual。
- 不使用 Desktop UI、Skiko、Skia、Material。
- 默认不包含 Foundation 和 Foundation Layout。
- 图形、文字、图片直接进入 Minecraft 当前 Vulkan 渲染流程。
- 不存在 Compose 离屏 RenderTarget、额外 VkImage 或 Blit。
- Scene、输入、Density 和资源生命周期工作正常。
- Fabric 和 NeoForge 开发与发布环境均验证通过。
- 提供消费者接入和现有业务 Mod迁移说明。
- 没有运行时双后端、全局 Scene 管理器或无关通用抽象。

---

## 16. 禁止事项

- 禁止在用户确认前修改文件。
- 禁止直接依赖 `ui-desktop` 后排除 Skiko。
- 禁止 relocate Compose 包。
- 禁止复制整个 Compose 仓库的无关模块。
- 禁止自动加入 Foundation、Animation、Material。
- 禁止为测试页面引入 UI 组件库。
- 禁止建立独立 Vulkan 设备或 RenderTarget。
- 禁止保留 Skia 兼容后端。
- 禁止修改现有业务 Mod，除非用户另行授权。
- 禁止在平台迁移中重构业务逻辑。
- 禁止把未支持方法静默实现为空操作。

---

## 17. Compose 1.11.0 源码核对入口

执行 AI至少核对以下固定 tag 源码：

- [ComposeScene](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/scene/ComposeScene.skiko.kt)
- [CanvasLayersComposeScene](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/scene/CanvasLayersComposeScene.skiko.kt)
- [PlatformContext](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/platform/PlatformContext.skiko.kt)
- [RootNodeOwner](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/node/RootNodeOwner.skiko.kt)
- [Canvas](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/Canvas.kt)
- [GraphicsLayer](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/layer/GraphicsLayer.kt)
- [SkiaGraphicsLayer actual](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui-graphics/src/skikoMain/kotlin/androidx/compose/ui/graphics/layer/SkiaGraphicsLayer.skiko.kt)
- [Paragraph](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/Paragraph.kt)
- [SkiaParagraph actual](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui-text/src/skikoMain/kotlin/androidx/compose/ui/text/SkiaParagraph.skiko.kt)
- [UI Graphics build](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui-graphics/build.gradle)
- [UI Text build](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui-text/build.gradle)
- [UI build](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.0/compose/ui/ui/build.gradle)

不得用浮动分支或实验 Vulkan 分支替代 1.11.0 的 API 事实。

---

## 18. 执行 AI 第一次回复模板

执行 AI接到本计划后的第一次回复必须只包含只读审查结果：

```markdown
# Compose Minecraft Platform Mod 前置审查

## 1. 新基础 Mod结构与基线构建

## 2. Fabric/NeoForge 当前依赖打包方式

## 3. 现有业务 Mod依赖闭包

## 4. 现有渲染调用链

## 5. 现有输入、资源和 Scene 生命周期

## 6. 现有 Compose API 使用范围

## 7. 可迁移代码与必须重写内容

## 8. 建议的平台模块结构

## 9. 建议的依赖与打包修改

## 10. 需要用户决定的事项

每项必须提供仓库证据、可选方向、推荐方向和影响。

## 11. 确认

请确认是否按照上述审查结果开始实施。在得到明确确认前，我不会修改任何文件。
```

如果执行 AI无法访问现有业务 Mod、发现版本不一致，或发现空项目基线无法构建，必须在第 10 节明确说明并询问用户，不能跳过问题继续实现。
