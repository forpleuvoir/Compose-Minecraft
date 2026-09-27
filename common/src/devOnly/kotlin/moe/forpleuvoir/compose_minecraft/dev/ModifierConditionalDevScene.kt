package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.shadow
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import moe.forpleuvoir.compose_minecraft.platform.ui.thenIf
import moe.forpleuvoir.compose_minecraft.platform.ui.thenIfElse
import moe.forpleuvoir.compose_minecraft.platform.ui.thenIfNotNull
import net.minecraft.network.chat.Style

/**
 * 条件 Modifier 测试:`Modifier.thenIf` / `thenIfElse` / `thenIfNotNull`。
 *
 * 验证两点:
 * 1. **作用域穿透** —— `thenIf` 是 `inline` 且块不带接收者,块内可照常解析外层
 *    `@LayoutScopeMarker` 作用域修饰符:`BoxScope.align` / `BoxScope.matchParentSize` /
 *    `ColumnScope.align` / `RowScope.weight`;
 * 2. **链完整** —— `thenIf` 只往链尾追加,调用点之前的修饰符(如链首 `padding`)不丢,
 *    条件为 false 时不接任何东西。
 */
@Composable
fun ModifierConditionalDevScene() {
    var condition by remember { mutableStateOf(true) }
    var label by remember { mutableStateOf<String?>("A") }

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
                "条件 Modifier 测试 (thenIf / thenIfElse / thenIfNotNull)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "thenIf = inline + 块不带接收者:作用域修饰符穿透(BoxScope/ColumnScope/RowScope),\n" +
                        "且只往链尾追加 —— 链首修饰符不丢。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            DevMenuButton(
                title = "condition = $condition(点击切换)",
                subtitle = "切换后所有 thenIf 块随条件增删,组合位置与手写 if 一致",
                onClick = { condition = !condition },
            )
            DevMenuButton(
                title = "thenIfNotNull 的 value = ${label ?: "null"}(点击切换)",
                subtitle = "非 null 时块内 value 为不可空类型,可直接参与计算",
                onClick = { label = if (label == null) "A" else null },
            )

            // ── ① BoxScope.align 穿透 + 链完整 ──
            SectionLabel("① BoxScope.align 穿透 + 链首 padding 不丢")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color(0xFF263238))
            ) {
                Box(
                    Modifier
                        .padding(6.dp)
                        .size(36.dp)
                        .thenIf(condition) { Modifier.align(Alignment.Center) }
                        .background(Color(0xFF4CAF50))
                )
            }
            Hint("condition=true → 绿块居中;false → 停靠左上。两种情况链首 6dp padding 都生效")

            // ── ② BoxScope.matchParentSize 穿透 ──
            SectionLabel("② BoxScope.matchParentSize 穿透")
            Box(
                Modifier
                    .size(180.dp, 48.dp)
                    .background(Color(0xFF263238))
            ) {
                Box(
                    Modifier
                        .thenIf(condition) { Modifier.matchParentSize() }
                        .background(Color(0x552196F3))
                )
                BasicText(
                    "matchParentSize",
                    Modifier.align(Alignment.Center),
                    style = Style.EMPTY.withColor(Color.White).toTextStyle(),
                )
            }
            Hint("condition=true → 蓝色半透明块铺满整个 Box;false → 尺寸 0,不可见")

            // ── ③ ColumnScope / RowScope 穿透 ──
            SectionLabel("③ ColumnScope.align / RowScope.weight 穿透")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1B2A33))
                    .padding(4.dp)
            ) {
                Box(
                    Modifier
                        .size(80.dp, 20.dp)
                        .thenIf(condition) { Modifier.align(Alignment.CenterHorizontally) }
                        .background(Color(0xFF2196F3))
                )
            }
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color(0xFF263238))
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(Color(0xFF4CAF50))
                )
                Box(
                    Modifier
                        .size(24.dp)
                        .thenIf(condition) { Modifier.weight(1f) }
                        .background(Color(0xFF2196F3))
                )
            }
            Hint("条件开启时:蓝块在 Column 内水平居中,并在 Row 内撑满剩余宽度(weight 生效)")

            // ── ④ thenIfElse ──
            SectionLabel("④ thenIfElse(二选一)")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Color(0xFF263238))
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .thenIfElse(
                            condition = condition,
                            ifTrue = { Modifier.align(Alignment.CenterStart) },
                            ifFalse = { Modifier.align(Alignment.CenterEnd) },
                        )
                        .background(Color(0xFFFFC107))
                )
            }
            Hint("condition=true → 黄块靠左;false → 黄块靠右(两支都是 BoxScope 修饰符)")

            // ── ⑤ thenIfNotNull ──
            SectionLabel("⑤ thenIfNotNull(值 + smart cast)")
            Box(
                Modifier
                    .size(48.dp)
                    .thenIfNotNull(label) { value -> Modifier.background(colorFor(value)) }
            )
            Hint("value 非 null 时块内直接使用(不可空类型),null 时整块不接 —— 灰色底 = 未接分支")

            // ── ⑥ 块内调用 @Composable 工厂 ──
            SectionLabel("⑥ 块内调用 @Composable 修饰符工厂(platform shadow)")
            Box(
                Modifier
                    .size(120.dp, 40.dp)
                    .thenIf(condition) { Modifier.shadow(8.dp, RoundedCornerShape(8.dp)) }
                    .background(Color(0xFF37474F))
            )
            Spacer(Modifier.height(8.dp))
            Hint("@Composable 修饰符工厂(shadow)在块内可正常调用;condition=false 时完全不执行、不建组合")
        }
    }
}

private fun colorFor(value: String): Color =
    if (value.length > 1) Color(0xFFFFC107) else Color(0xFF4CAF50)

/** 测试块小节标题(青 0xFF80CBC4 加粗) */
@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(8.dp))
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF80CBC4)).withBold(true).toTextStyle(),
    )
}

/** 结论提示(灰蓝) */
@Composable
private fun Hint(text: String) {
    BasicText(
        text,
        style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle(),
    )
}
