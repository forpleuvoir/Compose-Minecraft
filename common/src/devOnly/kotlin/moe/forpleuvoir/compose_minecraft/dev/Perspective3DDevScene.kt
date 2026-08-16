package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 3D 透视专项测试屏幕(T.15):验证 graphicsLayer rotationX/rotationY 的绘制端。
 *
 * 布局:全屏居中,元素少,避免溢出。
 * - 青色方块:点击在 0° / 45° / 60° / -45° 间切换 rotationX(绕水平轴);
 * - 紫色方块:点击切换 rotationY(绕垂直轴);
 * - 两个方块外层各画固定红十字参考(不随 3D 旋转,验证旋转中心);
 * - 底部说明文字提示当前角度与 3D 语义。
 */
@Composable
fun Perspective3DDevScene() {
    var rotX by remember { mutableStateOf(0f) }
    var rotY by remember { mutableStateOf(0f) }
    var rotXY by remember { mutableStateOf(0f) }

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
                "3D 透视测试 (T.15):rotationX / rotationY",
                style = Style.EMPTY.withColor(Color.White).withBold(true),
            )

            Row(
                Modifier.padding(top = 24.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(48.dp),
            ) {
                // ── rotationX:绕水平轴旋转(垂直压扁 + 透视)──
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
                        .graphicsLayer { rotationX = rotX }
                        .background(Color(0xFF26C6DA))
                        .clickable {
                            rotX = when (rotX) {
                                0f -> 45f
                                45f -> 60f
                                60f -> -45f
                                else -> 0f
                            }
                        },
                ) {
                    BasicText(
                        "X",
                        modifier = Modifier.align(Alignment.Center),
                        style = Style.EMPTY.withColor(Color.Black).withBold(true),
                    )
                }

                // ── rotationY:绕垂直轴旋转(水平压扁 + 透视)──
                Box(
                    Modifier
                        .size(100.dp)
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
                        .graphicsLayer { rotationY = rotY }
                        .background(Color(0xFFAB47BC))
                        .clickable {
                            rotY = when (rotY) {
                                0f -> 45f
                                45f -> 60f
                                60f -> -45f
                                else -> 0f
                            }
                        },
                ) {
                    BasicText(
                        "Y",
                        modifier = Modifier.align(Alignment.Center),
                        style = Style.EMPTY.withColor(Color.White).withBold(true),
                    )
                }

                // ── rotationX + rotationY:双轴同时旋转(斜向 3D)──
                Box(
                    Modifier
                        .size(100.dp)
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
                            rotationX = rotXY
                            rotationY = rotXY
                        }
                        .background(Color(0xFFFF7043))
                        .clickable {
                            rotXY = when (rotXY) {
                                0f -> 45f
                                45f -> 60f
                                60f -> -45f
                                else -> 0f
                            }
                        },
                ) {
                    BasicText(
                        "XY",
                        modifier = Modifier.align(Alignment.Center),
                        style = Style.EMPTY.withColor(Color.White).withBold(true),
                    )
                }
            }

            BasicText(
                "点击方块切换角度:0° → 45° → 60° → -45°",
                modifier = Modifier.padding(top = 20.dp),
                style = Style.EMPTY.withColor(Color(0xFFFFD54F)),
            )
            BasicText(
                "rotationX=$rotX° rotationY=$rotY° rotationXY=$rotXY°",
                style = Style.EMPTY.withColor(Color(0xFFFFD54F)),
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
