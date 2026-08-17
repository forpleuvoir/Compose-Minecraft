package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.LocalShadowLight
import moe.forpleuvoir.compose_minecraft.platform.shadow
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 旋转专项测试屏幕:全屏 Box,测试元素置于正中心,无滚动/布局干扰。
 *
 * 验证 `graphicsLayer.rotationZ` 的旋转中心:
 * - 红色十字:画在文本中心(graphicsLayer 外层),不随文本旋转 —— 若文本绕自身中心转,十字不动;
 * - 红色圆点:画在方块中心(graphicsLayer 外层),不随方块旋转 —— 若方块绕自身中心转,红点不动;
 * - 黄色方块带黑色对角线:对角线的转动直观显示自转。
 */
@Composable
fun RotationTestDevScene() {
    val spinTransition = rememberInfiniteTransition(label = "spin")
    val spinRotation by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinRotation",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        // 返回主菜单(左上角)
        DevMenuButton(
            title = "← 返回主菜单",
            subtitle = "Back to menu",
            onClick = { ComposeScreen.open { MinecraftDevSceneContent() } },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 48.dp, start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 1. 长文本:绕中心旋转,固定红十字参考 ──
            Box(
                Modifier
                    // 固定红十字(外层,不随旋转):画在文本中心
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
                    .graphicsLayer { rotationZ = spinRotation }
            ) {
                BasicText(
                    "Focus B: true (tab to move)",
                    style = Style.EMPTY.withColor(Color(0xFF1A1A1A)),
                )
            }

            // ── 2. 40x40 方块:绕中心旋转,固定中心红点参考 ──
            Box(
                Modifier
                    .padding(top = 56.dp)
                    .size(40.dp)
                    // 固定中心红点(外层,不随旋转)
                    .drawBehind {
                        drawCircle(
                            color = Color.Red,
                            radius = 3f,
                            center = Offset(size.width / 2f, size.height / 2f),
                        )
                    }
                    .graphicsLayer { rotationZ = spinRotation }
                    .background(Color(0xFFFFEB3B))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // 对角线方向标记:随方块旋转
                    drawLine(
                        color = Color.Black,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2f,
                    )
                }
            }

            BasicText(
                "长文本与方块无限旋转(2s/圈);红十字/红点为固定中心参考",
                modifier = Modifier.padding(top = 24.dp),
                style = Style.EMPTY.withColor(Color(0xFF455A64)),
            )
            BasicText(
                "红十字不动 = 文本绕自身中心转;红点不动 = 方块绕自身中心转",
                style = Style.EMPTY.withColor(Color(0xFF455A64)),
            )

            // ── 3. 阴影演示:shadowElevation + 圆角矩形 outline(CPU 离屏真模糊)──
            // 浅色底板让黑色阴影可见;光源方向经 LocalShadowLight 配置(点击切换)
            var lightIndex by remember { mutableStateOf(0) }
            val lights = listOf(
                Offset(1f, -1f),    // 右上(默认)
                Offset(-1f, -1f),   // 左上
                Offset(0f, -1f),    // 正上
                Offset(1f, 1f),     // 右下
            )
            val lightNames = listOf("右上", "左上", "正上", "右下")
            CompositionLocalProvider(LocalShadowLight provides lights[lightIndex]) {
                Box(
                    Modifier
                        .padding(top = 32.dp)
                        .background(Color(0xFF78909C))
                        .padding(28.dp)
                ) {
                    Box(
                        Modifier
                            .size(96.dp, 36.dp)
                            // 阴影必须在内容(background)之前:shadow(graphicsLayer)包裹内容,
                            // 阴影命令先记录、内容后回放 → 内容盖住阴影重叠部分
                            .shadow(1.dp, RoundedCornerShape(4.dp))
                            .background(Color(0xFF42A5F5), RoundedCornerShape(4.dp))
                    )
                }
            }
            BasicText(
                "阴影:shadowElevation=4 + RectangleShape(直角矩形投影)",
                modifier = Modifier.padding(top = 6.dp),
                style = Style.EMPTY.withColor(Color(0xFF455A64)),
            )
            // ── 3b. 阴影颜色演示(T.18):Modifier.shadow 的 ambientColor/spotColor ──
            // ambient(无偏移)= 蓝色、spot(投影偏移)= 橙红色;两张网格颜色各自生效。
            // 与 3a 的黑阴影对比,底色同浅色底板。
            Box(
                Modifier
                    .padding(top = 16.dp)
                    .size(96.dp, 36.dp)
                    .shadow(
                        4.dp,
                        RoundedCornerShape(4.dp),
                        ambientColor = Color(0xFF1976D2),
                        spotColor = Color(0xFFE64A19),
                    )
                    .background(Color(0xFFFFE0B2), RoundedCornerShape(4.dp))
            )
            BasicText(
                "阴影颜色:ambient 蓝(无偏移)+ spot 橙红(投影偏移)",
                modifier = Modifier.padding(top = 6.dp),
                style = Style.EMPTY.withColor(Color(0xFF455A64)),
            )
            BasicText(
                "光源:${lightNames[lightIndex]}(点击切换 LocalShadowLight)",
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(Color(0xFF37474F))
                    .clickable { lightIndex = (lightIndex + 1) % lights.size }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = Style.EMPTY.withColor(Color(0xFFFFD54F)),
            )
        }
    }
}
