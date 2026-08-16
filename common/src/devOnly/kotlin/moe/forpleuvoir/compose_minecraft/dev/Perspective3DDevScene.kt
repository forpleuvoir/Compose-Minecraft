package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 3D 透视专项测试屏幕(T.15):验证 graphicsLayer rotationX/rotationY/rotationZ 的绘制端。
 *
 * 布局:全屏居中,元素少,避免溢出。
 * - 青色方块:滑块控制 rotationX(绕水平轴);
 * - 紫色方块:滑块控制 rotationY(绕垂直轴);
 * - 橙色方块:两个滑块分别控制 rotationX + rotationY(双轴 3D);
 * - 绿色方块:三个滑块分别控制 rotationX + rotationY + rotationZ(三轴);
 * - 每个方块外层画固定红十字参考(不随 3D 旋转,验证旋转中心);
 * - 滑块范围 -90° ~ 90°,可拖动或点击轨道任意位置。
 */
@Composable
fun Perspective3DDevScene() {
    var rotX by remember { mutableStateOf(0f) }
    var rotY by remember { mutableStateOf(0f) }
    var rotXYX by remember { mutableStateOf(0f) }
    var rotXYY by remember { mutableStateOf(0f) }
    var rotXYZX by remember { mutableStateOf(0f) }
    var rotXYZY by remember { mutableStateOf(0f) }
    var rotXYZZ by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
    ) {
        // 返回主菜单(左上角)
        DevMenuButton(
            title = "← 返回主菜单",
            subtitle = "Back to menu",
            onClick = { ComposeScreen.open { MinecraftDevSceneContent() } },
        )

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                "3D 透视测试 (T.15):rotationX / rotationY / rotationZ",
                style = Style.EMPTY.withColor(Color.White).withBold(true),
            )

            Row(
                Modifier.padding(top = 24.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
            ) {
                AngleBox(
                    label = "X",
                    labelColor = Color.Black,
                    boxColor = Color(0xFF26C6DA),
                    rotationX = rotX,
                )
                AngleBox(
                    label = "Y",
                    labelColor = Color.White,
                    boxColor = Color(0xFFAB47BC),
                    rotationY = rotY,
                )
                AngleBox(
                    label = "XY",
                    labelColor = Color.White,
                    boxColor = Color(0xFFFF7043),
                    rotationX = rotXYX,
                    rotationY = rotXYY,
                )
                AngleBox(
                    label = "XYZ",
                    labelColor = Color.White,
                    boxColor = Color(0xFF66BB6A),
                    rotationX = rotXYZX,
                    rotationY = rotXYZY,
                    rotationZ = rotXYZZ,
                )
            }

            // ── 滑块行 ──
            Row(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
            ) {
                AngleSlider(
                    value = rotX,
                    onValueChange = { rotX = it },
                    modifier = Modifier.width(120.dp),
                )
                AngleSlider(
                    value = rotY,
                    onValueChange = { rotY = it },
                    modifier = Modifier.width(120.dp),
                )
                // XY 方块:两个滑块分别控制 rotationX / rotationY
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AngleSlider(
                        value = rotXYX,
                        onValueChange = { rotXYX = it },
                        modifier = Modifier.width(120.dp),
                    )
                    AngleSlider(
                        value = rotXYY,
                        onValueChange = { rotXYY = it },
                        modifier = Modifier.width(120.dp).padding(top = 4.dp),
                    )
                }
                // XYZ 方块:三个滑块分别控制 rotationX / rotationY / rotationZ
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AngleSlider(
                        value = rotXYZX,
                        onValueChange = { rotXYZX = it },
                        modifier = Modifier.width(120.dp),
                    )
                    AngleSlider(
                        value = rotXYZY,
                        onValueChange = { rotXYZY = it },
                        modifier = Modifier.width(120.dp).padding(top = 4.dp),
                    )
                    AngleSlider(
                        value = rotXYZZ,
                        onValueChange = { rotXYZZ = it },
                        modifier = Modifier.width(120.dp).padding(top = 4.dp),
                    )
                }
            }

            // 固定宽度:角度文本内容随拖动变化,若不固定宽度会导致整个居中的
            // Column 宽度重排,所有元素横向抖动(实测现象)。
            BasicText(
                "rX=$rotX° rY=$rotY° | XY-X=$rotXYX° XY-Y=$rotXYY° | XYZ-X=$rotXYZX° XYZ-Y=$rotXYZY° XYZ-Z=$rotXYZZ°",
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(620.dp),
                style = Style.EMPTY.withColor(Color(0xFFFFD54F)),
            )
            BasicText(
                "拖动滑块或点击轨道设置角度(-90° ~ 90°)",
                modifier = Modifier.padding(top = 4.dp),
                style = Style.EMPTY.withColor(Color(0xFF90A4AE)),
            )
            BasicText(
                "红十字不随方块移动 = 绕自身中心旋转;背景为真透视,文字为 2D 仿射近似",
                modifier = Modifier.padding(top = 8.dp),
                style = Style.EMPTY.withColor(Color(0xFF90A4AE)),
            )
            BasicText(
                "0° 时与普通 2D 渲染完全一致;非 0° 时呈透视四边形(近大远小)",
                style = Style.EMPTY.withColor(Color(0xFF90A4AE)),
            )
        }
    }
}

/** 带红十字参考的 3D 旋转方块 */
@Composable
private fun AngleBox(
    label: String,
    labelColor: Color,
    boxColor: Color,
    rotationX: Float = 0f,
    rotationY: Float = 0f,
    rotationZ: Float = 0f,
) {
    Box(
        Modifier
            .size(100.dp)
            // 固定红十字参考(外层,不随旋转)
            .drawBehind {
                drawLine(
                    color = Color.Red,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = Color.Red,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1f,
                )
            }
            .graphicsLayer {
                this.rotationX = rotationX
                this.rotationY = rotationY
                this.rotationZ = rotationZ
            }
            .background(boxColor),
    ) {
        BasicText(
            label,
            modifier = Modifier.align(Alignment.Center),
            style = Style.EMPTY.withColor(labelColor).withBold(true),
        )
    }
}

/**
 * 简单角度滑块(-90° ~ 90°):点击轨道任意位置或拖动设置角度。
 * 轨道带 -90/-45/0/45/90 刻度,滑块头为黄色圆点。
 */
@Composable
private fun AngleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackWidth by remember { mutableStateOf(1f) }
    Box(
        modifier
            .height(24.dp)
            .onSizeChanged { trackWidth = it.width.toFloat().coerceAtLeast(1f) }
            .drawBehind {
                val y = size.height / 2f
                // 轨道
                drawLine(
                    color = Color(0xFF455A64),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 3f,
                )
                // 刻度
                for (deg in intArrayOf(-90, -45, 0, 45, 90)) {
                    val x = (deg + 90) / 180f * size.width
                    drawLine(
                        color = Color(0xFF78909C),
                        start = Offset(x, y - 4f),
                        end = Offset(x, y + 4f),
                        strokeWidth = 1f,
                    )
                }
                // 滑块头
                val x = (value + 90) / 180f * size.width
                drawCircle(
                    color = Color(0xFFFFD54F),
                    radius = 6f,
                    center = Offset(x, y),
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onValueChange(((offset.x / trackWidth) * 180f - 90f).coerceIn(-90f, 90f))
                    },
                    onDrag = { change, _ ->
                        onValueChange(((change.position.x / trackWidth) * 180f - 90f).coerceIn(-90f, 90f))
                    },
                )
            },
    )
}
