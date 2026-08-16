package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 图形(几何)绘制验证屏幕:验证 CPU 三角化回放到 GuiRenderState 的
 * 圆/椭圆/弧/圆角矩形/线/Path/点,填充与描边两种样式。
 *
 * 注意:平台 Paint 快照携带 strokeCap(Points 模式的 Round → 圆 / 其他 → 方形,
 * 线/折线端帽),但未携带 strokeJoin,描边 join 统一按圆 join 展开
 * (见 GeometryTessellator 类注释)。
 */
@Composable
fun GeometryDevScene() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            BasicText(
                "Geometry Draw Test(图形绘制)",
                style = Style.EMPTY.withColor(Color.White).withBold(true),
            )
            BasicText(
                "填充:圆/椭圆/大扇区(useCenter,披萨缺角)/弧/圆角矩形/Path",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)),
            )
            BasicText(
                "描边:圆/椭圆/弧/圆角矩形/线/Path;点:Points/Lines/Polygon",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)),
            )

            // ── 填充:同心大扇区(useCenter 扇形,像被拿走一块的披萨)──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                drawArc(
                    color = Color(0xFFE53935), startAngle = 45f, sweepAngle = 300f, useCenter = true,
                    topLeft = Offset(12f, 15f), size = Size(80f, 80f),
                )
                drawArc(
                    color = Color(0xFF1E88E5), startAngle = 45f, sweepAngle = 300f, useCenter = true,
                    topLeft = Offset(32f, 35f), size = Size(40f, 40f),
                )
                drawOval(color = Color(0xFF43A047), topLeft = Offset(110f, 20f), size = Size(120f, 70f))
                drawOval(color = Color(0xFFFB8C00), topLeft = Offset(240f, 30f), size = Size(90f, 50f))
                drawCircle(color = Color(0xFFF06292), radius = 5f, center = Offset(360f, 55f))
                drawOval(color = Color(0xFF9575CD), topLeft = Offset(380f, 50f), size = Size(60f, 10f))
            }

            // ── 填充:弧(useCenter 扇形 / 弓形)──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                drawArc(
                    color = Color(0xFF8E24AA), startAngle = 0f, sweepAngle = 120f, useCenter = true,
                    topLeft = Offset(10f, 10f), size = Size(90f, 90f),
                )
                drawArc(
                    color = Color(0xFF00ACC1), startAngle = 45f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(120f, 10f), size = Size(90f, 90f),
                )
                drawArc(
                    color = Color(0xFFD81B60), startAngle = -60f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(230f, 10f), size = Size(90f, 90f),
                )
                drawArc(
                    color = Color(0xFF7E57C2), startAngle = 30f, sweepAngle = 300f, useCenter = true,
                    topLeft = Offset(340f, 10f), size = Size(90f, 90f),
                )
                drawArc(
                    color = Color(0xFFFFCA28), startAngle = 0f, sweepAngle = 15f, useCenter = true,
                    topLeft = Offset(450f, 10f), size = Size(90f, 90f),
                )
            }

            // ── 填充:圆角矩形(真圆角)──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                drawRoundRect(
                    color = Color(0xFF5E35B1), topLeft = Offset(10f, 10f),
                    size = Size(130f, 90f), cornerRadius = CornerRadius(24f, 24f),
                )
                drawRoundRect(
                    color = Color(0xFF00897B), topLeft = Offset(160f, 15f),
                    size = Size(110f, 80f), cornerRadius = CornerRadius(40f, 12f),
                )
                drawRoundRect(
                    color = Color(0xFF78909C), topLeft = Offset(300f, 10f),
                    size = Size(90f, 90f), cornerRadius = CornerRadius(60f, 60f),
                )
                drawRoundRect(
                    color = Color(0xFF26C6DA), topLeft = Offset(410f, 25f),
                    size = Size(70f, 60f), cornerRadius = CornerRadius(1f, 1f),
                )
            }

            // ── 填充:Path(非凸多边形 + 二次/三次贝塞尔 + 闭合)──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                val star = Path().apply {
                    moveTo(60f, 10f)
                    lineTo(75f, 45f)
                    lineTo(112f, 45f)
                    lineTo(82f, 68f)
                    lineTo(92f, 105f)
                    lineTo(60f, 84f)
                    lineTo(28f, 105f)
                    lineTo(38f, 68f)
                    lineTo(8f, 45f)
                    lineTo(45f, 45f)
                    close()
                }
                drawPath(star, color = Color(0xFFFFB300))

                val blob = Path().apply {
                    moveTo(170f, 110f)
                    quadraticTo(140f, 30f, 200f, 20f)
                    cubicTo(260f, 10f, 250f, 80f, 300f, 60f)
                    quadraticTo(330f, 110f, 260f, 120f)
                    close()
                }
                drawPath(blob, color = Color(0xFF26A69A))

                val tri = Path().apply {
                    moveTo(350f, 110f)
                    lineTo(410f, 30f)
                    lineTo(460f, 110f)
                    close()
                }
                drawPath(tri, color = Color(0xFFEF5350))

                // 蝴蝶结(自交 Path):x 350..430(靠左,窄窗口也可见)
                val bowtie = Path().apply {
                    moveTo(350f, 40f)
                    lineTo(430f, 110f)
                    lineTo(430f, 40f)
                    lineTo(350f, 110f)
                    close()
                }
                drawPath(bowtie, color = Color(0xFFAB47BC))
            }

            // ── 描边:圆 / 椭圆 / 弧 / 圆角矩形 / 线 ──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(150.dp)
            ) {
                drawCircle(
                    color = Color(0xFFFF7043), radius = 40f, center = Offset(52f, 55f),
                    style = Stroke(width = 6f),
                )
                drawCircle(
                    color = Color(0xFF66BB6A), radius = 20f, center = Offset(52f, 55f),
                    style = Stroke(width = 3f),
                )
                drawOval(
                    color = Color(0xFF42A5F5), topLeft = Offset(110f, 15f), size = Size(110f, 80f),
                    style = Stroke(width = 5f),
                )
                drawArc(
                    color = Color(0xFFAB47BC), startAngle = 30f, sweepAngle = 200f, useCenter = false,
                    topLeft = Offset(240f, 15f), size = Size(80f, 80f),
                    style = Stroke(width = 7f),
                )
                drawRoundRect(
                    color = Color(0xFFEF5350), topLeft = Offset(10f, 110f),
                    size = Size(120f, 30f), cornerRadius = CornerRadius(12f, 12f),
                    style = Stroke(width = 4f),
                )
                drawLine(
                    color = Color(0xFF26C6DA), start = Offset(150f, 125f), end = Offset(300f, 125f),
                    strokeWidth = 6f,
                )
                drawCircle(
                    color = Color(0xFFFFF59D), radius = 25f, center = Offset(350f, 45f),
                    style = Stroke(width = 1f),
                )
                drawArc(
                    color = Color(0xFF4DB6AC), startAngle = -30f, sweepAngle = 120f, useCenter = true,
                    topLeft = Offset(395f, 15f), size = Size(70f, 70f),
                    style = Stroke(width = 5f),
                )
            }

            // ── 描边:Path(贝塞尔轮廓)──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                val wave = Path().apply {
                    moveTo(10f, 80f)
                    quadraticTo(50f, 10f, 100f, 80f)
                    quadraticTo(150f, 10f, 200f, 80f)
                    quadraticTo(250f, 10f, 300f, 80f)
                }
                drawPath(wave, color = Color(0xFFFFEE58), style = Stroke(width = 5f))

                val starStroke = Path().apply {
                    moveTo(330f, 90f)
                    lineTo(345f, 55f)
                    lineTo(375f, 55f)
                    lineTo(350f, 35f)
                    lineTo(360f, 5f)
                    lineTo(330f, 22f)
                    lineTo(300f, 5f)
                    lineTo(310f, 35f)
                    lineTo(285f, 55f)
                    lineTo(315f, 55f)
                    close()
                }
                drawPath(starStroke, color = Color(0xFF80DEEA), style = Stroke(width = 3f))

                val zigzag = Path().apply {
                    moveTo(400f, 95f)
                    lineTo(430f, 15f)
                    lineTo(455f, 95f)
                    lineTo(485f, 15f)
                }
                drawPath(zigzag, color = Color(0xFFFFAB91), style = Stroke(width = 4f, cap = StrokeCap.Round))
            }

            // ── 点:Points(Round/Butt) / Lines / Polygon(连续折线)──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(190.dp)
            ) {
                drawPoints(
                    pointMode = PointMode.Points,
                    points = listOf(
                        Offset(20f, 30f), Offset(60f, 30f), Offset(100f, 30f),
                        Offset(140f, 30f), Offset(180f, 30f),
                    ),
                    color = Color(0xFFE91E63), strokeWidth = 8f,
                    cap = StrokeCap.Round,
                )
                drawPoints(
                    pointMode = PointMode.Points,
                    points = listOf(
                        Offset(20f, 30f), Offset(60f, 30f), Offset(100f, 30f),
                        Offset(140f, 30f), Offset(180f, 30f),
                    ),
                    color = Color(0xFFF48FB1), strokeWidth = 4f,
                    cap = StrokeCap.Square,
                )
                drawPoints(
                    pointMode = PointMode.Lines,
                    points = listOf(
                        Offset(20f, 70f), Offset(120f, 70f),
                        Offset(160f, 70f), Offset(260f, 70f),
                        Offset(290f, 70f),
                    ),
                    color = Color(0xFF00E5FF), strokeWidth = 4f,
                )
                drawPoints(
                    pointMode = PointMode.Polygon,
                    points = listOf(
                        Offset(20f, 110f), Offset(120f, 110f), Offset(150f, 90f), Offset(100f, 85f),
                    ),
                    color = Color(0xFFFFAB40), strokeWidth = 1f,
                )
                drawPoints(
                    pointMode = PointMode.Polygon,
                    points = listOf(
                        Offset(200f, 120f), Offset(260f, 120f), Offset(300f, 90f), Offset(260f, 80f),
                    ),
                    color = Color(0xFFB388FF), strokeWidth = 2f,
                )
                drawPoints(
                    pointMode = PointMode.Polygon,
                    points = listOf(
                        Offset(20f, 170f), Offset(120f, 130f), Offset(220f, 170f), Offset(120f, 155f),
                    ),
                    color = Color(0xFF80DEEA), strokeWidth = 6f,
                )
                drawPoints(
                    pointMode = PointMode.Points,
                    points = listOf(Offset(300f, 30f)),
                    color = Color(0xFFFF8A65), strokeWidth = 10f,
                    cap = StrokeCap.Round,
                )
                drawPoints(
                    pointMode = PointMode.Polygon,
                    points = listOf(
                        Offset(340f, 20f), Offset(340f, 80f), Offset(370f, 80f), Offset(370f, 20f),
                    ),
                    color = Color(0xFFAED581), strokeWidth = 4f,
                )
            }

            // ── 抗锯齿:水平/垂直/斜线,1px/2px/6px ──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                drawLine(
                    color = Color(0xFFFFF59D), start = Offset(10f, 20f), end = Offset(300f, 20f),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = Color(0xFFFFF59D), start = Offset(10f, 45f), end = Offset(300f, 45f),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = Color(0xFFFFF59D), start = Offset(10f, 80f), end = Offset(300f, 80f),
                    strokeWidth = 6f,
                )
                drawLine(
                    color = Color(0xFF4DD0E1), start = Offset(10f, 120f), end = Offset(300f, 10f),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = Color(0xFFCE93D8), start = Offset(330f, 10f), end = Offset(338f, 120f),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = Color(0xFFA5D6A7), start = Offset(370f, 20f), end = Offset(380f, 20f),
                    strokeWidth = 3f,
                )
                drawLine(
                    color = Color(0xFFA5D6A7), start = Offset(370f, 40f), end = Offset(380f, 40f),
                    strokeWidth = 1f,
                )
            }
        }
    }
}
