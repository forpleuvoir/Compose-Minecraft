package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalDefaultFont
import moe.forpleuvoir.compose_minecraft.platform.ui.text.MinecraftFonts
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style

/**
 * 字体缺字回退测试(像素化):
 *
 * - 全局默认字体 = compose_minecraft:fusion_pixel(12px 网格,24sp 显示);
 * - Fusion Pixel 覆盖外的文字(阿拉伯文/泰文/希伯来文/天城文/emoji 等)应
 *   **退回原版字形**(提交层按码点覆盖切段,未覆盖段换 minecraft:default);
 * - 若显示为「□」方框,说明回退链断裂;
 * - 底部提供 minecraft:default 对照组(整段原版字形,无像素字体)。
 */
@Composable
fun FontFallbackDevScene() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6121212))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        BasicText(
            "Fusion Pixel 缺字回退测试",
            style = Style.EMPTY.withColor(Color.White).withBold(true).toTextStyle(),
        )
        BasicText(
            "预期:覆盖内文字为像素字形;阿拉伯文/泰文/Emoji 等未覆盖文字自动退回原版字形(方框 = 回退断裂)",
            style = Style.EMPTY.withColor(Color(0xFFB0BEC5)).toTextStyle(),
        )

        FallbackSection("默认(compose_minecraft:fusion_pixel + 缺字回退)") {
            FallbackSamples()
        }

        FallbackSection("对照:LocalDefaultFont = minecraft:default(整段原版)") {
            CompositionLocalProvider(LocalDefaultFont provides MinecraftFonts.Default) {
                FallbackSamples()
            }
        }
    }
}

/** 采样文本组:覆盖内(应像素)+ 覆盖外(应原版)成对呈现 */
@Composable
private fun FallbackSamples() {
    SampleRow("拉丁 + 中文(覆盖内,应像素)", "The quick brown fox 敏捷的棕色狐狸跳过了懒狗")
    SampleRow("韩文(覆盖内,谚文)", "안녕하세요 한글 텍스트")
    SampleRow("日文(假名 + 汉字)", "こんにちは、世界!カタカナ")
    SampleRow("阿拉伯文(覆盖外 → 原版)", "مرحبا بالعالم")
    SampleRow("泰文(覆盖外 → 原版)", "สวัสดีครับ")
    SampleRow("希伯来文(覆盖外 → 原版)", "שלום עולם")
    SampleRow("天城文/印地语(覆盖外 → 原版)", "नमस्ते दुनिया")
    SampleRow("Emoji(大概率双方都缺 → 方框)", "😀 🎮 🚀 ✔ ★ ☂")
    SampleRow("混排单行", "中文 English العربية हिन्दी 한국어 สวัสดี")
}

/** 单个采样:灰标签 + 白色大样本 */
@Composable
private fun SampleRow(label: String, sample: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color(0xFF1E272C))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        BasicText(label, style = Style.EMPTY.withColor(Color(0xFF90A4AE)).toTextStyle())
        BasicText(sample, style = Style.EMPTY.withColor(Color.White).toTextStyle())
    }
}

/** 分区标题 + 内容 */
@Composable
private fun FallbackSection(title: String, content: @Composable () -> Unit) {
    BasicText(
        title,
        modifier = Modifier.padding(top = 14.dp),
        style = Style.EMPTY.withColor(Color(0xFFFFD54F)).toTextStyle(),
    )
    content()
}
