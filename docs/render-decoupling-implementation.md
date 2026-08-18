# 渲染实现解耦 —— 重构实施计划

> 状态:计划定稿 v2(2025-08)—— 决策 D1–D5 全部拍板,待实施
> 前置文档:`docs/render-decoupling-review.md`(现状耦合清单 C1–C7、目标模块划分、机制说明)
> 本文档将评审转化为**可执行任务**,每阶段给出任务拆解、涉及文件、验收标准与回滚策略。
> 决策记录见第 4 节(已定稿);重构范围外待评估项见第 4.1 节。

## 0. 重构范围声明(最高约束,先于一切原则)

> **重构只是代码结构调整**。**所有的实现和具体功能都不可修改**(用户明确,2025-08)。
>
> - 「实现」= 算法、语义、常数、分支顺序、坐标换算、取整方式、几何化输出、颜色求值、混合模式等一切代码行为逻辑 —— 一律**原样搬移**,禁止改写、合并、归一、修正;
> - 「具体功能」= 渲染结果与能力集合 —— 任何命令类型、任何渲染路径(GPU 回放 / 3D / CPU 快照 / vanilla 桥)的输出**像素级保持不变**,不新增、不删除、不替换功能;
> - 出现「疑似 bug / 语义分叉 / 更优写法」一律**只记录、不改动**;需要改,先停下报告,用户拍板后作为**独立非重构任务**实施。
> - 判断标准:逐函数「搬移前后 diff 除包名/可见性外应为空」;每个文件搬移后与原文件逐行比对(排除 import 与 internal 定位)。

## 1. 实施原则

1. **行为零变更(硬约束,2025-08 用户明确)**:重构**不允许修改任何实现逻辑**。不是 100% 确定的实现代码,一律**只搬移、不改变逻辑** —— 禁止顺手"修正"疑似问题、禁止合并语义有分歧的实现(如两个 `lerp`)、禁止统一常数/参数。发现行为差异先停下报告,由用户拍板后才可改;
2. **可验证的等价搬移**:P1–P5 每步是纯搬移/提取,完成后 runClient 对照样例应像素级一致;任何步骤发现行为变化(含肉眼不可见、仅数值不同),立即回退该步并报告,不叠加后续阶段;
3. **质量流程铁律**:每阶段完成必须过 `mcp__idea__get_file_problems`(单文件 errors)→ `mcp__idea__lint_files`(批量)→ `mcp__idea__build_project(rebuild=true)`(全量,devOnly 不可漏)三关;
4. **hot path 零分配**:`Sink` 复用、`FloatArray` 扩容、`triangleCache`、阴影 LRU 的对象生命周期与缓冲复用不允许因拆分改变;
5. **一次提交一个阶段**:每阶段一个 commit,消息前缀 `refactor(render): P<n> …`,便于回滚与审阅;
6. **稳定公共接口约束(外部消费者)**:`GuiCommandSink`(addElement/addText)签名与「items 三段注入时序」在本重构全程保持 ——
   `platform/ui/draw/VanillaDraw.kt`(`Modifier.vanillaDraw` / `Modifier.postVanillaDraw`,T.38,正式 API + 双通道)是第二收集器来源:
   每帧经 `VanillaDrawState.runFrameCallbacks` / `runPostFrameCallbacks` 在 `MinecraftComposeScene.renderFrame` 内以
   **前渲染(头部,画 Compose 之下)→ `renderContext.render`(Compose 内容)→ 后渲染(尾部,画 Compose 之上,含 Popup/Dialog)** 的三段时序注入收集器;
   此外 `VanillaDrawState.graphics`(当前帧原版 extractor,`guiScaleEnabled=true` 原版通道用)由 `ComposeScreen.extractRenderState`
   **在 renderFrame 前写入、完成后清除**(extract 阶段栈内可见)。桥直接构造 MC element、不经过 dispatcher,
   与 P1–P4 改动面正交;P5 拆 `DrawBatcher` 时三段顺序一律不得重排。

## 2. 阶段依赖

```
P1(paint 共享) ──> P2(dispatcher+TriMesh) ──> P3(合并 render3D)
                                        └──> P4(tessellator 拆分,可与 P3 并行)
P5(submit 收敛 + ShadowLight 迁移) 依赖 P2/P3 落地后的结构
P6(单测) 依赖 P1/P4
```

P1 先行:颜色求值是 P2/P3 共用的地基;但按硬约束 #1,**两处 lerp 语义分歧(HSV vs RGB)不做统一**,仅原样搬移共享(差异显式注释),是否统一由 D1 决策决定。

## 3. 分阶段任务

### P1 — 提取 paint 共享层(消除 C3)

**目标**:两份 `gradientVertexColors` / `sampleGradient` / lerp **原样收敛为共享实现**(行为零变更,硬约束 #1);`applyColorFilter` / `toArgb` 统一。

| # | 任务 | 动作 |
|---|---|---|
| 1.1 | 新建 `platform/render/paint/GradientSampler.kt` | 从 `MinecraftRenderContext` **原样搬移** `sampleGradient(t, colors, stops, tileMode, alphaMul, colorFilter)`、`gradientTAt`、`lerpColor`(HSV+色相环绕);从 `GraphicsLayerRasterizer` **原样搬移** `gradientVertexColors` / `sampleGradient` / `lerpColorARGB`(RGB 直插)—— 两个 lerp **各自保留原实现**,共享不合并语义 |
| 1.2 | 标注语义分歧(只标注,不改逻辑) | 在 `GradientSampler.kt` 以注释记录:① `lerpColor`(HSV+短弧)用于 GPU 回放路径 —— **需求约束(用户确认 2025-08):必须支持「色相渐变」,RGB 直插无法表达,HSV 是当前唯一可表达方案;勿视为自研偏差**;② `lerpColorARGB`(RGB 直插)用于 CPU 快照路径 —— **不能表达色相渐变(已知能力缺口,现状保留)**;③ 官方 `androidx.compose.ui.graphics.lerp` 为 **Oklab** 插值(三者互不相同)。**不合并、不修正**,统一与否待 D1 |
| 1.3 | 新建 `platform/render/paint/ColorEvaluator.kt` | 迁入 `applyColorFilter`、`toArgb(alphaMultiplier)`、`scaleAlpha`、`Color.toArgbInt`、`paintColorArgb`;凡两处实现有差异的,**以调用方各自原来的一份为准原样搬移**,不归一(如 `Color.toArgbInt` 的取整方式两处不同 → 两个私有扩展各自保留) |
| 1.4 | 改造两个调用方 | `MinecraftRenderContext` 删除本地函数,改调共享(行为等价);`GraphicsLayerRasterizer` 同步 |
| 1.5 | 验收 | runClient 对照:线性/径向/扫描渐变(含 Clamp/Repeated/Mirror/Decal)、colorMatrix/tint/Modulate 滤镜、drawVertices 逐顶点色;**GPU 路径与 CPU 快照路径输出分别与重构前一致**(两者之间允许不同 —— 那是现状,不是本次要修的) |

**涉及文件**:新增 `paint/GradientSampler.kt`、`paint/ColorEvaluator.kt`;修改 `MinecraftRenderContext.kt`、`GraphicsLayerRasterizer.kt`。

### P2 — CommandDispatcher + 统一几何化(消除 C1 前半)

**目标**:命令遍历唯一化;2D 与快照后端共享几何化结果。

| # | 任务 | 动作 |
|---|---|---|
| 2.1 | 定义中间产物 | `internal class TriMesh(vertices: FloatArray, colorArgb: Int, vertexColors: IntArray?, blendMode: BlendMode, scissor: Rect?)` —— 直接引用现有 `GeometryTessellator.Sink` 输出与 `PaintSnapshot` 求值结果,不包对象增加分配;文本/阴影/图片/渐变矩形命令走各自专用通道(不进 TriMesh) |
| 2.2 | 定义后端接口 | `internal interface GeometryBackend { fun text(cmd)/fun shadow(cmd)/fun image(cmd)/fun gradientRect(cmd)/fun triMesh(mesh: TriMesh) }`(候选形态,D2 定) |
| 2.3 | 新建 `dispatcher/CommandDispatcher.kt` | 单一 `when(command)`:提取命令几何参数 → 调 `GeometryTessellator.xxx` → 组装 `TriMesh` → 调 `backend.triMesh`;保留 `layer3D != null` 的早期路由(选 2D/3D 后端,D3 定) |
| 2.4 | `MinecraftRenderContext.render` 接入 | 2D 分支删除,改走 dispatcher → `GuiStateBackend`(从 `render` 原逻辑收敛而来) |
| 2.5 | `GraphicsLayerRasterizer` 接入 | `rasterize` 循环删除,改走 dispatcher → `RasterBackend`(原填充函数保留为后端内部方法) |
| 2.6 | 验收 | runClient 几何回归:矩形(描边/渐变)、圆角、圆、椭圆、弧、线、Path(含自交)、Points(三种 mode)、drawVertices;`toImageBitmap` 快照样例 |

**涉及文件**:新增 `dispatcher/CommandDispatcher.kt`、`backend/GeometryBackend.kt`、`backend/GuiStateBackend.kt`、`backend/RasterBackend.kt`;修改 `MinecraftRenderContext.kt`、`GraphicsLayerRasterizer.kt`。

### P3 — 合并 render3D(消除 C1 后半,最大削减)

**目标**:`render3D` 删除镜像 when,仅保留顶点变换段。

| # | 任务 | 动作 |
|---|---|---|
| 3.1 | `PerspectiveBackend` 落地 | `map3D` 迁移;后端内保留 `emitTriangle` / quad / tessellated 循环,但**输入几何**改由 dispatcher 的 `TriMesh` 提供(局部坐标),变换后输出屏坐标三角形 |
| 3.2 | 删除 `MinecraftRenderContext.render3D` 镜像 when | 3D 命令(携带 `layer3D`)走 dispatcher 几何化 → `PerspectiveBackend`;`OPAQUE_COVERAGE`、顶点丢弃(w<=0 防御)语义逐条保留(对照 T.15 文档) |
| 3.3 | 3D 纹理/滤镜 | 3D 路径的 `applyColorFilter` 走 P1 共享实现;3D 不支持的文本/阴影/图片分支维持现行为(降级 2D 近似或跳过),对照 `render3D` 原 when 的分支覆盖 |
| 3.4 | 缓存验证 | 确认 3D 与 2D 共用 `triangleCache` 指纹时命中率不回退(3D 顶点数通常较少;如回退,指纹按后端维度分流,D4 记录) |
| 3.5 | 验收 | runClient 3D 回归:单轴 rotationX/rotationY、双轴组合、绕中心旋转(参考 T.15 文档探针方法),与重构前行为一致 |

**涉及文件**:新增 `backend/PerspectiveBackend.kt`;修改 `MinecraftRenderContext.kt`(删 render3D 镜像)、`dispatcher/CommandDispatcher.kt`。

### P4 — tessellator 拆分(消除 C4)

**目标**:`GeometryTessellator` object 按文件职责拆分,算法零改动。

| # | 任务 | 动作 |
|---|---|---|
| 4.1 | 建 `tessellator/` 包,迁移文件 | `Sink.kt` / `Flatten.kt`(细分判据+flatten+simplifyPolygon)/ `Offset.kt`(offsetPolygon)/ `Fill.kt`(fillPolygon+fillInner+earClipInner)/ `Stroke.kt`(strokeRing+caps+cornerCap)/ `ShapeFill.kt`(circle/oval/arc/roundRect/line/path/points/rectGrid)/ `SelfIntersect.kt`(findCrossings+splitFill)/ `ShadowField.kt`(shadowFill+fillShadowInner+earClipShadowInner) |
| 4.2 | 保持 object 形态 | 拆成多个 internal object,构造函数/属性按依赖注入或同包可见;`Sink` 类与 `aaScale` 语义不变;公开入口(`roundRect`/`oval`/`arc`/`line`/`path`/`points`/`shadowFill`/`circle`)签名不变 |
| 4.3 | 文件头注释迁移 | 现有 30 行约定注释(三环结构、coverage 语义、简化原则)拆分配到对应文件顶部,`@file:Suppress` 保留在需要处 |
| 4.4 | 验收 | lint 全绿;build 全量;runClient 几何回归(与 P2.6 同一批次样例) |

**涉及文件**:新增 `tessellator/` 8 个文件;删除 `GeometryTessellator.kt`(或保留为 facade 转发,视 D2 后端形态定)。

### P5 — submit 收敛 + ShadowLight 迁移(消除 C5/C6/C7)

| # | 任务 | 动作 |
|---|---|---|
| 5.1 | 抽 `submit/DrawBatcher.kt` | 从 `ComposeGuiRenderer` 迁出:分组逻辑(同 pipeline/scissor/textureSetup)、`scissorChanged`、`enableScissor`(像素钳制+y 翻转)、投影设置与 `restoreVanillaProjection`、逐 draw 提交(`executeDraw`);`StagedVertexBuffer` 生命周期随 batcher |
| 5.2 | `ComposeGuiRenderer` 收敛 | 只保留:`items` 收集、`absorbAndClear`、`register/unregister/active`、帧序(`render()` 的 ensureAlive→prepare→upload→draw→清理 编排);prepare 的文本 glyph 化逻辑保留在 renderer 或移入 batcher(D4 定) |
| 5.3 | `ShadowLight.kt` 迁移 | `@Composable Modifier.shadow` 移到 `platform/ui/`(与业务 API 层相邻);`LocalShadowLight` 随迁或留 render 包(D3 定);render 包不再 import `androidx.compose.runtime` |
| 5.4 | 验收 | 全样例 runClient;F3 调试覆盖层/MC HUD/toast 与 Compose 共存顺序不变;Dialog scrim 层叠、父屏 `absorbAndClear` 场景回归;**vanilla 桥回归:`vanillaDraw` 垫底(Compose 之下)、`postVanillaDraw` 置顶(含 Popup/Dialog 之上)、`guiScaleEnabled=true` 原版通道(blitSprite/item/tooltip)三者均正常,三段注入时序未变** |

**涉及文件**:新增 `submit/DrawBatcher.kt`;修改 `ComposeGuiRenderer.kt`、`ShadowLight.kt`(迁移)。

### P6 — 单测(可选)

| # | 任务 | 动作 |
|---|---|---|
| 6.1 | 确认 common 模块测试配置 | 若 common 无 test 源码集,确认 Gradle 可加(需在构建层验证,见 D4) |
| 6.2 | `tessellator/` 单测 | 纯几何:圆/圆角矩形/路径三角化顶点数、覆盖率(面积 vs 三角形和)、自交切分输出、shadowFill 距离场符号(内部正外部负) |
| 6.3 | `paint/` 单测 | 渐变采样端点值、TileMode 四模式、颜色矩阵、tint/modulate 预期 ARGB |
| 6.4 | 验收 | 测试全绿;不引入 MC/渲染上下文依赖(纯 JVM) |

## 4. 决策点跟踪表(2025-08 全部定稿)

| 编号 | 决策点 | 选项 | 建议 | 状态 |
|---|---|---|---|---|
| D1 | lerp 语义与色相渐变 | **HSV 是需求约束(用户确认 2025-08):必须支持「色相渐变」(如彩虹/hue sweep),RGB 直插无法表达,GPU 回放路径的 `lerpColor`(HSV+短弧)是必需能力,重构保留、不统一、不修**。CPU 快照路径 `lerpColorARGB`(RGB 直插)不能表达色相渐变 —— **已确认保持现状、不立项(2025-08 用户拍板)**;重构仅在 P1 原样搬移并注释该缺口 | **已定:HSV 必须保留;快照缺口保持现状,不立项** | ✅ 已定 |
| D2 | 后端接口形态 | 单方法函数类型 / 枚举+全局 when 分派 / **sealed 接口、方法按命令细分** | **sealed `GeometryBackend` 接口,方法按命令类型细分(`triMesh` / `text` / `shadow` / `image` / `gradientRect`)—— 双向扩展编译期强制:新增后端=新实现类(必须实现全部命令方法,不会漏);新增命令=接口加方法(各后端必须实现,编译器兜底);对比:单方法函数类型命令参数会撑爆签名,枚举+when 需改所有分支易漏**。用户拍板:**sealed 接口多方法(2025-08)** | ✅ 已定:sealed 接口多方法 |
| D3 | `LocalShadowLight` 归属 | **技术背景(2025-08 确认):`platform/render/ShadowLight.kt` 的 `Modifier.shadow` 是平台扩展 —— 经 `GraphicsLayer` 的 `shadowElevation` / `ambientShadowColor` / `spotShadowColor` / `shadowLightDirectionX/Y` 属性 → `DrawShadowCommand` → `MinecraftShadowRenderer`(GPU erfc 距离场);参数语义逐条照抄 Skia SkShadowUtils(σ=0.667e、ambient 0.039/spot 0.19·(1-e/600)、spot 偏移/zRatio 钳制 0.95),与官方桌面 shadow 视觉对齐,区别仅在渲染机制(无离屏)与光源可配置(`LocalShadowLight`)。因此迁出属纯位置调整,不碰任何渲染语义**。选项:随 `Modifier.shadow` 一并迁到 `platform/ui/`(render 包不依赖 compose runtime)/ 留 render 包 | 随迁出(符合 P5 方向,且已确认无语义耦合)。用户拍板:**随 shadow 迁出 ui 包(2025-08)** | ✅ 已定:随迁出 |
| D4 | 次要边界 | ① **3D 缓存指纹是否分流**:现状 `render3D` 不查 `triangleCache`;P3 统一几何化后 3D 是否进同一缓存、key 是否加 2D/3D 维度(性能取舍,不碰行为)—— **默认:3D 先跟进共享缓存,key 含后端维度**(避免 2D AA 网格与 3D OPAQUE 网格互撞);② **prepare 文本 glyph 化归属**:P5 拆 `DrawBatcher` 后,「文本→GlyphRenderState」放 renderer 还是 batcher(纯归属)—— **默认随 renderer**(它持有 items 与文本处理现状);③ **P6 单测是否纳入**:给 tessellator/paint 补 JVM 单测(新增工作项)—— **默认不纳入本次重构**,完成 P5 后评定。用户拍板:**按默认三项(2025-08)** | ✅ 已定:按默认三项 |
| D5 | vanilla 桥定位 | 已正式化(双通道 + post 变体,不再弃用;devOnly 演示与下游依赖它)——「GuiCommandSink 长期稳定」升级为**确定项**,不再评估移除;保留决策点仅为记录:若未来桥需演进(如支持 3D/渐变),仍以扩充桥 API 而非改动 GuiCommandSink 为原则 | 已定,无需再确认 | ✅ 已定 |

## 4.1 重构范围外的待评估项(已登记,不阻塞重构)

| 编号 | 事项 | 现状(2025-08) | 处理 |
|---|---|---|---|
| X1 | 官方 `Modifier.dropShadow` / `Modifier.innerShadow`(Shadow 模型) | **占位不可用**:`Paint.setBlurFilter = Unit`(`Blur.kt:33`)模糊失效;`Canvas(image)` 命令记录型不填充位图像素 → 阴影位图空;Alpha8 无上传路径。渲不出真实阴影(调查见 review §1.1) | 已登记待评估;属行为变更/新功能,不入重构;若业务或下游依赖用到,单独立项设计 |
| X2 | CPU 快照路径色相渐变缺口 | `lerpColorARGB`(RGB 直插)不能表达色相渐变,与 GPU 路径(HSV)不一致 | D1 已定:保持现状、不立项 |

## 5. 完成定义(DoD)

- [ ] P1–P5 全部落地,`get_file_problems` errors 为 0,`lint_files` 无 items;
- [ ] 全量 `build_project(rebuild=true)` 通过(common + fabric + neoforge,devOnly 已编译);
- [ ] runClient 回归样例清单全过:几何(矩形/圆角/圆/椭圆/弧/线/Path/Points/顶点色)、渐变四模式、滤镜(colorMatrix/tint/Modulate)、阴影(矩形/圆角/Path、多阴影)、图片(最近邻/双线性)、3D(单双轴旋转)、文本(富文本/渐变文本/字号)、Dialog scrim 层叠、父屏 absorbAndClear、F3/HUD 共存;
- [ ] 无新增对象分配点(对照拆分前 hot path:每帧 `GeometryTessellator.Sink` 复用 + 缓存命中路径);
- [ ] 每阶段一个 commit,消息前缀 `refactor(render): P<n>`;`docs/render-decoupling-review.md` 与本文档同步更新为已实施状态。

## 6. 回滚策略

- 每阶段独立 commit → `git revert <P<n> commit>` 即可回到上一稳定态;
- P2/P3 为结构大改,合并前保留 dispatch 时代码的对照分支(runClient 截图基线)以便灰度对比;
- 若 P3 缓存命中率回退且未能在阶段内解决,先回退 P3,保留 P1/P2 已合并的共享几何化(2D 路径),3D 路径维持原镜像 when 直至单独攻关。