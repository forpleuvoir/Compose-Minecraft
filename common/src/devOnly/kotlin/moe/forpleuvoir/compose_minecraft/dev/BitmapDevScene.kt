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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style
import kotlin.math.cos
import kotlin.math.sin

/**
 * 图层快照测试屏幕(T.17):验证 GraphicsLayer.toImageBitmap 的 CPU 光栅化。
 *
 * 上方 200x200 图层内容(与 GPU 回放相同的绘制命令,经 record 录制);
 * 点击「生成快照」→ 协程内 toImageBitmap() → 下方显示快照(原样 + 2x 放大),
 * 与屏幕上方的图层(GuiRenderState 回放)对照。
 *
 * 已知限制(光栅化器不支持,快照中缺失):文本与阴影命令(见
 * GraphicsLayerRasterizer 类注释)。
 */
@Composable
fun BitmapDevScene() {
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val density = LocalDensity.current

    // 程序生成 32x32 棋盘位图(红蓝 8x8 格)
    val checker = remember {
        val pixels = IntArray(32 * 32) { i ->
            val x = i % 32
            val y = i / 32
            if (((x / 8) + (y / 8)) % 2 == 0) 0xFFE53935.toInt() else 0xFF1E88E5.toInt()
        }
        ImageBitmap(32, 32, pixels)
    }

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
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            BasicText(
                "图层快照测试 (GraphicsLayer.toImageBitmap)",
                style = Style.EMPTY.withColor(Color.White).withBold(true),
            )
            BasicText(
                "上方:图层内容(record 录制,GuiRenderState 回放);下方:CPU 光栅化快照。\n" +
                    "文本与阴影命令快照中不支持(缺失)。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)),
            )

            // ── 图层(record 每帧录制,与下方快照同一内容)──
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(200.dp)
                    .drawWithContent {
                        layer.record(density, layoutDirection, IntSize(200, 200)) {
                            layerContent(checker)
                        }
                        // 把图层绘制到屏幕(GuiRenderState 回放),与下方快照对照
                        drawLayer(layer)
                    }
            )

            // ── 截图按钮 ──
            DevMenuButton(
                title = "生成快照 (toImageBitmap)",
                subtitle = "协程内调用,CPU 光栅化录制命令",
                onClick = {
                    scope.launch {
                        snapshot = layer.toImageBitmap()
                    }
                },
            )

            // ── 快照显示(原样 + 2x 放大)──
            val snap = snapshot
            if (snap != null) {
                BasicText(
                    "快照 ${snap.width}x${snap.height}",
                    style = Style.EMPTY.withColor(Color(0xFF90CAF9)),
                )
                Canvas(
                    Modifier
                        .padding(top = 4.dp)
                        .size(200.dp)
                        .background(Color.White)
                ) {
                    drawImage(snap)
                }
                BasicText(
                    "2x 放大(默认最近邻采样,像素格锐利)",
                    style = Style.EMPTY.withColor(Color(0xFF90CAF9)),
                )
                Canvas(
                    Modifier
                        .padding(top = 4.dp)
                        .size(400.dp)
                        .background(Color.White)
                ) {
                    drawImage(snap, dstSize = IntSize(400, 400))
                }
            } else {
                BasicText(
                    "尚未生成快照",
                    style = Style.EMPTY.withColor(Color(0xFF78909C)),
                )
            }
        }
    }
}

/**
 * 图层内容(200x200):背景 + 矩形/圆角矩形/圆/椭圆/弧(useCenter)/星形 Path/
 * 线/图片,覆盖光栅化器全部形状命令。
 */
private fun DrawScope.layerContent(checker: ImageBitmap) {
    drawRect(Color(0xFFECEFF1), topLeft = Offset.Zero, size = Size(200f, 200f))
    drawRect(Color(0xFFE53935), topLeft = Offset(8f, 8f), size = Size(80f, 50f))
    drawRoundRect(
        Color(0xFF43A047), topLeft = Offset(96f, 8f), size = Size(96f, 56f),
        cornerRadius = CornerRadius(10f), style = Stroke(3f),
    )
    drawCircle(Color(0xFF1E88E5), radius = 22f, center = Offset(48f, 105f))
    drawOval(Color(0xFFFB8C00), topLeft = Offset(78f, 88f), size = Size(70f, 34f))
    drawArc(
        Color(0xFF8E24AA), startAngle = 0f, sweepAngle = 120f, useCenter = true,
        topLeft = Offset(156f, 84f), size = Size(36f, 36f),
    )

    // 五角星 Path(填充)
    val star = Path()
    val cx = 48f
    val cy = 165f
    for (i in 0 until 10) {
        val angle = i * (Math.PI / 5) - Math.PI / 2
        val r = if (i % 2 == 0) 28f else 11f
        val x = cx + r * cos(angle).toFloat()
        val y = cy + r * sin(angle).toFloat()
        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()
    drawPath(star, Color(0xFF00ACC1))

    drawLine(Color(0xFFFDD835), start = Offset(90f, 140f), end = Offset(190f, 180f), strokeWidth = 3f)
    drawImage(checker, dstOffset = IntOffset(120, 140), dstSize = IntSize(32, 32))
}
