package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.PlatformSpanStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalDefaultFont
import moe.forpleuvoir.compose_minecraft.platform.ui.text.LocalDefaultFontSize
import moe.forpleuvoir.compose_minecraft.platform.ui.text.MinecraftCustomFonts
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

/**
 * 自定义字体测试:枚举系统字体目录 → 搜索过滤 → 注册为自定义字体 → LocalDefaultFont 切换渲染。
 * 布局:返回 + 搜索框 + 预览(置顶)+ 可滚动列表(简易滚动条,thumb 可拖动)。
 */
@Composable
internal fun FontDevScene() {
    val fonts = remember { MinecraftCustomFonts.listSystemFonts() }
    val searchState = rememberTextFieldState()
    val query = searchState.text.toString()
    val filtered = remember(fonts, query) {
        if (query.isBlank()) fonts
        else fonts.filter { it.fileName.contains(query, ignoreCase = true) }
    }
    var selectedId by remember { mutableStateOf<Identifier?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var selectedMetrics by remember { mutableStateOf<MinecraftCustomFonts.FontMetricsInfo?>(null) }
    var registerResult by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    var viewportH by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // ── 顶栏:返回 + 标题 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "← 返回",
                    style = TextStyle(fontSize = 18.sp, color = Color(0xFF64B5F6)),
                    modifier = Modifier
                        .background(Color(0xFF263238))
                        .clickable { ComposeScreen.closeCurrent() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                BasicText(
                    "  系统字体 (OS Fonts, T.32)",
                    style = TextStyle(fontSize = 20.sp, color = Color(0xFF80CBC4)),
                )
            }

            // ── 搜索框 ──
            BasicTextField(
                state = searchState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Color(0xFF263238))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                textStyle = Style.EMPTY.withColor(Color.White),
                cursorBrush = SolidColor(Color.White),
            )
            BasicText(
                "搜索 \"$query\" → ${filtered.size}/${fonts.size} 个 (${MinecraftCustomFonts.systemFontDir() ?: "未找到目录"})",
                style = TextStyle(fontSize = 14.sp, color = Color(0xFF78909C)),
                modifier = Modifier.padding(top = 2.dp),
            )

            // ── 预览(置顶)──
            BasicText(
                "默认字体: ABC 中文 123 @\$#",
                style = TextStyle(fontSize = 36.sp, color = Color.White),
                modifier = Modifier.padding(top = 6.dp),
            )
            if (selectedId != null) {
                selectedMetrics?.let { m ->
                    BasicText(
                        "度量: unitsPerEm=${m.unitsPerEm} ascender=${m.ascender} descender=${m.descender}" +
                            " → @9px 行高≈" + "%.1f".format(m.lineHeightPx9) + "px(MC 渲染行高固定 9px)",
                        style = TextStyle(fontSize = 12.sp, color = Color(0xFF78909C)),
                    )
                }
                BasicText(
                    "已选: $selectedName ($selectedId)",
                    style = TextStyle(fontSize = 16.sp, color = Color(0xFFFFD54F)),
                    modifier = Modifier.padding(top = 4.dp),
                )
                CompositionLocalProvider(LocalDefaultFont provides FontDescription.Resource(selectedId!!)) {
                    BasicText(
                        "普通: ABC 中文 123 @\$#",
                        style = TextStyle(fontSize = 36.sp, color = Color.White),
                    )
                    BasicText(
                        "加粗: Bold Text",
                        style = TextStyle(fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold),
                    )
                    BasicText(
                        "斜体: Italic Text",
                        style = TextStyle(fontSize = 24.sp, color = Color.White, fontStyle = FontStyle.Italic),
                    )
                    BasicText(
                        "下划线: Underline",
                        style = TextStyle(
                            fontSize = 24.sp,
                            color = Color.White,
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                    BasicText(
                        "删除线: Strikethrough",
                        style = TextStyle(
                            fontSize = 24.sp,
                            color = Color.White,
                            textDecoration = TextDecoration.LineThrough,
                        ),
                    )
                    BasicText(
                        "乱码: Obfuscated",
                        style = TextStyle(
                            fontSize = 24.sp,
                            color = Color.White,
                            platformStyle = PlatformTextStyle(PlatformSpanStyle(obfuscated = true), null),
                        ),
                    )
                    BasicText(
                        "阴影: Shadow",
                        style = TextStyle(
                            fontSize = 24.sp,
                            color = Color.White,
                            platformStyle = PlatformTextStyle(PlatformSpanStyle(shadowColor = Color(0xFFFF6B6B)), null),
                        ),
                    )
                    BasicText(
                        "彩色: Colored",
                        style = TextStyle(fontSize = 24.sp, color = Color(0xFFFFD54F)),
                    )
                }
                // ── LocalDefaultFontSize 演示:未显式 fontSize 的文本吃默认字号 Local ──
                CompositionLocalProvider(
                    LocalDefaultFontSize provides 30.sp,
                ) {
                    BasicText(
                        "LocalDefaultFontSize=30sp(未显式 fontSize)",
                        style = TextStyle(color = Color.White),
                    )
                }
                registerResult?.let {
                    BasicText(
                        "最近注册: $it",
                        style = TextStyle(fontSize = 16.sp, color = Color(0xFF64B5F6)),
                    )
                }
            }

            // ── 列表区 + 简易滚动条 ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp)
                    .onSizeChanged { viewportH = it.height.toFloat() }
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    for (f in filtered) {
                        val isSelected = selectedId == MinecraftCustomFonts.identifierFor(f.fileName)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .background(if (isSelected) Color(0xFF37474F) else Color(0xFF263238))
                                .clickable {
                                    val id = MinecraftCustomFonts.identifierFor(f.fileName)
                                    val ok = MinecraftCustomFonts.registerCustomFont(id, f.path)
                                    registerResult = "${f.fileName} → ${if (ok) "OK" else "失败"}"
                                    if (ok) {
                                        selectedId = id
                                        selectedName = f.fileName
                                        selectedMetrics = f.metrics
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            BasicText(
                                f.displayName ?: f.fileName,
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    color = if (isSelected) Color(0xFFFFD54F) else Color.White,
                                ),
                            )
                            BasicText(
                                "${f.fileName} · ${f.sizeBytes / 1024} KB",
                                style = TextStyle(fontSize = 12.sp, color = Color(0xFF78909C)),
                            )
                        }
                    }
                }

                // 简易滚动条:track + 可拖动 thumb(thumb 高度 = 可视比例;绝对抓取跟手)
                val contentH = scrollState.maxValue + viewportH
                if (viewportH > 0f && contentH > viewportH) {
                    val thumbH = (viewportH * viewportH / contentH).coerceAtLeast(28f)
                    val thumbTop = scrollState.value / contentH * viewportH
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(6.dp)
                            .padding(vertical = 4.dp)
                            .background(Color(0x33FFFFFF))
                            // pointerInput 挂在【不移动】的 track 上:局部坐标不受 thumb 位移污染
                            // (thumb Box 随滚动移动,局部坐标有反馈环 → 拖动阻尼,视觉 = 只走一半)
                            .pointerInput(scrollState, viewportH, thumbH) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    // 按下时记录抓取偏移(相对 thumb 顶部;点在 thumb 外则跳抓)
                                    val cH0 = scrollState.maxValue + viewportH
                                    val thumbTop0 = scrollState.value / cH0 * viewportH
                                    val grabOffset = (down.position.y - thumbTop0).coerceIn(0f, thumbH)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.positionChanged()) {
                                            // 实时读 maxValue,避免闭包旧值导致拖动比例失真
                                            val cH = scrollState.maxValue + viewportH
                                            val currentTop = scrollState.value / cH * viewportH
                                            val targetTop = (change.position.y - grabOffset)
                                                .coerceIn(0f, (viewportH - thumbH).coerceAtLeast(0f))
                                            // dispatchRawDelta 非 suspend,可脱离 restricted 作用域直发滚动 delta
                                            scrollState.dispatchRawDelta((targetTop - currentTop) / viewportH * cH)
                                            change.consume()
                                        }
                                        if (change.changedToUp()) break
                                    }
                                }
                            }
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .offset { IntOffset(0, thumbTop.roundToInt()) }
                                .width(6.dp)
                                .height(thumbH.dp)
                                .background(Color(0xAAFFFFFF))
                        )
                    }
                }
            }
        }
    }
}
