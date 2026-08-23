# 文本度量与渲染系统 · 重构设计(全系统版)

> 本文档取代 `pixel-font-refactor-plan.md` 的局部视角,成为文本系统的总设计。
> 事实底账见 `text-pipeline-census.md`(447 条普查数据)。
> 执行前先读 §0 铁律;AGENTS.md 全部约定继续生效。

---

## §0 绝对注意事项(在 pixel-font-plan §0 十一条之上追加)

12. **度量与渲染必须同一字体实例**:任何文本 run 的布局宽度只能来自该 run 最终
    渲染所用字体的 advance。禁止全局单例度量、禁止构造期冻结快照量所有字体。
13. **尺寸空间唯一**:布局空间 = 最终像素空间;renderScale 只允许一个定义点。
    禁止"外层折算 + 内部再折算"并存(双重折算曾致字段宽度减半,实测)。
14. **段级字号 = 每段独立 emPx**,禁止 ratio 相对系数体系(charScales/ratioAt/
    fallbackRatio 整套退役)。
15. 新增任何常量前先对照 §7 清单,确认不是已有概念的重复定义。
16. 涉及文本几何的改动,必须在 TextInputDevScene(三输入框)、FontFallbackDevScene、
    TrueType 对照屏三处同时人工回归,缺一不交付。

---

## §1 现状审计结论(census 数据)

四类结构性问题(细节见 census):

| 类 | 问题 | 实证 |
|---|---|---|
| A | **缩放公式 5 处独立实现**:toPlatformData / toTextScale / autoSize×2 / 段级 ratio | 同一公式四种写法;基准切换需同步四处,P3 迁移即漏过 autoSize |
| B | **行高/基线三种约定并存**:7.2f 启发式、7f 锚点补偿、0.8×行盒再启发式 + 像素自然行盒 13px | 光标/选区用一套、绘制用另一套 |
| C | **模式/字体分支 ≥20 处**:盖章×2入口、supports、分段 swap、度量双层门控、resolver 哨兵映射 | usePixelDefaultFont 一动,四处行为各自变化 |
| D | **度量对象被 9 文件交叉引用**,核心是构造期冻结快照 | 快照时机决定生死,缓存永不自愈 |

根因:**管线没有"字体解析"概念**。组合期/布局期/绘制期/分流期四个阶段各自决定
"用什么字体、多大",靠隐式约定对齐;任何新变量(新字体、新字号默认值、新模式)
都会打破对齐——本轮所有 bug 均属此类,无一例外。

## §2 目标不变量(重构完成判据)

- **I1 字体解析单点**:`style → ResolvedFont` 只发生在 `FontResolver.resolve(style)`。
- **I2 度量恒等于渲染**:`ResolvedFont.advance(cp)` ≡ 该字符最终渲染推进(px)。
- **I3 尺寸空间唯一**:布局一律在「最终像素空间」计算(emPx);段内无第二空间、
  无 ratio 折算;绘制 1:1 落 canvas(scale 仅存在于位图通道的 pose,一处)。
- **I4 基线唯一**:`baselineFromTop` 来自 ResolvedFont 自然度量;原版锚点差由
  FreeType 提交路径内部一次性补偿,对外 API 不可见。
- **I5 默认字号唯一**:`effectiveDefaultFontSizeSp` 只定义于 TextRenderConfig;
  toPlatformData/toTextScale/autoSize 共用它,不再有第二套默认值逻辑。

## §3 目标架构

```kotlin
// FontResolver.kt(render/text/)
enum class FontKind { PIXEL_FREE_TYPE, SYSTEM_STB, VANILLA_BITMAP }

class ResolvedFont(
    val kind: FontKind,
    val emPx: Float,            // 本 run 的最终像素 em(fontSize × density × fontScale)
    val providerEmPx: Float,    // 位图光栅 em(pixel=网格12;system=9;named=json size)
    val metrics: RunMetrics,    // advance/kern —— 以 emPx 表达
    val lineHeightPx: Float,    // 自然行盒 ×(emPx/providerEm)
    val baselineFromTop: Float,
    val covers: (Int) -> Boolean,
    val fallback: FontDescription? = FontDescription.DEFAULT, // 像素族 → default
)

object FontResolver {
    fun resolve(style: Style): ResolvedFont          // 唯一决策点
    fun defaultBinding(): ResolvedFont               // 盖章语义(两输入框共用)
}
```

RunMetrics 实现:
- 像素族:`stb advance(units) × emPx/upm` —— 与 FreeType 渲染端逐字符一致
  (已证 hmtx 全等 + FT 精确整除);
- 系统 stb 链:`units × emPx/9em基准`;
- vanilla 位图:`mc.font.splitter.stringWidth`(9-grid)。

解析规则:

| style.fontOriginal | kind | providerEmPx | 缺字回退 |
|---|---|---|---|
| fusion_pixel / _mono | PIXEL_FREE_TYPE | pixelFontEmSp(网格12) | 强制 DEFAULT 段 |
| null / DEFAULT | enabled ? SYSTEM_STB : VANILLA_BITMAP | baseFontSizePx(9) | — |
| 其他资源字体 | VANILLA_BITMAP | — | — |

## §4 消灭清单(重构完成的硬判据 —— 以下概念必须不存在)

- [ ] `MC_DEFAULT_FONT_SIZE_SP` 的模式分支语义(仅保留单一委托)
- [ ] `toTextScale` 与 `toPlatformData` 两套 scale 公式(合一后只留一处实现)
- [ ] `charScales / ratioAt / fallbackRatio` 相对系数体系
- [ ] `0.8f × 行盒` 与 `7.2f` 基线启发式
- [ ] `/9f` 装饰厚度写死(改 metrics.lineHeight 比例常量化)
- [ ] supports() 的字体白名单 if(font 描述进 binding.renderer 决策)
- [ ] 两处重复的 VANILLA 锚点补偿常量(binding 内部一份)
- [ ] PixelFont 类加载器读取残留

## §5 迁移阶段(每阶段独立提交+验收)

**P0 基线**:commit 当前工作区(WIP 标注)。验收:git 干净。

**P1 度量按字体解析**(小步,行为不变):
PixelFont 泛化为双字体注册表(proportional+mono 已就位)+ `metricsFor(id)`;
新增 `FontResolver.resolve(style)`;MinecraftParagraphIntrinsics:129 改为
`metricsSourceFor(style)`;GuiStateBackend 三处同步换 `metricsSourceFor(cmd.style)`。
验收:三输入框 + BasicText 渲染与宽度不回归(此阶段两表数值相同,纯结构迁移)。

**P2 尺寸空间统一**(最大手术,单独分支):
- MultiParagraphIntrinsics 接口:`scale: Float` 参数退役,改为
  `ResolvedFont` 注入;MinecraftTextLayout 直接输出 emPx 空间尺寸;
- charScales/ratioAt/fallbackRatio 体系删除,段级字号 = 每段独立 emPx;
- toPlatformData/toTextScale 合一为单一函数;
- getLineBaseline 等 0.8f 启发式改 binding.baselineFromTop。
验收:mono/proportional/default 三种字段混排一屏,wrap/光标/选区全部对齐;
autoSize 二分结果与手测一致。

**P3 输入框路径收敛**:
state 重载与 legacy CoreTextField 共享同一 measure/paint 内核;
盖章统一为 `FontResolver.defaultBinding()` 单点。
验收:两路径同文本渲染逐像素一致。

**P4 收尾**:§4 消灭清单核对;debugTextBounds 默认 false;手工验证矩阵全过。

## §6 风险与边界

- P2/P3 触及 MultiParagraphIntrinsics 公共接口,建议独立分支;
- 像素字体锐利条件:fontSize(sp→px 后)须为网格(12)整数倍 —— 文档化,
  非整数倍时的表现(轻微模糊)属已知边界而非 bug;
- 自定义注册字体(MinecraftCustomFonts)当前度量走原版 splitter 的历史失配
  不在本轮范围,登记为后续项。

## §7 验证矩阵

同 pixel-font-refactor-plan §5,追加:
| mono 输入框 | 所有字符等宽推进;光标步进恒定 |
| autoSize 文本 | 缩放二分结果稳定,与固定字号视觉一致 |
