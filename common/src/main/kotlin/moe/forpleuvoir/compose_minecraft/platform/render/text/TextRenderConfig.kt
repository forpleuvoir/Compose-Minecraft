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
 * - [path] 语义:文件系统路径;[resourcePath] 语义:模组内嵌 classpath 资源
 *   (如平台内置的 Fusion Pixel,`/assets/...` 起始),二者二选一;
 * - [designSizePx]:字体加载 em(px)。像素字体必填其设计网格(如 Fusion Pixel
 *   = 12)—— advance/行高按原生网格计算,保证与 FreeType 渲染端逐像素一致;
 *   null = 跟随全局基准([TextRenderConfig.baseFontSizePx]);
 * - [weight]/[italic]:多字重族描述(P2 样式完备阶段消费,P1 仅记录)。
 */
data class FontSource(
    val path: String,
    val weight: Int = 400,
    val italic: Boolean = false,
    /** classpath 内嵌资源路径(模组 JAR 内);设置后 [path] 被忽略 */
    val resourcePath: String? = null,
    /** 像素字体设计网格(em px);null = 跟随全局基准字号 */
    val designSizePx: Float? = null,
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

    /**
     * 全局开关:开启且字体就绪时 Compose 文本走自研 TTF 管线(默认启用)。
     *
     * ⚠️ P3 像素化语义修正:[usePixelDefaultFont] = true 时本开关对
     * **默认/fusion_pixel 的 run 不生效** —— 该类 run 必须经管线渲染才能与
     * 像素度量同源,否则行盒/宽度错位;enabled 仅在 stb 模式(false)下作为
     * 旧总闸使用。
     */
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
     * **像素默认字体的 em 基准(sp)**:平台全局默认字体已切换为内置
     * Fusion Pixel(compose_minecraft:fusion_pixel,设计网格 12px,见
     * `assets/compose_minecraft/font/fusion_pixel.json`)。
     *
     * - 1sp == 1px 不变;本值 = 字体设计网格,**默认字号取 12sp 时字形
     *   网格与屏幕像素 1:1(锐利)**,放大建议取 12 的整数倍;
     * - 字号→渲染缩放换算基准([moe.forpleuvoir.compose_minecraft.platform.ui.text.TextStyleMapper]
     *   的 scale = sp / 本值)随之从原版行高 9 切到 12;
     * - 度量链(TrueTypeFontManager 内置源)以同值作为字体加载 em。
     */
    var pixelFontEmSp: Float by mutableStateOf(12f)

    /**
     * 全局默认字号(sp)= 像素字体网格 ×2([pixelFontEmSp] × 2,默认 24sp)。
     * ×2 为整数倍缩放,像素字形仍逐像素锐利;12sp 原生尺寸实测过小(用户反馈,
     * 2026-08)。字号→渲染缩放换算(scale = sp / [pixelFontEmSp])下 24sp → 2x。
     */
    val pixelDefaultFontSizeSp: Float get() = pixelFontEmSp * 2f

    /**
     * 是否启用「像素默认字体」模式(P3,默认 true):
     *
     * - true:Compose 未显式指定字体的文本使用 compose_minecraft:fusion_pixel,
     *   经原版 FreeType 管线按设计网格渲染;布局度量取像素字体 em
     *   ([pixelFontEmSp]),缺字码点按码点退回原版字形;
     * - false:回退旧行为 —— 默认字体 minecraft:default,[enabled] 开启时由
     *   自研 stb 管线按系统字体链渲染(TrueType 文本对照测试屏使用此模式)。
     *
     * 注意:本开关只影响「默认文本走哪条渲染路径与度量」,不影响已显式指定
     * 字体的 run 与原版自身渲染。
     */
    var usePixelDefaultFont: Boolean by mutableStateOf(true)

    /**
     * 生效默认字号(sp)—— 双模式统一入口:
     * - 像素模式([usePixelDefaultFont])= [pixelDefaultFontSizeSp](24sp);
     * - stb 模式 = 原版行高 × [defaultFontSizeLineMultiple](18sp,历史语义)。
     * 所有「未显式指定字号」的消费点(BasicText/BasicTextField 等)必须经此取值,
     * 禁止各自硬编码(历史上 9f/16f/18f 三处写死导致模式间尺寸错乱)。
     */
    val effectiveDefaultFontSizeSp: Float
        get() = if (usePixelDefaultFont) pixelDefaultFontSizeSp
        else mc.font.lineHeight * defaultFontSizeLineMultiple

    /**
     * 字号→渲染缩放的基准 px(sp ÷ 本值 = scale)—— 双模式统一入口:
     * - 像素模式 = [pixelFontEmSp](12):24sp → 2x;
     * - stb 模式 = mc.font.lineHeight(9):18sp → 2x(历史语义)。
     */
    val fontScaleBasePx: Float
        get() = if (usePixelDefaultFont) pixelFontEmSp else mc.font.lineHeight.toFloat()

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
     * 调试:为每个文本 run 绘制行盒轮廓(绿)+ 基线(红);同时打印 `[TT]`
     * 路由日志(每个 run 的字体描述/粗体/分流)与 `[TT-FIELD]`(输入框解析字体)。
     * 排查「粗体/回退/混排走错渲染器」类问题的第一手观测开关。
     */
    @Volatile
    var debugTextBounds: Boolean = false

    /**
     * 粗体膨胀强度(连续值,支持亚像素):**强度 = 光栅化设备字号 × [boldEmboldenRatio]**,
     * 钳制 0.5..2。默认 1/32 —— 18sp(32px 位图)时强度 1.0 = 每侧 +1px、
     * 笔画总宽 +2px,清晰加粗;调大更粗、调小更细。
     */
    var boldEmboldenRatio: Float by mutableStateOf(1f / 32f)

    /**
     * 字形图集活跃页水位(P3① 页粒度 LRU):超过即在本帧渲染前把「最久未使用」
     * 的页整页退役(cache 条目同步失效),帧末重置该页打包游标供后续复用 ——
     * §k 混淆等持续产生新字形的长驻场景显存不再无界增长。
     * 单页 = 1024×1024 R8 = 1 MB;默认 8 页(8 MB)。工作集真超水位时按 LRU
     * 换页(被淘汰字形下次使用重新光栅化),调大可减少换页抖动。
     */
    var atlasMaxPages: Int by mutableStateOf(8)
}
