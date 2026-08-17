package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen

/**
 * 顶点渐变测试 (T.23):Canvas.drawVertices —— 每顶点色 GPU 插值。
 *
 * ① 四边形渐变(Triangles + 索引)  红/绿/蓝/白 四角
 * ② 圆形渐变(Triangles + fan 索引) 中心黄 → 边缘蓝
 * ③ TriangleStrip                  锯齿带
 * ④ TriangleFan                    扇形渐变
 * ⑤ blendMode = Plus               半透明顶点色叠合
 * ⑥ 乱序索引                       非顺序索引展开正确性
 */
@Composable
internal fun VerticesDevScene() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            BasicText(
                text = "顶点渐变测试 (T.23)",
                style = TextStyle(fontSize = 22.sp),
                color = { Color(0xFF1A1A1A) },
            )
            BasicText(
                text = "每顶点色经 GPU 插值;①③⑥ 同色区应平滑过渡,⑤ 重叠区 = 品红亮化。请按编号报告。",
                style = TextStyle(fontSize = 12.sp),
                color = { Color(0xFF455A64) },
            )
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "",
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            SectionLabel("① 四边形渐变 (Triangles + 索引)")
            VerticesBlock {
                drawVertices(
                    listOf(
                        Offset(40f, 40f),   // 0 红
                        Offset(160f, 40f),  // 1 绿
                        Offset(160f, 160f), // 2 蓝
                        Offset(40f, 160f),  // 3 白
                    ),
                    indices = listOf(0, 1, 2, 0, 2, 3),
                    colors = listOf(
                        Color(0xFFFF5252), Color(0xFF69F0AE), Color(0xFF448AFF), Color(0xFFFFFFFF),
                    ),
                    blendMode = BlendMode.SrcOver,
                    paint = Paint(),
                )
            }

            SectionLabel("② 圆形渐变 (Triangles + fan 索引)")
            VerticesBlock {
                val cx = 100f; val cy = 100f; val r = 80f; val seg = 24
                val pts = ArrayList<Offset>(seg + 1)
                val cols = ArrayList<Color>(seg + 1)
                pts += Offset(cx, cy); cols += Color(0xFFFFEB3B) // 中心黄
                for (i in 0 until seg) {
                    val a = angle(i, seg)
                    pts += Offset(cx + r * cosF(a), cy + r * sinF(a))
                    cols += Color(0xFF2962FF) // 边缘蓝
                }
                val idx = IntArray(seg * 3) { k ->
                    val t = k / 3
                    when (k % 3) {
                        0 -> 0
                        1 -> t + 1
                        else -> if (t + 2 > seg) 1 else t + 2
                    }
                }
                drawVertices(pts, idx.toList(), cols, BlendMode.SrcOver, Paint())
            }

            SectionLabel("③ TriangleStrip 锯齿带")
            VerticesBlock {
                drawVertices(
                    listOf(
                        Offset(40f, 40f), Offset(160f, 40f), Offset(40f, 100f),
                        Offset(160f, 100f), Offset(40f, 160f), Offset(160f, 160f),
                    ),
                    indices = emptyList(),
                    colors = listOf(
                        Color(0xFFE91E63), Color(0xFF00BCD4), Color(0xFFFFEB3B),
                        Color(0xFF9C27B0), Color(0xFF4CAF50), Color(0xFFFF9800),
                    ),
                    blendMode = BlendMode.SrcOver,
                    paint = Paint(),
                    vertexMode = VertexMode.TriangleStrip,
                )
            }

            SectionLabel("④ TriangleFan 扇形渐变")
            VerticesBlock {
                val cx = 100f; val cy = 100f; val r = 80f; val seg = 10
                val pts = ArrayList<Offset>(seg + 2); val cols = ArrayList<Color>(seg + 2)
                pts += Offset(cx, cy); cols += Color(0xFFE0F7FA)
                for (i in 0..seg) {
                    val a = angle(i, seg)
                    pts += Offset(cx + r * cosF(a), cy + r * sinF(a))
                    cols += Color(0xFFD500F9)
                }
                drawVertices(pts, emptyList(), cols, BlendMode.SrcOver, Paint(), vertexMode = VertexMode.TriangleFan)
            }

            SectionLabel("⑤ blendMode = Plus 叠合(深灰底,加法混合)")
            // Plus = 加法混合:白背景(255)上加任何颜色都饱和成白,必须用深色底。
            // 两个三角形的斜边交叉于中心 → 中心菱形区 = 红+蓝 = 亮的品红
            Box(
                Modifier
                    .padding(bottom = 12.dp)
                    .width(200.dp)
                    .height(200.dp)
                    .background(Color(0xFF757575))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // 右下三角:红 (alpha 0.6)
                    drawVertices(
                        listOf(Offset(40f, 160f), Offset(160f, 40f), Offset(160f, 160f)),
                        indices = emptyList(),
                        colors = listOf(
                            Color(0x99FF1744), Color(0x99FF1744), Color(0x99FF1744),
                        ),
                        blendMode = BlendMode.Plus,
                        paint = Paint(),
                    )
                    // 左下三角:蓝 (alpha 0.6),斜边与红三角交叉 → 中心重叠
                    drawVertices(
                        listOf(Offset(40f, 40f), Offset(160f, 160f), Offset(40f, 160f)),
                        indices = emptyList(),
                        colors = listOf(
                            Color(0x993040FF), Color(0x993040FF), Color(0x993040FF),
                        ),
                        blendMode = BlendMode.Plus,
                        paint = Paint(),
                    )
                }
            }

            SectionLabel("⑥ 乱序索引")
            VerticesBlock {
                // 索引 [3,0,2, 1,2,0] 打乱顺序,验证索引展开按给出顺序
                drawVertices(
                    listOf(Offset(40f, 40f), Offset(160f, 40f), Offset(160f, 160f), Offset(40f, 160f)),
                    indices = listOf(3, 0, 2, 1, 2, 0),
                    colors = listOf(
                        Color(0xFF00E676), Color(0xFFFF1744), Color(0xFF2979FF), Color(0xFFFFEA00),
                    ),
                    blendMode = BlendMode.SrcOver,
                    paint = Paint(),
                )
            }

            Box(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VerticesBlock(draw: DrawScope.() -> Unit) {
    Canvas(
        Modifier
            .padding(bottom = 12.dp)
            .width(200.dp)
            .height(200.dp)
    ) {
        draw(this)
    }
}

/** 快捷封装:Triangles 模式的顶点渐变(默认),indices 为空 = 顺序展开 */
private fun DrawScope.drawVertices(
    positions: List<Offset>,
    indices: List<Int>,
    colors: List<Color>,
    blendMode: BlendMode,
    paint: Paint,
    vertexMode: VertexMode = VertexMode.Triangles,
) {
    drawIntoCanvas { canvas ->
        val vertices = Vertices(
            vertexMode,
            positions,
            List(positions.size) { Offset.Zero },
            colors,
            indices,
        )
        canvas.drawVertices(vertices, blendMode, paint)
    }
}

private fun angle(i: Int, seg: Int): Float = kotlin.math.PI.toFloat() * 2f * i / seg

private fun cosF(a: Float): Float = kotlin.math.cos(a.toDouble()).toFloat()

private fun sinF(a: Float): Float = kotlin.math.sin(a.toDouble()).toFloat()

@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text = text,
        style = TextStyle(fontSize = 13.sp),
        color = { Color(0xFF1A1A1A) },
    )
}
