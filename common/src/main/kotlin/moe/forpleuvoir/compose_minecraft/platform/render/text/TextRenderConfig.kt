package moe.forpleuvoir.compose_minecraft.platform.render.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.forpleuvoir.compose_minecraft.mc

/**
 * 文本渲染后端定向选择(T.TT,设计文档 §3.1.1)。
 *
 * - [DEFAULT]:跟随全局开关([TextRenderConfig.enabled])分流;
 * - [VANILLA]:强制原版 MC 位图字体渲染(定向回退,组合期经
 *   `LocalTextRenderBackend` 覆盖 —— 该 CompositionLocal 属 P2)。
 *
 * 无"强制启用"档:启用是平台级决策,不暴露给子树(避免与全局开关职责重复)。
 */
enum class TextRenderBackend {
    DEFAULT,
    VANILLA,
}

/**
 * 字体源(D1 开放设计):加载器按列表优先级逐个尝试,第一个解析成功的生效。
 *
 * - [path] 语义:绝对/相对文件路径(P1);资源包路径后续按需扩展;
 * - [weight]/[italic]:多字重族描述(P2 样式完备阶段消费,P1 仅记录)。
 */
data class FontSource(
    val path: String,
    val weight: Int = 400,
    val italic: Boolean = false,
)

/**
 * 自研 TrueType 文本渲染管线全局配置(T.TT,设计文档 §3.4)。
 *
 * - [enabled] 默认 **false**(D3/D4:验证稳定后再翻默认;关闭时文本链路
 *   与现状逐字节一致 —— 度量走原版 splitter、绘制走 `sink.addText`);
 * - [fontSources] 按优先级排序,全部失败 → 回退原版(D4);
 *
 * 配置在运行时可变;[enabled]/[fontSources] 变化对**新建布局**生效
 * (MinecraftTextLayout 构造时快照度量来源),已建场景需重建后切换。
 */
object TextRenderConfig {

    /** 全局开关:开启且字体就绪时 Compose 文本走自研 TTF 管线(默认启用) */
    var enabled: Boolean by mutableStateOf(true)

    /** 常规体字体文件列表(D1 开放设计),按优先级排序 */
    val fontSources: MutableList<FontSource> = mutableListOf()

    /**
     * 粗体字重文件列表(可选,字体家族):非空且加载成功时,**粗体 run 直接
     * 使用真粗体字形**(不再做膨胀合成);为空/全部失败 → 退化合成粗体。
     * 加载器同样按优先级逐个尝试、跳过失效源。
     */
    val boldFontSources: MutableList<FontSource> = mutableListOf()

    /**
     * 字体排版行高基准(px)= 原版 Font.lineHeight,**动态跟随**(部分模组会
     * 修改该值)。关键语义:平台 sp 链路自带「÷行高 再放大」,此基准必须等于
     * 行高本身,否则重复叠加 —— 取 9 时 18sp 恰为 18px 行(1sp == 1px,
     * 与原版一致;实测反馈:写死 18 会叠加成 1sp=2px)。
     */
    val baseFontSizePx: Float get() = mc.font.lineHeight.toFloat()

    /**
     * **TTF 渲染器的默认字号(sp)**:矢量字形墨迹占格比例高于位图,
     * 同 sp 下视觉偏大 —— 默认 16sp 与原版位图的 18sp 观感对齐。
     * 仅在 [enabled] 时作为默认字号生效。
     */
    var ttfDefaultFontSizeSp: Float by mutableStateOf(16f)

    /**
     * 默认字号 = 原版 Font.lineHeight × 本倍数(默认 2,即平台惯例的 18sp)。
     * 与 [baseFontSizePx] 一样动态跟随行高;部分模组修改行高时自动适配。
     */
    @Volatile
    var defaultFontSizeLineMultiple: Float = 2f

    /**
     * 混淆(§k)字形重掷间隔(ms),默认 16(约每帧一次,对齐原版每帧重掷的观感;
     * 调大可降低闪烁与光栅化开销)。每次重掷按「字符序号 + 时间槽」确定性选样,
     * 同一槽位内稳定。最小值钳制为 1。
     */
    var obfuscatedUpdateIntervalMs: Long by mutableStateOf(16L)

    /**
     * 调试:为每个文本 run 绘制行盒轮廓(绿)+ 基线(红)。在分流层实现,
     * 两种渲染器画的是同一逻辑盒,用于对照尺寸/对齐。渲染期每帧读取,
     * 切换无需重组。
     */
    @Volatile
    var debugTextBounds: Boolean = false

    /**
     * 粗体膨胀强度(连续值,支持亚像素):**强度 = 光栅化设备字号 × [boldEmboldenRatio]**,
     * 钳制 0.5..2。默认 1/32 —— 18sp(32px 位图)时强度 1.0 = 每侧 +1px、
     * 笔画总宽 +2px,清晰加粗;调大更粗、调小更细。
     */
    var boldEmboldenRatio: Float by mutableStateOf(1f / 32f)
}
