package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 复述系统(Narration)测试屏幕:验证 Compose 语义树 → MC 原版朗读链。
 *
 * 前置:游戏设置 → 无障碍 → 朗读器 需开启(或 IDE 内 SharedConstants.DEBUG_UI_NARRATION),
 * 否则整链静默(shouldRunNarration 依赖 Narrator.isActive())。
 *
 * 验证项(每项悬停/聚焦后约 750ms/200ms 自动朗读,文本输出到 TTS 或日志):
 * - 焦点优先:Tab 键在下方各可聚焦元素间移动,朗读跟随焦点节点
 *   (BasicTextField 读 editableText、按钮读 role+文本、带 contentDescription 读描述);
 * - 悬停回退:鼠标悬停 BasicText(自带 text 语义)、纯图片类(仅 contentDescription)、
 *   带 stateDescription 的开关/复选;
 * - 附加 HINT:stateDescription / error / heading / 进度条 追加到朗读文本;
 * - 隐藏节点:hideFromAccessibility 的节点不朗读(悬停无输出)。
 */
@Composable
fun NarrationDevScene() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0121212))
    ) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            DevMenuButton(
                title = "← 返回主菜单",
                subtitle = "Back to menu",
                onClick = { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } },
            )

            BasicText(
                "复述系统测试 (Narration / Compose Semantics)",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "开启游戏「朗读器」后:Tab 移动焦点 / 悬停控件,等待约 0.2-0.75s 自动朗读。\n" +
                    "朗读文本 = 语义树映射(text/contentDescription/stateDescription/role)。",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // 1. 焦点优先:BasicTextField 读 editableText(点进去聚焦即可见光标)
            BasicText(
                "1. 聚焦输入框:朗读应读出「这里输入文字测试」",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            val textState: TextFieldState = rememberTextFieldState("这里输入文字测试")
            BasicTextField(
                state = textState,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(Color(0xFF263238))
                        .padding(2.dp),
                textStyle = Style.EMPTY.withColor(Color.White),
                cursorBrush = SolidColor(Color.White),
            )

            // 2. 悬停回退:BasicText 自带 text 语义
            BasicText(
                "2. 悬停本行:朗读应读出这句话(悬停回退,BasicText 自带 text 语义)",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            // 3. 按钮:clickable + role 写语义 → 朗读「按钮」类型词 + 文案
            BasicText(
                "3. 悬停/聚焦按钮:朗读应含「按钮」类型词 + 文案",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF37474F))
                    .clickable(role = Role.Button, onClick = {})
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                BasicText("带类型的按钮", style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle())
                BasicText("role=Button,悬停朗读「带类型的按钮 + 按钮」", style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle())
            }

            // 4. 纯 contentDescription(无 text):如图片语义
            BasicText(
                "4. 悬停色块:仅 contentDescription,朗读应读出描述(图片语义)",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            // 无 text/role,仅 contentDescription:
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(width = 120.dp, height = 40.dp)
                    .background(Color(0xFF4E342E))
                    .semantics { contentDescription = "棕色示例图片区域" }
            )

            // 5. 附加 HINT:stateDescription / error / heading
            BasicText(
                "5. 悬停各块:朗读应含附加 HINT(状态描述/错误/标题)",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Row {
                // stateDescription
                Box(
                    Modifier
                        .size(width = 110.dp, height = 40.dp)
                        .background(Color(0xFF1B5E20))
                        .semantics {
                            contentDescription = "复选框示例"
                            stateDescription = "已勾选"
                        }
                )
                Spacer(Modifier.width(8.dp))
                // error
                Box(
                    Modifier
                        .size(width = 110.dp, height = 40.dp)
                        .background(Color(0xFFB71C1C))
                        .semantics {
                            contentDescription = "输入校验"
                            error("格式错误")
                        }
                )
                Spacer(Modifier.width(8.dp))
                // heading
                Box(
                    Modifier
                        .size(width = 110.dp, height = 40.dp)
                        .background(Color(0xFF0D47A1))
                        .semantics {
                            contentDescription = "章节标题"
                            heading()
                        }
                )
            }

            // 6. 显式 hideFromAccessibility:悬停不应朗读
            BasicText(
                "6. 悬停下方色块:无输出(该节点对读屏隐藏)",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(width = 120.dp, height = 40.dp)
                    .background(Color(0xFF37474F))
                    .semantics(mergeDescendants = true) {
                        contentDescription = "隐藏内容不应朗读"
                        hideFromAccessibility()
                    }
            )

            // 7. focusable 节点:Tab 可达,朗读 role 兜底(无文本时)
            BasicText(
                "7. focusable 空文本:焦点时朗读「焦点区域(无文本)」形状词",
                modifier = Modifier.padding(top = 16.dp),
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(width = 120.dp, height = 40.dp)
                    .background(Color(0xFF00695C))
                    .focusable(interactionSource = remember { MutableInteractionSource() })
                    .semantics { contentDescription = "可聚焦区域(无点击)" }
            )
        }
    }
}