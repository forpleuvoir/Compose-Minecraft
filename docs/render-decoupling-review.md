# 渲染实现解耦设计评审

> 状态:评审稿 v1(2025-08,未实施)
> 范围:`common/src/main/kotlin/moe/forpleuvoir/compose_minecraft/platform/render/**`
> 结论:当前渲染实现为实现而快速迭代,耦合度偏高;本文给出目标模块划分、消重机制与分阶段迁移路径。

## 0. 评审基线

- **范围声明(最高约束,2025-08 用户明确)**:本次重构**只是代码结构调整**,**所有实现和具体功能都不可修改** —— 不外乎拆文件、收敛重复、定义中间层,但任何算法、语义、常数、分支、坐标换算、取整方式一律原样搬移;功能不增删改。完整定义见 `docs/render-decoupling-implementation.md` 第 0 节。
- **目标**:消除结构性重复(三份命令分派、两份渐变求值、god object),让职责边界可独立演进与测试;在此目标下,`MinecraftRenderContext` / `GraphicsLayerRasterizer` / `GeometryTessellator` 等仅做**结构搬移**,内部实现逐函数原样保留。
- **红线(拆不得的"耦合"是数据流语义,不是坏味道)**:
  1. **z 序与排序语义**:元素/文本交错顺序、阴影必须画在内容之前 —— 渲染正确性,归档在提交层,禁止"解耦"成后端各自排序;
  2. **per-command 属性流**:pose / clip / blendMode / 颜色滤镜随命令走,不可从命令剥离成全局;
  3. **零分配 hot path**:`GeometryTessellator.Sink` 复用、`FloatArray` 翻倍扩容、三角化缓存(`triangleCache`)、阴影网格 LRU(`MinecraftShadowRenderer.meshCache`)—— 拆分只动文件边界,不动对象生命周期与缓冲复用;
  4. **录制层不加平台方法**:分派器放在平台层,`MinecraftCanvas` / `DrawCommand` 保持移植源码原貌。

## 1. 现状调用链

```
ComposeScreen.extractRenderState(每帧)
  └─ MinecraftComposeScene.renderFrame()
       ├─ scene.render(canvas)                // 组合录制命令 → MinecraftCanvas(DrawCommand 列表)
       └─ renderContext.render(canvas, renderer)   // 命令 → GuiElementRenderState
GUI 阶段:GuiRendererMixin → ComposeGuiRenderer.render()   // prepare → upload → draw
快照:   GraphicsLayer.toImageBitmap → GraphicsLayerRasterizer.rasterize   // CPU 像素
```

## 1.1 官方 shadow 家族在本平台的支持矩阵(2025-08 调查结论,重构避雷参考)

| API | 机制 | 本平台可用性 | 关键依据 |
|---|---|---|---|
| `Modifier.shadow`(androidx.compose.ui.draw) | 设 GraphicsLayer 5 属性(elevation/shape/clip/ambient/spot)→ `DrawShadowCommand` → GPU 距离场 | ✅ **可用**;**2025-08 起官方版已标 @Deprecated 并重定向平台版**(`Shadow.kt`,WARNING + ReplaceWith,3 参重载同步统一)—— 官方版光源方向固定默认右上 `(1,-1)` 不可配;与平台 `ShadowLight.kt` 同名扩展并存,同时 import 两包会歧义 | `Shadow.kt:111` + `GraphicsLayer.drawShadow` 全链路消费
| `Modifier.dropShadow` / `Modifier.innerShadow`(Shadow 模型,radius/spread/偏移,自绘) | 离屏 Alpha8 位图 + BlurFilter | ❌ **不可用(占位实现)**:① `Paint.setBlurFilter = Unit`(`Blur.kt:33`)模糊失效;② `Canvas(image)` 是命令记录型,不填充位图像素 → shadowBitmap 空;③ Alpha8 配置平台无上传路径 | `Blur.kt:33`、`Canvas.kt:26-28`、`MinecraftPlatform.kt:592-594` |
| 平台 `Modifier.shadow`(platform.render.ShadowLight.kt) | 同官方 `Modifier.shadow`,多设 `shadowLightDirectionX/Y`(LocalShadowLight) | ✅ 可用,光源可配(ambient/spot 可设色) | `ShadowLight.kt` + `GraphicsLayer.drawShadow` |
| 文本 dropShadow(`GuiTextRenderState` 参数) | MC 原生字形阴影 | ✅ 可用(走原版字体管线,不经上述任何机制) | `MinecraftRenderContext.text` |

## 2. 现状耦合清单

### C1. 命令分派逻辑重复三套(最明显)

同一份 `when(command)` 大 switch 出现在:

| 位置 | 输出端 |
|---|---|
| `MinecraftRenderContext.render()` | 2D → `GuiElementRenderState` |
| `MinecraftRenderContext.render3D()` | 3D → CPU 透视三角形(几乎镜像的 when) |
| `GraphicsLayerRasterizer.rasterize()` | CPU 像素 `IntArray` |

新增一个命令类型(如历史上加 `DrawShadowCommand`)要改三处,几何化参数传递(`GeometryTessellator.xxx(...)` 调用)逐字重复。**几何化的前半段完全相同,复用靠拷贝。**

### C2. `MinecraftRenderContext` 是 god object(1279 行)

一个类同时承担:命令分派 / 3D 透视变换(`render3D` + `map3D`)/ 渐变采样与 HSV 插值 / 颜色滤镜(`applyColorFilter`,约 80 行)/ 文本构建 / 图片 blit / 三角化缓存(指纹计算约 90 行)/ 矩阵转置适配(`toMatrix3x2f`)。七个以上职责,靠注释分区维系。

### C3. 渐变顶点色计算实质重复

`MinecraftRenderContext.gradientVertexColors`(带 colorFilter)与 `GraphicsLayerRasterizer.gradientVertexColors`(不带)是同一逻辑的两个拷贝;`sampleGradient` 各自一份,`lerpColor` 甚至出现**语义分叉**:

- `MinecraftRenderContext.lerpColor`:HSV 空间插值(色相取最短路径);
- `GraphicsLayerRasterizer.lerpColorARGB`:RGB 直插。

同一渐变(饱和色相之间)两条路径渲染结果不同,是潜在隐患。

### C4. `GeometryTessellator` 是巨型 object(1537 行)

细分判据、miter 偏移、三环填充、耳切、自交切分、描边带展开、阴影距离场、全部形状生成(圆/椭圆/弧/圆角矩形/线/Path/点)堆在一个文件,依赖文件头 30 行注释约定。

### C5. pipeline 创建分散且互相引用

`MinecraftGuiTriangles`(3 个基础 pipeline)+ `BlendPipelines`(3 组 × 最多 17 个变体)+ 每个 `RenderState` 类自写 `pipeline()` / `buildVertices()` / `computeBounds()`;选管线逻辑(`blendMode != SrcOver → … stroke → …`)在 `GuiTriangleRenderState` 与 `ComposeGuiRenderer` 各出现一次。

### C6. 包内混入 UI API

`render/ShadowLight.kt` 放了 `@Composable Modifier.shadow` 与 `LocalShadowLight` —— 业务 API 层,却住在渲染实现包。

### C7. `ComposeGuiRenderer` 既管收集又管提交

`items` 收集 + `absorbAndClear` + `draws` 分组 + `StagedVertexBuffer` 生命周期 + 投影切换 + scissor 钳制 + 恢复原版投影,全在一个类。

## 3. 关键机制:把「三份 when」收敛为「一份几何化 + 三个薄后端」

三套分派的前半段完全相同(命令 → 局部坐标三角网格 + 样式),差异只在最后一步:

| 后端 | 消费局部网格的方式 |
|---|---|
| 2D GUI | 网格原样提交,pose 矩阵在 GPU 端变换 |
| 3D 透视 | 网格顶点 CPU `map3D`(layer3D)后提交,pose=identity |
| CPU 快照 | 网格进重心填充(逐像素) |

目标结构:

```
DrawCommand
   │  CommandDispatcher(平台层,唯一的一个 when)
   ▼
局部三角形网格(TriMesh: x, y, coverage[, vertexColors]) + PaintState(已求值 ARGB)
   │
   ├─ GuiStateBackend   → GuiElementRenderState(2D)
   ├─ PerspectiveBackend → map3D 顶点 → 屏坐标三角形(3D)
   └─ RasterBackend     → 重心填充 IntArray(快照)
```

- **新增一个命令类型 = dispatcher 加一个分支 + 各后端加一个消费实现**(现状是改三套 when 的中段,漏改即行为分叉);
- **3D 与 2D 的 when 直接合并**:`render3D` 无需自己的几何化镜像,仅保留「顶点变换 + OPAQUE coverage」段 —— 削减重复量最大的一处;
- 发射方式:**保留 `when(sealed)` 不用 visitor 接口**(Kotlin sealed when 编译为高效分派,零虚调用;且避免在移植录制层加 `accept()`);
- 运行时零成本:后端接口为单方法函数类型或 sealed 后端枚举,不引入对象分配(TriMesh 与 PaintState 用现成 `FloatArray` / `Int` 传递,不包对象)。

## 4. 目标模块划分(平台层,每文件单一职责)

```
platform/render/
├─ dispatcher/
│   └─ CommandDispatcher.kt        # 唯一命令分派:命令 → 几何化调用 + 后端消费
├─ tessellator/                    # 从 GeometryTessellator 拆出(同一 Sink,零分配不变)
│   ├─ Sink.kt                     # 现 Sink(顶点缓冲复用)→ 独立文件
│   ├─ Flatten.kt                  # 细分判据 + 贝塞尔 flatten + 近共线简化
│   ├─ Offset.kt                   # miter 偏移(offsetPolygon)
│   ├─ Fill.kt                     # 三环填充 + fillInner + 耳切
│   ├─ Stroke.kt                   # strokeRing + caps + cornerCap
│   ├─ ShapeFill.kt                # 圆/椭圆/弧/圆角矩形/线/Path/点 入口
│   ├─ SelfIntersect.kt            # findCrossings + splitFill
│   └─ ShadowField.kt              # shadowFill + fillShadowInner + earClipShadowInner
├─ paint/                          # 颜色求值(消除 C3)
│   ├─ GradientSampler.kt          # sampleGradient + lerpColor(统一 HSV/RGB 策略)
│   ├─ ColorEvaluator.kt           # applyColorFilter / toArgb / scaleAlpha(合并两版差异)
│   └─ PaintState.kt               # 已求值 0xAARRGGBB + blendMode(后端消费的中间表示)
├─ backend/
│   ├─ GuiStateBackend.kt          # 现 MinecraftRenderContext 2D 分支,命名收敛为职责名
│   ├─ PerspectiveBackend.kt       # map3D + 3D 顶点管线(现 render3D 的变换段)
│   ├─ RasterBackend.kt            # 现 GraphicsLayerRasterizer,消费同一份几何
│   └─ Backends.kt                 # 后端选择(2D/3D 共享 dispatcher 一次遍历)
├─ submit/                         # 提交层收敛(C7)
│   ├─ ComposeGuiRenderer.kt       # 只留:收集(absorbAndClear)+ 生命周期(register/unregister)+ 帧序
│   ├─ DrawBatcher.kt              # 从 renderer 拆出:分组(同 pipeline/scissor/texture)/ scissor 钳制 / 逐 draw 提交
│   └─ GuiElementStates.kt         # GuiTriangleRenderState / GuiShadowRenderState(保留,含 bounds 计算)
├─ pipeline/
│   ├─ MinecraftGuiTriangles.kt    # 基础三项 pipeline(不动)
│   └─ BlendPipelines.kt           # blend 变体(不动)
└─ resource/
    └─ ImageTextureCache.kt        # 贴图上传缓存(不动,命名收敛)
```

同时(C6):`ShadowLight.kt` 的 `@Composable Modifier.shadow` 移到 `platform/ui/`,render 包只留 `LocalShadowLight` CompositionLocal 或一并移走。

## 5. 迁移顺序(每阶段独立可验证,不破坏功能)

| 阶段 | 内容 | 验证 | 收益 |
|---|---|---|---|
| P1 | 提取 `paint/`:`GradientSampler` + `ColorEvaluator`,`MinecraftRenderContext` 与 `RasterBackend` 改为调用共享实现(先消除第一份拷贝,含 HSV/RGB lerp 分叉的**语义对齐**) | build + runClient 渐变/滤镜样例对比 | 消除 C3,修复 lerp 分叉 |
| P2 | 定义 `TriMesh` 中间产物 + `CommandDispatcher`(几何化段),`render()` 2D 分支与 rasterize 接入 | build + runClient 几何回归 | 三份 when → 两份的后半差异 |
| P3 | `render3D` 改造:删除镜像 when,复用 dispatcher 几何化,仅保留 `map3D` 顶点段 | build + runClient 3D(rotationX/Y)回归 | 削减最大重复块 C1 |
| P4 | `GeometryTessellator` 拆为 `tessellator/` 多文件(object 拆多个,同一 `Sink`,算法零改动) | build + lint | god object C4 瓦解 |
| P5 | `submit/`:`DrawBatcher` 抽出,`ComposeGuiRenderer` 收敛;`ShadowLight` UI API 迁移 | build + runClient 全样例 | C5/C6/C7 收敛 |
| P6 | 可选:为 `tessellator/` + `paint/` 补 JVM 单测(拆后已无 MC 依赖,可脱离 Minecraft 测试) | gradle test | 可测试性 |

每阶段以 `mcp__idea__get_file_problems` → `lint_files` → `build_project(rebuild=true)` 全量链路把关;P1–P5 每步为纯搬移/提取,理论上行为像素级不变。

## 6. 风险与保留

- **语义分叉(最高风险)**:P1 需处理 `lerpColor` HSV 与 RGB 两版差异 —— 先定"哪个是正确语义"(建议以 `MinecraftRenderContext` 的 HSV + 色相环绕为准,GPU 路径为准绳),再统一,不能简单取一;
- **性能**:所有拆分不触碰缓冲复用 / LRU / 缓存生命周期;`CommandDispatcher` 用 sealed when 保持编译期分派;P3 的 3D 路径与 2D 共用 `triangleCache` 指纹,需确认 3D 顶点数少时缓存命中率不回退;
- **边界**:`MinecraftRenderContext` 收敛为 `GuiStateBackend` 后,其私有缓存(`triangleCache` / 指纹)随 backend 走;`ComposeGuiRenderer.absorbAndClear`(父屏)语义保持;
- **实施需用户确认**:涉及架构方向调整,按 AGENTS.md 约束 #1 不自行实施。

## 7. 待确认决策项

1. lerp 语义以哪条路径为准(建议 HSV + 色相环绕);
2. 后端接口形态:单方法函数类型 vs sealed 后端枚举(建议后者,便于 3D/2D 共用 dispatcher 时按命令携带 `layer3D` 选路);
3. `LocalShadowLight` 是否随 `Modifier.shadow` 一并迁出 render 包;
4. P6 单测是否纳入本次范围。