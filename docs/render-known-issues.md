# 渲染问题登记（已修复）

> 登记方：IbukiGourd。用途：把消费方实际用到的绘制 API 与观察到的渲染现象集中登记在此，
> 便于 CMP 侧圈定排查范围。
> 下列现象均已定位并修复，保留根因与修复点备查。

## 1. 本轮用到的 API

场景：Sokitsu 曲线编辑画布（一块 `Canvas` 内自绘底 / 网格 / 曲线 / 控制点），
外加一条动画预览（`Slider` + 方块位移 + 播放/暂停）。

| 类别 | API | 使用形态 |
|---|---|---|
| 画布 | `androidx.compose.foundation.Canvas(modifier) { … }` | 单节点一次画完所有层 |
| 填充 | `DrawScope.drawRect(color)` | 画布底、单位正方形填充 |
| 描边 | `drawRect(color, topLeft, size, style = Stroke(width = 1f))` | 单位正方形边框、8dp 控制点方块描边 |
| 线段 | `drawLine(color, start, end, strokeWidth = 1f / 2f)` | 网格线、引导线、曲线折线（约 250 段短线段，逐段调用）、手绘虚线 |
| 裁剪 | `clipRect(0f, 0f, size.width, size.height) { … }` | 把过冲的曲线裁在画布内 |
| 几何 | `Offset`、`Size`、`Color.copy(alpha = …)` | — |
| 指针 | `Modifier.pointerInput` + `awaitEachGesture` / `awaitFirstDown` / `drag` | 控制点拖拽 |
| 指针 | `awaitPointerEventScope` + `awaitPointerEvent()`（`PointerEventType.Move` / `Enter` / `Exit` / `Press`） | 控制点悬停高亮 |
| 指针 | `Modifier.pointerHoverIcon(PointerIcon.Hand / NotAllowed)` | 光标形状 |
| 布局 | `Modifier.background` / `Modifier.offset(x = Dp)` / `Modifier.width` · `height` · `size` | 预览轨道与运动方块 |
| 动画 | `rememberInfiniteTransition()` + `animateFloat` + `infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart)` | 预览播放 |

消费方当前**未使用** `drawPath` / `Path`、`drawPoints`、`Brush` 渐变、`drawIntoCanvas`。

## 2. 已修复的问题

### 2.1 `PathEffect`（虚线）不生效 → 已修复

现象：`drawLine(..., pathEffect = PathEffect.dashPathEffect(...))` 仍按实线绘制。

根因：描边链路未把 `Paint.pathEffect` 传给三角化。

修复：`GeometryTessellator.strokeRing` 消费 `DashPathEffect`（按弧长切段）与
`CornerPathEffect`（倒圆），`geometryFingerprint` 把 pathEffect 纳入指纹。

消费方仍保留手工切分虚线（`drawDashedLine`）——两条路径都在，未切换。

### 2.2 多段 `drawLine` 拼成的曲线有接缝 → 已修复

现象：约 250 段、步长 1px、线宽 2f 的短线段逐段 `drawLine` 画出的曲线，
拐折处可见缺口 / 粗细不匀。

根因：每段独立 butt 端帽，端帽 fringe 在拐折重叠区二次混合。

修复：`ConnectedLineMerger` 把「同 paint、同矩阵、同裁剪、首尾严格相接」的连续
`DrawLineCommand` 合并为一条 Polygon 折线，由描边带统一 join（两端仍按原 cap）。

### 2.3 1px 矩形描边四边不一致 → 已修复

现象：`drawRect(..., style = Stroke(width = 1f))` 画出的单位正方形边框，
四条边明暗 / 粗细不一致，个别边看似缺失。

根因：`tryHairlineStrokeRect` 四边取整规则不一致（左 / 上向内、右 / 下向外），
半透明描边压在填充上的底色强弱不同。

修复：四边统一取「覆盖最大的一列 / 一行」，整数坐标 50/50 并列时向内取。

### 2.4 线段端点被画成旧值（引导线「跳动」）→ 已修复

现象：曲线编辑画布里的引导线偶尔整条错位——端点停在几十帧之前的位置，
与同帧手柄方块 / 读数不符，之后自行恢复。

根因：`geometryFingerprint` 的折叠 `h1 = h1 * 31 + bits` 对字段位是线性的，
靠后混入的字段只在低位留下差异，坐标近似的不同几何会算出同一个键；
`triangleCache` 命中后把**另一份几何**的顶点交给渲染端。

实测（同一支画笔、同一锚点、只差末端的线段）：5.6 万个互异坐标里 91 组键重复，
随机 30 万对 50 组；修复后同规模 0 组。曲线因混入数百个点、熵足够，从未中招。

修复：进入折叠**之前**逐字段做 64 位雪崩（splitmix64 fmix）。
注意只在折叠之后补一次雪崩无效——雪崩是双射，不会减少重复键数。

## 3. 复现入口

IbukiGourd：dev client → 测试屏列表 → 「曲线编辑器测试」
（`common/src/devOnly/kotlin/moe/forpleuvoir/ibukigourd/test/sokitsu/BezierCurvePlotTest.kt`）。

主画布 280dp，同一块 `Canvas` 内含：1px 单位正方形边框 + 1px 网格与引导线 + 2px 曲线折线 + 8dp 控制点方块（含 1px 描边）；
测试屏带「逐行扫描」与全精度读数，可复现 2.4 这类单帧几何异常。
