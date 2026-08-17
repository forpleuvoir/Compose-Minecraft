package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 颜色滤镜测试屏幕(T.21):验证 GraphicsLayer/Paint 级 colorFilter 的 draw 级近似。
 *
 * 每块 = 标签 + 原始 8 色条带(对照)+ 滤镜后条带。
 * 覆盖:ColorMatrix(反相/灰度)、BlendModeColorFilter(tint SrcIn/SrcOver/Modulate)、
 * LightingColorFilter(multiply+add)、graphicsLayer 级 colorFilter(图层整体)。
 * 另附纯色几何(圆/圆角矩形)走三角化路径的滤镜验证。
 *
 * 已知限制:blendMode(Paint/图层级)仅 SrcOver 生效 —— MC 26.2 pipeline
 * blend 编译期固定,draw 级无法逐命令切换,见 docs §1.6/§11.3。
 */
private val STRIP_COLORS = listOf(
    Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF),
    Color(0xFFFFFF00), Color(0xFF00FFFF), Color(0xFFFF00FF),
    Color(0xFFFFFFFF), Color(0xFF808080),
)

/** 4x5 颜色矩阵按**行主序**书写(每行 5 个:[a,b,c,d,e],第 5 列是 0..255 偏移) */
private val INVERT_MATRIX = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
)

private val GRAYSCALE_MATRIX = ColorMatrix(
    floatArrayOf(
        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
)

/** 8 色条带(每块对照源,48dp 高) */
private fun DrawScope.colorStrip(y: Float) {
    val stripWidth = 24f
    var x = 0f
    for (color in STRIP_COLORS) {
        drawRect(color, topLeft = Offset(x, y), size = Size(stripWidth, 48f))
        x += stripWidth
    }
}

/** 带滤镜的 8 色条带 */
private fun DrawScope.colorStripFiltered(y: Float, filter: ColorFilter?) {
    val stripWidth = 24f
    var x = 0f
    for (color in STRIP_COLORS) {
        drawRect(color, topLeft = Offset(x, y), size = Size(stripWidth, 48f), colorFilter = filter)
        x += stripWidth
    }
}

@Composable
private fun FilterBlock(
    title: String,
    note: String,
    filter: ColorFilter?,
    extra: (DrawScope.() -> Unit)? = null,
) {
    BasicText(title, style = Style.EMPTY.withColor(Color(0xFF1A1A1A)).withBold(true))
    if (note.isNotEmpty()) {
        BasicText(note, style = Style.EMPTY.withColor(Color(0xFF546E7A)))
    }
    Canvas(
        Modifier
            .padding(top = 2.dp)
            .height(100.dp)
            .fillMaxWidth()
    ) {
        colorStrip(0f)
        colorStripFiltered(52f, filter)
        extra?.invoke(this)
    }
    BasicText("", style = Style.EMPTY.withColor(Color(0xFF90A4AE)))
}

@Composable
fun ColorFilterDevScene() {
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current

    val invertFilter = remember { ColorFilter.colorMatrix(INVERT_MATRIX) }
    val grayFilter = remember { ColorFilter.colorMatrix(GRAYSCALE_MATRIX) }
    val tintSrcIn = remember { ColorFilter.tint(Color(0xFFFF0000), BlendMode.SrcIn) }
    val tintSrcOver = remember { ColorFilter.tint(Color(0x80FF0000), BlendMode.SrcOver) }
    val tintModulate = remember { ColorFilter.tint(Color(0xFFFF0000), BlendMode.Modulate) }
    val lightingFilter = remember { ColorFilter.lighting(Color(0xFFFF0000), Color(0xFF00FF00)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            BasicText(
                "颜色滤镜测试 (ColorFilter, T.21)",
                style = Style.EMPTY.withColor(Color(0xFF1A1A1A)).withBold(true),
            )
            BasicText(
                "每块:上行原始 8 色条带(红/绿/蓝/黄/青/品红/白/灰),下行滤镜后。\n" +
                    "blendMode 仅 SrcOver 生效(draw 级限制,见 docs §1.6)。",
                style = Style.EMPTY.withColor(Color(0xFF546E7A)),
            )

            // ① 对照(无滤镜)
            FilterBlock("① 对照(无滤镜)", "原始条带,颜色应原样", null)

            // ② 颜色矩阵:反相
            FilterBlock(
                "② ColorMatrix 反相", "下行应为上行的补色(黑↔白、红↔青、绿↔品红、蓝↔黄)", invertFilter,
            )

            // ③ 颜色矩阵:灰度
            FilterBlock("③ ColorMatrix 灰度", "下行应为灰度(亮度 0.2126/0.7152/0.0722)", grayFilter)

            // ④ tint SrcIn(不透明红)
            FilterBlock("④ tint 红 SrcIn", "下行应全为红色(alpha 保留,颜色替换)", tintSrcIn)

            // ⑤ tint SrcOver(50% 红)
            FilterBlock(
                "⑤ tint 红 SrcOver", "下行 = 源色与 50% 红各半混合(白→粉、红→红、蓝→紫)", tintSrcOver,
            )

            // ⑥ tint Modulate(红)
            FilterBlock(
                "⑥ tint 红 Modulate", "下行 = 源色 × 红(绿/蓝通道归零:只留红与亮度分量)", tintModulate,
            )

            // ⑦ lighting(multiply=红, add=绿)
            FilterBlock(
                "⑦ lighting(multiply=红, add=绿)", "下行 = 源×红 + 绿(G 恒满,R=源R,B=0 → 黄调)",
                lightingFilter,
            )

            // ⑧ graphicsLayer 级 colorFilter(图层整体反相)
            BasicText(
                "⑧ graphicsLayer 级反相(图层整体)",
                style = Style.EMPTY.withColor(Color(0xFF1A1A1A)).withBold(true),
            )
            BasicText(
                "图层内容(条带+圆)整体反相;同滤镜在 2D 回放(replayFrom)路径生效",
                style = Style.EMPTY.withColor(Color(0xFF546E7A)),
            )
            Canvas(
                Modifier
                    .padding(top = 2.dp)
                    .size(192.dp)
                    .background(Color(0xFFECEFF1))
                    .drawWithContent {
                        layer.record(density, layoutDirection, IntSize(192, 100)) {
                            colorStrip(0f)
                            drawCircle(Color(0xFF00ACC1), radius = 20f, center = Offset(160f, 74f))
                        }
                        layer.colorFilter = invertFilter
                        drawLayer(layer)
                    }
            ) {
                // onDraw 空:内容由 drawWithContent 提交(record + drawLayer)
            }

            // ⑨ 三角化路径(圆/圆角矩形)带滤镜
            BasicText(
                "⑨ 三角化几何(圆/圆角矩形)带滤镜",
                style = Style.EMPTY.withColor(Color(0xFF1A1A1A)).withBold(true),
            )
            BasicText(
                "左边 tint 红 SrcIn(应全红),右边反相(应补色);走 addTriangles 路径",
                style = Style.EMPTY.withColor(Color(0xFF546E7A)),
            )
            Canvas(
                Modifier
                    .padding(top = 2.dp)
                    .height(80.dp)
                    .fillMaxWidth()
            ) {
                drawRoundRect(
                    Color(0xFF43A047), topLeft = Offset(8f, 8f), size = Size(60f, 60f),
                    cornerRadius = CornerRadius(10f),
                    colorFilter = tintSrcIn,
                )
                drawCircle(
                    Color(0xFF1E88E5), radius = 26f, center = Offset(108f, 38f),
                    colorFilter = invertFilter,
                )
                drawRoundRect(
                    Color(0xFFFB8C00), topLeft = Offset(168f, 8f), size = Size(60f, 60f),
                    cornerRadius = CornerRadius(10f),
                    style = Stroke(4f),
                    colorFilter = grayFilter,
                )
            }
        }
    }
}
