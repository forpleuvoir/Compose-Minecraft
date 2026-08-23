# 文本度量与渲染系统 · 总设计(2026 定案版)

> 本文是文本系统重构的唯一权威依据,取代本文件旧版(双模式/I5 全局默认字号等内容已废)。
> `pixel-font-refactor-plan.md` 降级为历史参考:其 §0 中**流程性**铁律继续有效,
> **设计性**内容(双模式、哨兵映射、fontScaleBasePx 等)一律以本文为准。
> 事实底账见 `text-pipeline-census.md`(447 条普查数据)。
>
> 核心定案(用户拍板):
> 1. **pixel-font 只是当前默认字体,零特殊处理**——历史上围绕它建立的一切
>    特判(双模式开关、哨兵映射、专用分段)都是上下文污染,全部拆除;
> 2. **所有字体一个概念**:像素/系统/原版位图/自定义/外部字体一律平权,
>    只有渲染通道不同;
> 3. **默认字号是字体的属性**(原版 18sp / 像素 24sp / 系统 16sp 只是现值),
>    不写死、不散落、统一在字体定义;
> 4. 任何功能取舍必须先与用户确认;核心功能一项不能少。

---

## §0 铁律

### 流程法则(承自 pixel-font-plan,继续生效)

1. 资源加载只走 `mc.resourceManager`;禁止类加载器直读;失败必须打 ERROR,禁止裸吞。
2. MC 资源路径全小写。
3. 每次编辑后强制复核:grep 复核导入引用 → IDEA MCP lint_files → build_project(rebuild=true)。
4. 静态推演到极限仍解释不了现象时用运行时证据(debugTextBounds / 日志探针);
   探针打印不得当作修复合入。
5. 不确定就问用户要运行时现象,禁止猜测后直接改代码。
6. 不面向测试编程:修复落在结构/不变量上,使同类问题在该结构下不可能复现。
7. 涉及文本几何的改动,必须在 TextInputDevScene(三输入框)、FontFallbackDevScene、
   TrueType 对照屏三处同时人工回归,缺一不交付。

### 架构法则(本轮定案)

| # | 法则 |
|---|---|
| A1 | **唯一字体概念**:`PlatformFont` 是一切字体的抽象;禁止为任何具体字体(含 fusion_pixel)书写类型特判或 if 分支 |
| A2 | **决策单点**:字体解析、渲染通道、度量、回退目标只发生在 `FontResolver.resolve()`;其余代码只消费结果 |
| A3 | **绑定随数据走**:布局期解析出的 `ResolvedFont` 实例引用随绘制命令到达渲染端;布局与渲染使用同一绑定,结构上不可能分叉 |
| A4 | **尺寸空间唯一**:布局一律在最终像素空间(emPx)进行;`emPx = sp × density × fontScale`(1sp == 1px @density1,全字体统一,**无基准除法**);缩放仅存在于位图通道的 pose(一处) |
| A5 | **度量恒等于渲染**:`advance(cp)` 逐码点精确(hmtx / widthProvider 同源),支持任意比例字体;kern 逐对独立;任何 run 的布局宽度只能来自它最终渲染所用字体的度量 |
| A6 | **默认字号随字体**:未显式指定 fontSize 时取当前解析字体的 `defaultSizeSp`;全系统不允许存在第二处默认字号定义(硬编码 18.sp/24.sp 等一律消灭) |
| A7 | **全覆盖回退**:每字体声明 `fallback`(字体 id),链条终局必达 MC_BITMAP 终端字体(unifont 兜底);不存在无人负责的码点 |
| A8 | **无模式开关**:`FontResolver.defaultFontId` 是「默认字体是谁」的唯一配置点;dev 对照场景通过改配置/Provider 实现,不产生模式分支语义 |

---

## §1 现状问题(census 摘要 + 本轮判断)

四类结构性问题(细节见 census):

| 类 | 问题 | 实证 |
|---|---|---|
| A | 缩放公式 5 处独立实现(toPlatformData / toTextScale / autoSize×2 / 段级 ratio) | 同一公式四种写法,基准切换需同步多处,历史上反复漏迁 |
| B | 行高/基线三种约定并存(7.2f 启发式、7f 锚点补偿、0.8×行盒)+ 像素自然行盒 | 光标/选区一套、绘制另一套 |
| C | 模式/字体分支 ≥20 处(盖章×2 入口、supports、分段 swap、双层门控、哨兵映射) | 任一开关变动,四处行为各自变化 |
| D | 度量对象被 9 文件交叉引用,核心是构造期冻结的全局快照(不看 style) | 快照时机决定生死,缓存永不自愈 |

**根因重述**(本轮定稿):管线没有「字体」这个概念。组合期/布局期/绘制期/分流期
各自决定"用什么字体、多大",靠隐式约定对齐;而历史上的「像素模式 vs stb 模式」
双模式体系进一步把"默认字体是谁"伪装成了全局状态,催生哨兵映射与双层门控。
所有已知 bug 均属此类。

## §2 目标不变量(重构完成判据)

- **I1 字体解析单点**:`style/font → ResolvedFont` 只发生在 `FontResolver.resolve()`。
- **I2 度量恒等于渲染**:`ResolvedFont.advance(cp)` ≡ 该字符最终渲染推进(px);
  逐码点精确,比例字体各测各宽。
- **I3 尺寸空间唯一**:布局输出即最终像素(emPx);无 ratio 折算、无第二空间;
  scale 仅存在于位图通道 pose(一处定义)。
- **I4 基线唯一**:`baselineFromTopPx` 来自 ResolvedFont 自然度量;原版 7px 锚点差
  由位图提交助手内部一次性补偿,对外不可见,常量仅此一处。
- **I5 默认字号随字体**:`defaultSizeSp` 定义于各 PlatformFont;全系统无第二处
  默认字号来源(BasicText 各重载、输入框、autoSize 全部经解析取得)。
- **I6 回退全覆盖**:任意码点沿 fallback 链必达唯一终端渲染器。
- **I7 公共 API 兼容**:LocalDefaultFont / LocalDefaultTextStyle / LocalDefaultFontSize /
  BasicText / BasicTextField / rememberCustomFont 等签名与语义保持;业务 Provider
  覆盖能力不变。

## §3 目标架构

```kotlin
// render/text/FontResolver.kt(新;render/text/ 下)

/** 渲染通道:字体的唯一本质差异 */
enum class FontChannel { STB_VECTOR, MC_FREETYPE, MC_BITMAP }

/** 逐码点度量(emPx 绝对值;hmtx/widthProvider 同源,比例字体精确逐字符) */
interface RunMetrics {
    fun advance(codepoint: Int): Float
    fun kern(prev: Int, next: Int): Float   // 默认 0
}

/** 所有字体的唯一抽象 —— 平权,无特例 */
interface PlatformFont {
    val id: FontDescription
    val channel: FontChannel
    val providerEmPx: Float        // 光栅网格 em:像素=12(json size)/位图=9/stb=加载 em
    val defaultSizeSp: Float       // 未显式指定字号时的默认值(字体自己的属性)
    fun covers(codepoint: Int): Boolean
    val fallbackId: FontDescription?   // null = 本字体即终端(MC_BITMAP 终端字体)
    fun metricsAt(emPx: Float): RunMetrics
    val lineHeightAt: (emPx: Float) -> Float   // 自然行盒 × em 比
    val baselineFromTopAt: (emPx: Float) -> Float
}

/** 解析结果:一次解析,全程携带 */
class ResolvedFont(
    val font: PlatformFont,
    val emPx: Float,               // = sp × density × fontScale(A4)
    val metrics: RunMetrics,
    val lineHeightPx: Float,
    val baselineFromTopPx: Float,
) {
    /** 该码点的最终归属(沿 fallback 链,I6;终局必达) */
    fun ownerOf(codepoint: Int): ResolvedFont
}

object FontRegistry {
    fun register(font: PlatformFont)
    operator fun get(id: FontDescription?): PlatformFont?   // null → 不查默认,见 Resolver
}

object FontResolver {
    var defaultFontId: FontDescription          // 唯一配置点(A8,当前 = fusion_pixel)
    fun resolve(id: FontDescription?, emPx: Float): ResolvedFont
    fun resolveDefault(emPx: Float): ResolvedFont
}
```

内置字体实现(全部只是注册表条目,无一特判):

| 实现 | 通道 | providerEm | defaultSizeSp | 度量来源 | fallbackId |
|---|---|---|---|---|---|
| FusionPixelFont(proportional/mono 两 id) | MC_FREETYPE | 12 | 24 | 同文件 stb 解析(hmtx 已证与 FreeType 全等) | minecraft:default |
| DefaultFontChain(minecraft:default) | enabled 且就绪 ? STB_VECTOR : MC_BITMAP | 加载 em / 9 | 18 | stb 链混合度量 / splitter | unifont(位图终端) |
| VanillaBitmapFont(alt/unifont/illageralt/missing 及其它资源字体) | MC_BITMAP | 9 | 18 | splitter widthProvider | null(自身即终端)* |
| CustomFont(rememberCustomFont 注册的外部 ttf/otf/ttc) | MC_FREETYPE | 注册时指定(未指定 = 1:1 sp 值) | 同上 | 同文件 stb 解析 | minecraft:default |

\* unifont 终端语义:MC 位图字体集自带 missing 方框兜底,视为全覆盖。

要点:

- **stb 总闸(TextRenderConfig.enabled)**收编为 DefaultFontChain 自身的通道策略,
  不再是全局模式;关闭时 default 链退化为位图通道,行为与旧「关闭」一致。
- **缺字回退行为微差**(登记于 §6):stb 就绪时,像素字体缺字段将由 default 链
  (stb 矢量)渲染而非今天的强制位图;机制统一后的自然结果,验收矩阵对照。
- **位图通道提交助手**(唯一一处):FreeType/位图字形由 pose 承载 em 比
  (`k = emPx / providerEmPx`),基线锚点补偿(`−7×k` 网格系)在其内部完成;
  今天 2 处定义 3 处使用的补偿逻辑全部删除。
- **切段器**(唯一一处):按 `ownerOf(cp)` 把 run 切成连续同归属段;
  stb 内联原版段、像素缺字段、渐变逐字采样三层共用同一切段器。

## §4 消灭清单(重构完成的硬判据 —— 以下符号/模式必须不存在)

- [ ] `activeMetricsSource()` 全局快照及其 5 个消费点(改为按 style 解析)
- [ ] `usePixelDefaultFont` 双模式开关及一切由此派生的分支
- [ ] `resolveDefaultFont()` 哨兵映射(fusion_pixel↔default 互换)
- [ ] `fontScaleBasePx` 与 `effectiveDefaultFontSizeSp` 全局基准(被 A4/A6 取代)
- [ ] `toTextScale` / toPlatformData scale / autoSize×2 / 段级 ratio 五套公式
      (合一为 `emPx = sp × density × fontScale`)
- [ ] `charScales / ratioAt / fallbackRatio` 相对系数体系(段级 = 每段独立 emPx)
- [ ] `0.8f × 行盒`、`7.2f`、`7f` 三种基线约定(binding 内部一份锚点补偿)
- [ ] `/9f` 装饰厚度写死 ×3(改行高比例常量一处)
- [ ] `supports()` 白名单与 `goingVanilla` 混合三条件(读 channel 一处 switch)
- [ ] `pixelFontCovers` 及其私有缓存(ResolvedFont.covers 承担)
- [ ] 两处手写的分段状态机(共享切段器)
- [ ] `BasicText(component)` 重载的 `18.sp` 硬编码默认字号
- [ ] `MC_DEFAULT_FONT_SIZE_SP` 顶层属性(委托语义并入解析)

## §5 迁移阶段(master 开工;P2 切独立分支;每阶段独立提交+验收)

**P1 字体注册表与解析单点**(行为等价迁移):
新增 PlatformFont/FontRegistry/FontResolver/ResolvedFont;四个内置字体实现入册;
MinecraftParagraphIntrinsics 度量快照、GuiStateBackend 三处、TrueTypeTextWriter
门控改走 resolve(style)(此阶段两套度量数值相同,纯结构替换)。
验收:三 dev 场景零回归;grep 无 activeMetricsSource 残留消费点。

**P2 尺寸空间统一**(最大手术,独立分支):
MultiParagraphIntrinsics 去 `scale` 参数;charScales 体系删除,段级 = 每段独立
ResolvedFont(emPx);绘制端去 save/scale 包裹;位图提交助手落地(基线补偿单点);
getLineBaseline 等启发式改 binding.baselineFromTopPx;DrawTextCommand 携带
ResolvedFont 引用(A3)。
验收:mono/proportional/default 三种字段混排一屏 wrap/光标/选区全对齐;autoSize
二分结果与手测一致;Component 重载默认字号随字体。

**P3 输入框与盖章收敛**:
state 版/legacy CoreTextField 共享同一 measure/paint 内核路径的字体解析;
两处盖章改 `resolveDefault()` 单点;CustomFont 以 PlatformFont 实现接入
(历史度量失配顺带消失,§6 登记观察)。
验收:两路径同文本逐像素一致;自定义字体宽度与渲染吻合。

**P4 收尾**:
§4 清单逐项核对;debugTextBounds 默认 false;手工验证矩阵(§7)全过;
AGENTS.md 文本章节同步(经用户批准)。

## §6 行为微差登记(预期内,非 bug;交付前逐项人工确认)

1. 基线数学从启发式(0.8×行盒 / 7.2f)切到真实字体基线:光标/选区纵向位置
   可能有 ≤1px 级移动(像素字体下 13/16≈0.8125 vs 0.8)。
2. 缺字回退段在 stb 就绪时改由 stb 矢量渲染(今天强制位图);字符显示正确,
   笔画风格随通道。
3. 像素字体 fontSize 非 12 整数倍时轻微模糊 = 已知边界,文档化,非 bug。
4. CustomFont 度量失配修复后文本宽度可能与旧版不同(变准了)。
5. BasicText(component) 默认字号从 18.sp 变为当前字体 defaultSizeSp(像素模式
   24sp)—— 用户拍板的统一行为。

## §7 验证矩阵(每阶段交付标准)

| 场景 | 预期 |
|---|---|
| TextInputDevScene 三输入框(mono/proportional/filter) | 字形/光标/点击对齐;mono 等宽推进恒定 |
| FontFallbackDevScene | 希伯来文真实字形;阿拉伯文/泰文方框且对照组同为方框 |
| TrueType 对照屏 | 进出后默认字体恢复;stb/像素两态切换正常 |
| dev 菜单正文/粗体标题 | 默认字体字形;粗体生效;宽度贴合行盒 |
| 富文本混排(AnnotatedString / Component / 占位符) | 段样式/段级字号/占位符原子行为不回归 |
| autoSize 文本 | 二分结果稳定,与固定字号视觉一致 |
| Ellipsis/maxLines/softWrap | 截断与省略号行为不变 |
| 原版 tooltip/HUD/F3 | 零波及 |
