package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 开发环境总菜单(dev scene 主界面):
 *
 * 每项测试一个按钮,点击打开专属测试屏幕(独立的 ComposeScreen)。
 * 按钮为最简 Basic 层级外观(矩形 + 文本),无平台预设风格 —— 外观由业务方自定。
 */
@Composable
fun MinecraftDevSceneContent() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x00121212))
    ) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            BasicText(
                "Compose Minecraft Dev Menu",
                style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
            )
            BasicText(
                "选择一项测试:",
                style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
            )

            DevMenuButton(
                title = "文本字号测试 (Text / fontSize)",
                subtitle = "fontSize(sp) 驱动字号:8~48sp 阶梯、行高、窄宽换行、样式叠加",
                onClick = { ComposeScreen.open { TextDevScene() } },
            )

            DevMenuButton(
                title = "文本输入测试 (TextInput)",
                subtitle = "中英文/IME 上屏、光标移动、选区、剪贴板",
                onClick = { ComposeScreen.open { TextInputDevScene() } },
            )

            DevMenuButton(
                title = "指针图标测试 (PointerIcon / I9)",
                subtitle = "悬停切换 Default/Crosshair/Text/Hand 光标;输入框 I 形、链接手型、overrideDescendants",
                onClick = { ComposeScreen.open { PointerIconDevScene() } },
            )

            DevMenuButton(
                title = "复述系统测试 (Narration)",
                subtitle = "Compose 语义树 → MC 原版朗读:焦点优先 + 悬停回退,Tab/悬停即读",
                onClick = { ComposeScreen.open { NarrationDevScene() } },
            )

            DevMenuButton(
                title = "Dialog / Popup 测试",
                subtitle = "Popup 锚点/外部关闭/Esc/非模态;Dialog scrim/居中/模态;Tooltip 联动",
                onClick = { ComposeScreen.open { DialogPopupDevScene() } },
            )

            DevMenuButton(
                title = "样式与交互验证 (Style/Click/Focus/Scroll)",
                subtitle = "BasicText 样式矩阵、鼠标点击、焦点、滚轮",
                onClick = { ComposeScreen.open { StyleMatrixDevScene() } },
            )

            DevMenuButton(
                title = "滚动专项测试 (Scroll)",
                subtitle = "固定高度全宽列表,滚轮方向/速度/裁剪验证",
                onClick = { ComposeScreen.open { ScrollTestDevScene() } },
            )

            DevMenuButton(
                title = "图形绘制测试 (Geometry)",
                subtitle = "圆/椭圆/弧/圆角矩形/线/Path/点,填充与描边",
                onClick = { ComposeScreen.open { GeometryDevScene() } },
            )

            DevMenuButton(
                title = "旋转专项测试 (Rotation)",
                subtitle = "长文本/方块绕中心无限旋转,红十字/红点中心参考",
                onClick = { ComposeScreen.open { RotationTestDevScene() } },
            )

            DevMenuButton(
                title = "3D 透视测试 (Perspective 3D)",
                subtitle = "rotationX/rotationY 3D 透视,点击方块切换角度",
                onClick = { ComposeScreen.open { Perspective3DDevScene() } },
            )

            DevMenuButton(
                title = "图片管线测试 (Image)",
                subtitle = "程序生成位图/资源 PNG 解码/drawImageRect/变换组合",
                onClick = { ComposeScreen.open { ImageDevScene() } },
            )

            DevMenuButton(
                title = "图层快照测试 (GraphicsLayer toImageBitmap)",
                subtitle = "record 录制 → CPU 光栅化快照,与 GPU 回放对照",
                onClick = { ComposeScreen.open { BitmapDevScene() } },
            )

            DevMenuButton(
                title = "颜色滤镜测试 (ColorFilter)",
                subtitle = "ColorMatrix 反相/灰度、tint、lighting、图层级滤镜",
                onClick = { ComposeScreen.open { ColorFilterDevScene() } },
            )

            DevMenuButton(
                title = "混合模式测试 (BlendMode)",
                subtitle = "17 种可表达 blendMode:灰底 + 半透明红叠加,矩形/圆形双几何",
                onClick = { ComposeScreen.open { BlendModeDevScene() } },
            )

            DevMenuButton(
                title = "顶点渐变测试 (drawVertices)",
                subtitle = "每顶点色 GPU 插值:四边形/圆形渐变、strip/fan、Plus 叠合、乱序索引",
                onClick = { ComposeScreen.open { VerticesDevScene() } },
            )

            DevMenuButton(
                title = "原版绘制修饰符测试 (vanillaDraw / postVanillaDraw)",
                subtitle = "前/后渲染双通道:guiScale 开关切换 1:1 像素桥与原版通道,坐标换算与层级",
                onClick = { ComposeScreen.open { VanillaDrawDevScene() } },
            )

            DevMenuButton(
                title = "vanillaDraw 遮挡/弹层测试 (Layer Occlusion)",
                subtitle = "vanilla 与 Compose 互相遮盖矩阵 + Popup/Dialog 弹层内 vanillaDraw + 滚动跟随",
                onClick = { ComposeScreen.open { VanillaDrawLayerDevScene() } },
            )

            DevMenuButton(
                title = "父屏幕能力测试 (ParentScreen)",
                subtitle = "关闭时返回父屏(dev 菜单);渲染父屏开关(半透明背景透出 dev 菜单)",
                onClick = {
                    val holder = arrayOfNulls<ComposeScreen>(1)
                    holder[0] = ComposeScreen.open(
                        content = {
                            ParentDevSceneContent(
                                renderParent = holder[0]?.renderParentScreen == true,
                                onToggleRenderParent = {
                                    val s = holder[0]
                                    if (s != null) s.renderParentScreen = !s.renderParentScreen
                                },
                                onClose = { holder[0]?.onClose() },
                            )
                        },
                        parent = mc.gui.screen(),
                    )
                },
            )

            DevMenuButton(
                title = "原版父屏测试 (Vanilla Parent)",
                subtitle = "原版 Screen(一个按钮)→ 打开 Compose 屏并渲染原版父屏(按钮+遮罩透出)",
                onClick = { openVanillaParentTest() },
            )

            DevMenuButton(
                title = "密度参数测试 (Density)",
                subtitle = "ComposeScreen.open(density=2f) 打开 —— dp 尺寸/sp 字号放大 2 倍,100dp 应显示为 200px",
                onClick = { ComposeScreen.open(density = 2f) { DensityDevScene() } },
            )

            DevMenuButton(
                title = "系统字体测试 (OS Fonts)",
                subtitle = "枚举系统字体目录,点击注册为自定义字体并经 LocalDefaultFont 切换渲染",
                onClick = { ComposeScreen.open { FontDevScene() } },
            )

            DevMenuButton(
                title = "原生纹理测试 (MinecraftTexture)",
                subtitle = "MC 原生纹理/物品渲染: 纹理、方块、物品、DrawScope 扩展、尺寸变换",
                onClick = { ComposeScreen.open { TextureDevScene() } },
            )

            DevMenuButton(
                title = "原版精灵图测试 (R.1)",
                subtitle = "GUI atlas sprite: Composable/Modifier/DrawScope 三入口, 九宫格缩放与调制色",
                onClick = { ComposeScreen.open { SpriteDevScene() } },
            )

            DevMenuButton(
                title = "实体渲染测试 (MinecraftEntity)",
                subtitle = "玩家/生物实体离屏 PIP 渲染: 旋转 rotationX/Y、色调色、alpha 透传",
                onClick = { ComposeScreen.open { EntityDevScene() } },
            )

            DevMenuButton(
                title = "Alpha 透传测试",
                subtitle = "单个 Box + graphicsLayer(alpha=0.5) + drawMinecraftTexture, 验证透明度",
                onClick = { ComposeScreen.open { AlphaTestScene() } },
            )

            DevMenuButton(
                title = "双击测试 (DoubleTap)",
                subtitle = "detectTapGestures onTap/onDoubleTap 计数 + BasicTextField 双击选词",
                onClick = { ComposeScreen.open { DoubleTapDevScene() } },
            )

            DevMenuButton(
                title = "原版 Tooltip 插件测试 (T.39)",
                subtitle = "Compose 1:1 渲染原版视觉 tooltip:物品/文本/满空 bundle,popup 鼠标跟随,guiscale 开关",
                onClick = { ComposeScreen.open { TooltipDevScene() } },
            )

            DevMenuButton(
                title = "TrueType 文本渲染对照 (T.TT)",
                subtitle = "自研 stb_truetype 管线 vs 原版位图:开关切换、字号阶梯、换行与输入框度量同源",
                onClick = { ComposeScreen.open { TrueTypeTextDevScene() } },
            )

            DevMenuButton(
                title = "字体缺字回退测试 (P3 像素化)",
                subtitle = "Fusion Pixel 覆盖内像素字形;阿拉伯文/泰文/Emoji 等未覆盖文字退回原版字形,附 default 对照组",
                onClick = { ComposeScreen.open { FontFallbackDevScene() } },
            )
        }
    }
}

/** 总菜单按钮:最简矩形 + 文本(Basic 层级,无平台预设外观) */
@Composable
internal fun DevMenuButton(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .background(Color(0xFF37474F))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        BasicText(title, style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle())
        BasicText(subtitle, style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle())
    }
}
