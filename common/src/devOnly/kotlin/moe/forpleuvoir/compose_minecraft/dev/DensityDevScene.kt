package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.minecraftItem
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * 密度参数测试:验证 `ComposeScreen.open(density = …)` 场景密度可配置。
 *
 * 本屏由 dev 菜单以 **density = 2f** 打开 —— 场景内所有 dp 尺寸 / sp 字号
 * 按官方桌面 density 语义放大 2 倍:
 * - [LocalDensity] 显示实际值(应为 2.0);
 * - 100.dp.toPx() 应为 200px(1f 时为 100px),红块视觉 2 倍大;
 * - sp 字号换算 `sp × density / 9`(BasicText.kt MC_TEXT_SCALE_BASE_PX),
 *   9sp = 1x 原生、18sp = 2x 基准 —— density=2 时再乘 2;
 * - BasicTextField 默认 fontSize = 18.sp,随 density 放大,光标位置同步。
 */
@Composable
fun DensityDevScene() {
    val density = LocalDensity.current
    val px100 = with(density) { 100.dp.toPx() }
    val textFieldState = rememberTextFieldState("density=2 输入框")

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0121212))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            BasicText(
                "密度参数测试 (density)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "T.26:场景 density 可配置。本屏经 ComposeScreen.open(density = 2f) 打开 ——\n" +
                        "dp 尺寸与 sp 字号全部放大 2 倍(官方桌面 density 语义)。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // ── ① density 读数 ──
            SectionLabel("① density 读数")
            BasicText(
                "LocalDensity.density = ${density.density}",
                style = Style.EMPTY.withColor(Color.White).toTextStyle(),
            )
            BasicText(
                "fontScale = ${density.fontScale}",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            BasicText(
                "100.dp.toPx() = $px100 px (1f 时应为 100,2f 应为 200)",
                style = Style.EMPTY.withColor(Color(0xFF80CBC4)).toTextStyle(),
            )

            // ── ② 固定 dp 参考物(视觉 2 倍大)──
            SectionLabel("② 固定 dp 参考物(100dp 红 / 50dp 绿)")
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(100.dp).background(Color.Red))
                Box(Modifier.size(50.dp).background(Color.Green))
            }
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(0.5f)
                    .height(2.dp)
                    .background(Color.Blue)
            )
            BasicText(
                "蓝线 = 50% 宽 × 2dp",
                style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
            )

            // ── ③ 字号阶梯(sp 含 density 放大)──
            SectionLabel("③ 字号阶梯 9/18/36sp (density=2 → 2x/4x/8x)")
            listOf(9.sp, 18.sp, 36.sp).forEach { s ->
                BasicText(
                    "${s.value}sp 文字",
                    style = Style.EMPTY.withColor(Color.White).toTextStyle().merge(TextStyle(fontSize = s)),
                )
            }
            Spacer(modifier = Modifier.size(64.dp).minecraftItem(ItemStack(Items.DIAMOND_SWORD)))

            // ── ④ 输入框(默认 18sp,随 density 放大)──
            SectionLabel("④ BasicTextField(默认 fontSize=18sp)")
            BasicTextField(
                state = textFieldState,
                modifier =
                    Modifier
                        .width(240.dp)
                        .height(120.dp)
                        .background(Color(0xFF263238))
                        .padding(2.dp),
                textStyle = Style.EMPTY.withColor(Color.White),
                cursorBrush = SolidColor(Color.White),
                lineLimits = TextFieldLineLimits.Default,
            )
            BasicText(
                "text = ${textFieldState.text.toString().ifEmpty { "(empty)" }}",
                style = Style.EMPTY.withColor(Color(0xFF80CBC4)).toTextStyle(),
            )
        }
    }
}

/** 测试块小节标题(青 0xFF80CBC4 加粗) */
@Composable
private fun SectionLabel(text: String) {
    Spacer(modifier = Modifier.height(8.dp))
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true).toTextStyle(),
    )
}
