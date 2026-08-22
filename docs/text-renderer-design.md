# 文本渲染器替换 —— 设计文档(TrueType 渲染管线)

> 状态:**P1 已实施(2026-08-23),默认关;P2/P3 待做**
> 目标:用自研 TrueType 文本渲染管线替换 Compose 文本的默认渲染路径(原版 MC 位图字体管线)。
> 参考:[Modern UI](https://github.com/BloCamLimb/ModernUI-MC)(成熟先例)。
>
> **授权边界**:Modern UI 为 LGPL-3.0,本项目为 Apache-2.0 —— **仅参考其公开文档与架构思路
> (按需光栅化 + 图集 + 自定义管线是业界通用做法),禁止移植任何源码**。实现全部基于
> LWJGL `STBTruetype` 绑定(stb 为公共领域/MIT 双许可,LWJGL 为 BSD)与自研代码。
> 字体文件授权另行注意:内置字体候选需为可再分发许可(OFL 等),打包时附带对应字体许可证。

---

## 0. 决策记录(用户已拍板)

| # | 决策 | 内容 |
|---|---|---|
| D1 | 字体源 | **内置开源字体**(思源黑体 / Noto Sans CJK 等候选),**具体字体开放设计**——加载器按配置取字体文件,后续再选定 |
| D2 | 光栅化库 | **LWJGL stb_truetype**(`org.lwjgl.stb.STBTruetype`,MC 运行时自带,零新依赖) |
| D3 | 替换范围 | **仅 Compose 文本渲染**;原版 GUI / 聊天 / F3 等不受影响 |
| D4 | 失败回退 | 字体加载失败 / 图集不可用时,**回退原版渲染器**(`sink.addText` 共存) |

## 1. 背景与动机

1. MC 字形为 8×8 位图,字号靠矩阵放大 —— 非整数缩放模糊,段级字号的 1.125x 等缩放观感差;
2. FreeType/TTF 自定义字体(MinecraftCustomFonts)仍被塞进 MC FontManager 管线,受 oversample 机制限制;
3. 描边、渐变字等效果在原版管线上无法实现。

## 2. 现状链路与替换边界

```
recordTextDraw(text,x,y,style,alpha) → DrawTextCommand
  → CommandDispatcher → GeometryBackend.drawText      ← 【唯一拦截点】
    → GuiStateBackend: sink.addText(GuiTextRenderState) ← 原版入口(回退路径保留)
```

- 排版/布局层(`MinecraftTextLayout`/`MinecraftParagraph`)**已是平台自有**,结构不变;
- CPU 快照后端(`RasterBackend.drawText` 目前跳过文本)阶段 3 补齐。

## 3. 目标架构

新增包 `platform/render/text/`:

```
TrueTypeFont          # stb_truetype 封装:解析 TTF/TTC、字形度量、光栅化灰度位图
FontFaceManager       # 字体加载与选择(按配置的字体文件列表;D1 开放设计)
GlyphAtlas            # 纹理图集分页管理(R8 单通道),LRU 淘汰,UV 记录
GlyphKey/GlyphCache   # (codepoint, sizePx, 合成样式) → 字形槽位
GuiGlyphRenderState   # GuiElementRenderState 子类:文本 quad 批次 + 图集 TextureSetup
gui_text RenderPipeline # 自定义管线:R8 图集采样 × 顶点色 tint(参照 T.14 gui_shadow 先例)
```

### 3.1 拦截与分流

```kotlin
// GuiStateBackend.drawText
when (cmd.backend) {
    VANILLA        -> sink.addText(text(cmd, scissor))   // 手动回退原版
    DEFAULT / null -> if (TextRenderConfig.enabled && TrueTypeFontManager.isReady)
                        sink.addElement(GuiGlyphRenderState(...))  // 自有管线
                      else sink.addText(text(cmd, scissor))      // 全局关闭或字体不可用(D4)
}
```

- 未覆盖字形(如 emoji):逐 run 回退 `addText`(与原版渲染共存于同一帧);
- 全局开关:`TextRenderConfig.enabled`(默认 false,验证稳定后再翻默认)。

### 3.1.1 手动指定渲染器(LocalTextRenderBackend)

组合期可覆盖的**定向回退**(仅用于把某个子树切回原版渲染;启用与否由全局开关统一管):

```kotlin
enum class TextRenderBackend { DEFAULT, VANILLA }

val LocalTextRenderBackend = staticCompositionLocalOf { TextRenderBackend.DEFAULT }
```

- **读取时机**:指针/绘制阶段读不到 CompositionLocal —— 由文本组件在**组合期**读取
  (`BasicText`/`BasicTextField` 内随 scale 一起捕获),传递到绘制命令
  (`DrawTextCommand.backend` 新字段,记录时盖章);
- **解析**:仅 `VANILLA` 生效(强制原版);`DEFAULT` 跟随全局开关;无"强制启用"档——
  启用是平台级决策,不暴露给子树(避免与全局开关职责重复)。

### 3.2 度量同源(**硬骨头**)

字形与宽度必须同源,否则错位。改造点:

- 引入度量来源抽象:`MetricsSource`(`charAdvance(cp)/ascent/descent/lineHeight`);
- 原:`font.splitter.stringWidth`(MC 位图度量);
- 新:`stbtt_GetCodepointHMetrics` × 当前字号;
- `MinecraftTextLayout.cumFloatWidths` 的增量数组结构不变,数据源切换;行高/基线公式同步换 stb ascent/descent;
- 段级字号 r 系数、占位符原子、Ellipsis 全部兼容(只换每字符增量的数值来源)。

### 3.3 样式映射

| Compose 语义 | 实现 |
|---|---|
| fontSize(段级) | 光栅化字号 = basePx × r(图集按 size 缓存;非整数 size 直接支持——矢量光栅化红利) |
| bold | 有 Bold 字重字体则用之;否则 stb 合成加粗(embolden) |
| italic | 倾斜矩阵(skew)叠加 |
| color/alpha | 顶点色 tint(shader 相乘) |
| MC Style obfuscated | 不支持,该 run 回退原版 |

### 3.4 字体配置(D1 开放设计)

```kotlin
data class FontSource(val path: String, val weight: Int = 400, val italic: Boolean = false)
object TextRenderConfig {
    var enabled: Boolean = false
    val fontSources: MutableList<FontSource>   // 资源路径或绝对文件路径,按优先级排序
}
```

内置候选(最终选哪个由你定):思源黑体 SC(Noto Sans CJK SC)、霞鹜文楷、OPPO Sans 等;
加载顺序 = 列表优先级,全部失败 → 回退原版(D4)。

## 4. 阶段计划

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P1 最小闭环** ✅ 已实施 | 原生排版(字体自身 HMetrics/kern/em 归一);stb 解析 + R8 图集 + gui_text 管线;粗体(偏移并集/真粗体文件)、斜体、装饰线、混淆 §k、渐变逐字形、彩色阴影全部管线内实现;缺字字符内联原版字形(混排);flag 默认关 | ✅ dev 对照验收通过 |
| P2 样式完备 | ⬜ `LocalTextRenderBackend` 组合期定向回退;⬜ 缺字回退链(多 FontSource 按码点兜底,当前仅粗体独立文件);⬜ 24sp 粗体清晰度微调(boldEmboldenRatio) | 混排场景各段正确缩放与基线对齐 |
| P3 性能与收尾 | ⬜ 批次合并、图集分页/LRU(混淆长开会持续占用图集)、RasterBackend CPU 路径、可选描边 | 静态场景零额外开销;动态文本流畅 |

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| stb 对部分 TTC/字重轴支持弱 | 字体选型时验证;必要时单字体拆分提供 |
| CJK 图集内存增长 | 分页 + LRU;常用区(GB2312 一级)预热可选 |
| 度量切换引发既有场景布局变化 | flag 关闭即完全回退;开启后以 dev 场景逐项对照 |
| 与原生组件(tooltip 等)字体不一致 | 属预期(仅替换 Compose 文本);视觉统一可后续给 tooltip 也开开关 |
