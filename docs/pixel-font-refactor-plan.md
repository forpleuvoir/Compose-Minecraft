# Fusion Pixel 字体系统重构计划(交接执行文档)

> 本文档自包含,写给**新的执行会话**。前序会话在接入 fusion_pixel 时产生了大量"面向症状"的补丁,
> 架构决策反复,上下文已被污染。执行本文档前,**先完整读完 §0 铁律,再通读全文,最后动手**。
> 项目 AGENTS.md 的所有约定(改动需用户批准、构建走 IDEA MCP 等)继续生效。

---

## §0 绝对注意事项(铁律 —— 违反任意一条即停止并重新评估)

1. **资源加载只走 `mc.resourceManager`**。
   禁止 `getResourceAsStream` 类加载器直读(Fabric dev 实测静默失败)。
   任何加载失败必须打 ERROR 日志;禁止不带日志的裸 `runCatching` 吞异常。

2. **度量必须按 run 的字体解析**(依据 `style.fontOriginal` 选择 MetricsSource)。
   禁止全局单例快照式度量;"构造时冻结一个测量器量所有字体"就是本轮最大 bug 的根源。

3. **字号/缩放基准只允许引用统一入口**
   `TextRenderConfig.effectiveDefaultFontSizeSp` 与 `fontScaleBasePx`。
   禁止出现 `/9f`、`12f`、`16f`、`18f` 等字面量基准(历史上四处写死引发连锁错乱)。

4. **原版字形基线锚点 = 行顶 + 7px**(vanilla `GlyphBitmap.getTop() = 7 − bearingTop`,硬编码)。
   凡经 `GuiTextRenderState` 提交的文本,y 必须补偿:`y + (布局基线 − 7)`。
   布局基线来自该 run 字体的 MetricsSource(像素字体 @em12 ≈ 13px → 补偿 +6)。

5. **命名资源字体没有跨字体回退**。缺字回退段必须显式
   `style.withFont(FontDescription.DEFAULT)`;否则该字符在命名字体集里只会画出方框。

6. **state 版 BasicTextField 不经过 CoreTextField**。
   它的可见文本由 `TextFieldTextLayoutModifier`(input/internal/)绘制。
   修改输入框相关行为时,CoreTextField 与 state 版 BasicTextField 两条路径
   必须同时覆盖,或收敛到单一咽喉点(见 Phase 2 的 resolver)。

7. **每次编辑后的强制复核顺序**:grep 复核导入与引用 → IDEA MCP lint_files →
   build_project(rebuild=true)。三者缺一不可;"编译通过"≠"验证通过"。

8. **静态推演到极限仍解释不了现象时,用运行时证据**:
   打开 `TextRenderConfig.debugTextBounds`(会打印每个 run 的 `[TT]` 字体/粗体/分流日志),
   让用户跑一次,读 `fabric/runs/client/logs/latest.log`。该设施是常驻调试能力;
   探针打印本身不得当作修复合入。

9. **不确定就问用户要运行时现象,禁止猜测后直接改代码。**
   本轮多次返工的直接原因就是"猜机制 → 改一版 → 让用户测"循环。

10. **MC 资源路径全小写**(`ModNioPackResources` 会拒绝大写路径并忽略,刷 ERROR)。

11. 用户已明确:**不要面向测试编程**。修复必须落在结构/不变量上,
    使同类问题在该结构下不可能复现,而不是针对单次观察加特判。

---

## §1 用户需求(已定案,勿再变更方向)

- Compose 全局默认字体 = `compose_minecraft:fusion_pixel`(proportional);
  mono 变体 `compose_minecraft:fusion_pixel_mono` 为特殊用途(等宽场景),同家族。
- 像素字体渲染走**原版 FreeType**(`font/fusion_pixel*.json`,provider size=12)——
  **stb 自研管线只服务矢量字体**(minecraft:default 的系统字体链),不得接管像素字体。
- 只有 Compose 渲染的文本使用上述字体;原版自身 UI(tooltip/HUD/F3)零波及。
- 缺字按码点回退 `minecraft:default` 字形。
- 默认字号 = 像素网格 ×2 = 24sp(整数倍缩放锐利);行高语义为像素字体自然行盒。

## 2. 当前状态盘点(工作区未提交,先 commit 作基线再动工)

### 2.1 已验证正确、必须保留(不要重写)
- 资源:`assets/compose_minecraft/font/fusion-pixel-12px-{proportional,monospaced}-zh_hans.ttf`
  + `fusion_pixel{,_mono}.json`(provider ttf size=12 oversample=1)+ 小写许可证文件。
- 原版 FreeType 渲染像素字形:BasicText 已实测正常。
- 盖章:`resolveDefaultFont()` 已接入 BasicText×3、CoreTextField、state 版 BasicTextField(:309)。
- 缺字回退:GuiStateBackend 分段路径(coverage swap 强制 DEFAULT)+ 基线补偿;
  TrueTypeTextWriter.flushVanilla 同样强制 DEFAULT + `vanillaBaselineY` 补偿。
- 双模式基准:`effectiveDefaultFontSizeSp` / `fontScaleBasePx`(四处消费点已迁移)。
- 对照屏(TrueTypeTextDevScene)DisposableEffect 进出恢复 `usePixelDefaultFont`。

### 2.2 已知残留问题(= 本重构目标)
- **度量是全局快照**:`MinecraftParagraphIntrinsics.metrics = activeMetricsSource()`(:129),
  不看 style.font。mono 字段因此被 proportional 表度量(当前两表数值恰好相同,
  但结构错误;且 PixelFont 一旦加载失败会整体回退 msyh/vanilla,实测已发生)。
- 路由靠 `supports()` 字体特判 + 分段 if,散落 GuiStateBackend / TrueTypeTextWriter 两处。
- 基线补偿常量 `7f` 与补偿公式在两处重复。
- `debugTextBounds` 默认 true 未关(诊断期遗留)。
- 粗体观感(stb/FreeType 双绘)与行距需最终人工确认。

### 2.4 工作区现状
- 18 个修改文件 + 新增 PixelFont.kt / FontFallbackDevScene.kt / font 资源目录 + downloads/(缓存)。
- 执行前先 `git add -A && git commit` 存基线(WIP 信息注明"重构前快照")。

---

## §3 目标架构:FontResolver / FontBinding(唯一决策点)

新增 `platform/render/text/FontResolver.kt`:

```kotlin
enum class TextRendererKind { FREE_TYPE, STB_VECTOR, VANILLA_BITMAP }

data class FontBinding(
    val id: FontDescription?,          // null = 未指定(default 语义)
    val renderer: TextRendererKind,
    val metrics: MetricsSource,
    val emPx: Float,
    /** 该绑定缺字时的回退目标(像素族 → DEFAULT;default → 无) */
    val covers: (codepoint: Int) -> Boolean,
)

object FontResolver {
    /** 唯一决策点:style → 绑定。所有度量/分流/回退判定只允许经此处。 */
    fun bindingFor(style: Style): FontBinding { ... }
}
```

解析规则(数据驱动,不再 if 散落):

| style.fontOriginal | renderer | metrics | em | 缺字 |
|---|---|---|---|---|
| fusion_pixel / fusion_pixel_mono | FREE_TYPE(json size=12) | PixelFont 对应实例 | pixelFontEmSp(12) | 回退段强制 DEFAULT |
| null / DEFAULT | enabled ? STB_VECTOR : VANILLA_BITMAP | 链度量 / VanillaMetricsSource | baseFontSizePx(9) | 内联原版(现状) |
| 其他资源字体(unifont/alt/自定义) | VANILLA_BITMAP | VanillaMetricsSource | — | — |

配套:
- `PixelFont` 改为**族注册表**:`Map<Identifier, TrueTypeFont>`(proportional + mono),
  经 resourceManager 加载(已完成),暴露 `fontOf(id)` / `covers(id, cp)` /
  `metricsOf(id)`;两文件的 hmtx 已证实逐字符相等,度量互通不影响正确性。
- `supports()` 删除:是否 stb 接管完全由 binding.renderer 决定。
- `activeMetricsSource()` 保留为薄包装或直接迁移调用点(共 5 处,见 §4)。

---

## §4 实施阶段(每阶段独立提交 + 独立验收)

**Phase 0 · 基线**
commit 当前工作区(WIP)。验收:git status 干净。

**Phase 1 · PixelFont 族注册表**
扩展现有 Map 结构(骨架已在):两 id 各自懒加载、ERROR 日志、
`covers(id,cp)`、`metricsOf(id)`。
验收:启动后断点/日志确认两 id 实例均非 null,advance('x')=6.0@em12。

**Phase 2 · FontResolver 接入(核心)**
新建 FontResolver;迁移五个消费点:
- MinecraftParagraph.platform.kt:129 `metrics = FontResolver.bindingFor(style).metrics`
- GuiStateBackend.kt:295/352/424 三处 `activeMetricsSource()` → `bindingFor(cmd.style)`
- BasicTextField.kt:309 与 CoreTextField 盖章改为调 `FontResolver.defaultBinding()` 语义
  (保持现行为,消除两处重复 resolve 逻辑)
验收:TextInputDevScene 三个输入框(mono/proportional/filter)文字宽度贴合行盒、
光标对齐;BasicText 不回归。

**Phase 3 · 回退与基线收敛**
flushVanilla / 分段路径 / splitGradient 的"强制 DEFAULT + 基线补偿"统一由
binding 提供(一份实现);删除两处手抄。
验收:缺字回退屏基线对齐、希伯来文显示真实字形、阿拉伯文方框(对照组同为方框)。

**Phase 4 · 清理与收尾**
- `debugTextBounds` 默认 false
- 删除 supports() 字体特判、失效注释([TT] 探针保留,属常驻调试设施)
- 全量构建(rebuild=true)+ 手工验证矩阵(§5)全过

---

## §5 手工验证矩阵(交付验收标准)

| 场景 | 预期 |
|---|---|
| dev 菜单正文/粗体标题 | 像素字形;粗体 = FreeType 双绘;宽度贴合行盒 |
| TextInputDevScene 三输入框 | 像素字形;光标/点击对齐;mono 框等宽推进 |
| 缺字回退屏·希伯来文 | 原版字形(非方框) |
| 缺字回退屏·阿拉伯文/泰文 | 方框,且 minecraft:default 对照组同样方框 |
| TrueType 对照屏 | 雅黑矢量(stb),进出后全局模式恢复 |
| 原版 tooltip/HUD/F3 | 原版字体,零变化 |

---

## §6 已验证技术事实(免重查,直接引用)

- 原版字形基线锚点硬编码 = 行顶+7:`GlyphBitmap.getTop() = 7 − bearingTop`;
  缺失字体集 `getFontSetRaw → missingFontSet = AllMissingGlyphProvider` = 方框字形。
- 26.2 默认字体**无阿拉伯文/泰文**;`include/unifont.json` providers 为空
  ⇒ 这两类文字纯原版也方框(回退屏对照组印证)。
- fusion_pixel 两变体:upm=1200,OS/2 行高 1600units → 自然行盒 16px@em12;
  **hmtx 在 ASCII+CJK 全区间逐字符相等**(GPOS 有差异)⇒ 度量互通。
- 原版 TTF provider flags `4194312` = NO_BITMAP|COMPUTE_METRICS;校验格式必须 "TrueType"
  (拒绝 CFF/OTF);stb 同样只支持 glyf —— 选字体文件时避开 OTF/位图格式。
- `TextureSetup` 是 record(值相等)⇒ 相邻同 key 元素合并 Draw 安全。
- state 版 BasicTextField 绘制路径:TextFieldTextLayoutModifier → TextLayoutState →
  TextFieldLayoutStateCache.getOrComputeLayout → **TextMeasurer.measure(scale=nonMeasureInputs.scale)**
  → MultiParagraphIntrinsics → MinecraftParagraphIntrinsics;缩放折算在
  `MinecraftParagraph.layoutText` 内部(maxWidth/scale),**外层不得重复折算**
  (双重折算曾致字段可用宽度减半,2026-08 实测)。
- `mc.resourceManager.open(...)` 通道 dev 可用(shader/lang 先例);
  类加载器直读 mod 资源不可靠(PixelFont 静默失败实例)。
- 像素字体自然度量:proportional 样例串 264px@em12(与 mono 全等);
  行盒 16px、基线 13px(@em12)。

## §7 关键文件索引

| 文件 | 角色 |
|---|---|
| render/text/PixelFont.kt | 像素字体族注册表(度量+覆盖;resourceManager 通道) |
| render/text/MetricsSource.kt | 度量抽象 + activeMetricsSource(将迁入 FontResolver) |
| render/text/TrueTypeFontManager.kt | stb 系统字体链(**不含**像素字体) |
| render/text/TrueTypeTextWriter.kt | stb 矢量渲染器(仅 DEFAULT run) |
| render/backend/GuiStateBackend.kt | drawText 分流 + 分段回退 + 基线补偿 |
| pipeline/ComposeGuiRenderer.kt | 文本批次保序合并(flush-on-key-change) |
| ui/text/TextCompositionLocals.kt | LocalDefaultFont/Size + resolveDefaultFont() |
| ui/text/TextStyleMapper.kt | sp→scale(fontScaleBasePx) |
| assets/compose_minecraft/font/ | json 定义 + ttf + 许可证 |
