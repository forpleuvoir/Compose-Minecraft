package moe.forpleuvoir.compose_minecraft.minecraft

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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

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
            McText(
                "Geometry Draw Test(图形绘制)",
                style = McTextStyle(color = Color.White, bold = true),
            )
            McText(
                "填充:圆/椭圆/弧(useCenter+弓形)/圆角矩形/Path(非凸+贝塞尔)",
                style = McTextStyle(color = Color(0xFFB0BEC5)),
            )
            McText(
                "描边:圆/椭圆/弧/圆角矩形/线/Path;点:Points/Lines/Polygon",
                style = McTextStyle(color = Color(0xFFB0BEC5)),
            )

            // ── 填充:圆 / 椭圆 ──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                drawCircle(color = Color(0xFFE53935), radius = 40f, center = Offset(52f, 55f))
                drawCircle(color = Color(0xFF1E88E5), radius = 20f, center = Offset(52f, 55f))
                drawOval(color = Color(0xFF43A047), topLeft = Offset(110f, 20f), size = Size(120f, 70f))
                drawOval(color = Color(0xFFFB8C00), topLeft = Offset(240f, 30f), size = Size(90f, 50f))
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
            }

            // ── 点:Points(Round/Butt) / Lines / Polygon(连续折线)──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(190.dp)
            ) {
                // Points + Round cap:每个点一个直径为 strokeWidth 的圆
                drawPoints(
                    pointMode = PointMode.Points,
                    points = listOf(
                        Offset(20f, 30f), Offset(60f, 30f), Offset(100f, 30f),
                        Offset(140f, 30f), Offset(180f, 30f),
                    ),
                    color = Color(0xFFE91E63), strokeWidth = 8f,
                    cap = StrokeCap.Round,
                )
                // Points + Butt cap(默认):每个点一个方形
                drawPoints(
                    pointMode = PointMode.Points,
                    points = listOf(
                        Offset(20f, 30f), Offset(60f, 30f), Offset(100f, 30f),
                        Offset(140f, 30f), Offset(180f, 30f),
                    ),
                    color = Color(0xFFF48FB1), strokeWidth = 4f,
                    cap = StrokeCap.Butt,
                )
                // Lines:两两独立成段(p0→p1, p2→p3),奇数点忽略末点
                drawPoints(
                    pointMode = PointMode.Lines,
                    points = listOf(
                        Offset(20f, 70f), Offset(120f, 70f),
                        Offset(160f, 70f), Offset(260f, 70f),
                        Offset(290f, 70f), // 奇数点:应被忽略
                    ),
                    color = Color(0xFF00E5FF), strokeWidth = 4f,
                )
                // Polygon:连续折线(不填充、不闭合),斜线/尖角 join
                drawPoints(
                    pointMode = PointMode.Polygon,
                    points = listOf(
                        Offset(20f, 110f), Offset(120f, 110f), Offset(150f, 90f), Offset(100f, 85f),
                    ),
                    color = Color(0xFFFFAB40), strokeWidth = 1f,
                )
                // Polygon:不同线宽(2px/6px)验证覆盖度渐变
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
                // 对角线(非像素对齐,验证两侧渐变)
                drawLine(
                    color = Color(0xFF4DD0E1), start = Offset(10f, 120f), end = Offset(300f, 10f),
                    strokeWidth = 1f,
                )
            }
        }
    }
}
