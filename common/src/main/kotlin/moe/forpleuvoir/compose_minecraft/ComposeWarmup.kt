package moe.forpleuvoir.compose_minecraft

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.forpleuvoir.compose_minecraft.platform.screen.MinecraftComposeScene
import moe.forpleuvoir.compose_minecraft.platform.ui.shadow
import org.slf4j.LoggerFactory

object ComposeWarmup {

    private val logger = LoggerFactory.getLogger(ComposeWarmup::class.java)
    private var scheduled = false

    /**
     * 构建一次性场景并渲染一帧：触发组合运行时、字体解析与几何三角化的首次初始化。
     * 命令只记录进 ComposeGuiRenderer，未注册 active，不提交 GPU。
     */
    fun schedule() {
        if (scheduled) return
        scheduled = true
        mc.execute { warmup() }
    }

    @OptIn(InternalComposeUiApi::class)
    private fun warmup() = runCatching {
        val window = mc.window
        val scene = MinecraftComposeScene(window.width, window.height)
        try {
            scene.setContent { WarmupContent() }
            scene.renderFrame()
        } finally {
            scene.close()
        }
    }.onFailure { logger.error("Compose warmup failed: ${it.message}", it) }

    @Composable
    private fun WarmupContent() {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            TextSamples()
            DrawSamples()
            LayerSamples()
            ScrollSamples()
            InteractionSamples()
        }
    }

    /**
     * 文本：多字号/字重/斜体/装饰线/字距行高/对齐/多语言字形、
     * 长文本多行与省略、annotated 富文本、文本输入。
     */
    @Composable
    private fun TextSamples() {
        Column {
            BasicText("warmup 暖机 0123 ABC")
            BasicText(
                "bold italic",
                style = TextStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
            )
            BasicText(
                "underline lineThrough",
                style = TextStyle(textDecoration = TextDecoration.Underline + TextDecoration.LineThrough),
            )
            BasicText("24sp 字距行高", style = TextStyle(fontSize = 24.sp, letterSpacing = 2.sp, lineHeight = 32.sp))
            BasicText("居中", style = TextStyle(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
            BasicText(
                "长文本换行与省略 ".repeat(12),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                buildAnnotatedString {
                    append("annotated ")
                    addStyle(SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold), 0, 9)
                    append("span")
                },
            )
            var field by remember { mutableStateOf("input") }
            BasicTextField(value = field, onValueChange = { field = it })
        }
    }

    /**
     * 绘制：矩形/圆角/圆/椭圆/线段/描边/路径/渐变/变换/drawWithContent。
     */
    @Composable
    private fun DrawSamples() {
        Row {
            Box(Modifier.size(40.dp).drawBehind { drawRect(Color.Red) })
            Box(Modifier.size(40.dp).drawBehind { drawRoundRect(Color.Green, cornerRadius = CornerRadius(8f, 8f)) })
            Box(Modifier.size(40.dp).drawBehind { drawCircle(Color.Blue) })
            Box(Modifier.size(40.dp).drawBehind { drawOval(Color.Cyan) })
            Box(Modifier.size(40.dp).drawBehind {
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.Black)))
            })
            Box(Modifier.size(40.dp).drawBehind {
                drawRect(Brush.linearGradient(listOf(Color.Yellow, Color.Magenta)))
            })
            Box(Modifier.size(40.dp).drawBehind {
                drawLine(
                    color = Color.Black,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            })
            Box(Modifier.size(40.dp).drawBehind {
                drawRect(color = Color.DarkGray, style = Stroke(width = 3f, join = StrokeJoin.Round))
            })
            Box(Modifier.size(40.dp).drawBehind {
                drawPath(
                    path = Path().apply {
                        moveTo(0f, size.height)
                        lineTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height)
                        close()
                    },
                    color = Color.Yellow,
                )
            })
            Box(Modifier.size(40.dp).drawBehind {
                rotate(30f) { drawRect(Color.Gray, size = Size(size.width / 2f, size.height / 2f)) }
            })
            Box(Modifier.size(40.dp).drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = 0.2f))
            })
        }
    }

    /**
     * 图层与裁剪：alpha/缩放/旋转/3D 旋转/形状裁剪/阴影/Modifier 变换。
     */
    @Composable
    private fun LayerSamples() {
        Row {
            Box(
                Modifier.size(32.dp)
                    .graphicsLayer { alpha = 0.6f; scaleX = 1.2f; scaleY = 0.8f; rotationZ = 15f }
                    .background(Color.Red),
            )
            Box(Modifier.size(32.dp).graphicsLayer { rotationX = 30f }.background(Color.Green))
            Box(Modifier.size(32.dp).graphicsLayer { rotationY = 30f }.background(Color.Blue))
            Box(
                Modifier.size(32.dp)
                    .graphicsLayer { shape = CircleShape; clip = true }
                    .background(Color.Yellow),
            )
            Box(
                Modifier.size(32.dp)
                    .shadow(4.dp, RoundedCornerShape(6.dp), clip = false)
                    .background(Color.White),
            )
            Box(Modifier.size(32.dp).rotate(45f).background(Color.Cyan))
            Box(Modifier.size(32.dp).scale(1.5f).background(Color.Magenta))
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Color.Gray))
            Box(Modifier.size(32.dp).border(2.dp, Color.Black, RoundedCornerShape(4.dp)))
        }
    }

    /** 滚动与懒列表：垂直/水平滚动容器、LazyColumn / LazyRow 子项组合与测量。 */
    @Composable
    private fun ScrollSamples() {
        Column(Modifier.height(80.dp).verticalScroll(rememberScrollState())) {
            repeat(20) { BasicText("scroll $it") }
        }
        LazyRow(state = rememberLazyListState(), modifier = Modifier.fillMaxWidth().height(40.dp)) {
            items(listOf("a", "b", "c", "d", "e")) { BasicText(it, modifier = Modifier.width(40.dp)) }
        }
        LazyColumn(modifier = Modifier.height(80.dp)) {
            items((0 until 20).toList()) { BasicText("lazy $it") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            repeat(10) { index ->
                Spacer(Modifier.size(20.dp).background(if (index % 2 == 0) Color.Red else Color.Blue))
            }
        }
    }

    /** 交互与语义：clickable / hoverable / focusable / selectable / toggleable。 */
    @Composable
    private fun InteractionSamples() {
        var selected by remember { mutableStateOf(false) }
        Row {
            Box(
                Modifier.size(28.dp)
                    .background(Color.Red)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            )
            Box(
                Modifier.size(28.dp)
                    .background(Color.Green)
                    .hoverable(remember { MutableInteractionSource() }),
            )
            Box(
                Modifier.size(28.dp)
                    .background(Color.Blue)
                    .focusable(interactionSource = remember { MutableInteractionSource() }),
            )
            Box(
                Modifier.size(28.dp)
                    .background(if (selected) Color.Yellow else Color.Gray)
                    .selectable(selected, onClick = { selected = !selected }),
            )
            Box(
                Modifier.size(28.dp)
                    .background(Color.Cyan)
                    .toggleable(value = selected, onValueChange = {}),
            )
        }
    }
}
