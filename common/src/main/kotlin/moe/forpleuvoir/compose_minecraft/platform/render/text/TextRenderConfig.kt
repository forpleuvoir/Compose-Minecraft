package moe.forpleuvoir.compose_minecraft.platform.render.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.forpleuvoir.compose_minecraft.mc

/**
 * 文本渲染后端定向选择(设计文档 §3.1.1)。
 *
 * - [DEFAULT]:由字体解析结果的通道决定分流(A2);
 * - [VANILLA]:强制原版 MC 位图字体渲染(定向回退,组合期经
 *   `LocalTextRenderBackend` 覆盖 —— 该 CompositionLocal 属)。
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
 * - [weight]/[italic]:多字重族描述(样式完备阶段消费, 仅记录)。
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
 * 自研 TrueType 文本渲染管线全局配置(设计文档 §3.4)。
 *
 * - [fontSources] 按优先级排序,全部失败 → 回退原版(D4);
 * - STB 矢量链是否就绪由 TrueTypeFontManager 的加载结果决定,无独立总开关
 *   (历史 KDoc 的 `[enabled]` 开关从未落地,font-system 重构  清理)。
 *
 * 配置在运行时可变;[fontSources] 变化对**新建布局**生效
 * (MinecraftTextLayout 构造时快照度量来源),已建场景需重建后切换。
 */
object TextRenderConfig {

    /**
     * 内置 Fusion Pixel 的设计网格 em(sp):advance/行高按原生网格计算,
     * 与 FreeType 渲染端(provider size 同值)逐像素一致。仅该字体的
     * providerEm/defaultSize 由此派生(A6)—— 不再承担全局基准职责。
     */
    var pixelFontEmSp: Float by mutableStateOf(12f)

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
     * 字形图集活跃页水位(页粒度 LRU):超过即在本帧渲染前把「最久未使用」
     * 的页整页退役(cache 条目同步失效),帧末重置该页打包游标供后续复用 ——
     * §k 混淆等持续产生新字形的长驻场景显存不再无界增长。
     * 单页 = 1024×1024 R8 = 1 MB;默认 8 页(8 MB)。工作集真超水位时按 LRU
     * 换页(被淘汰字形下次使用重新光栅化),调大可减少换页抖动。
     */
    var atlasMaxPages: Int by mutableStateOf(8)
}
