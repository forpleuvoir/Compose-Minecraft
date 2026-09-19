package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.ui.text.toTextStyle
import moe.forpleuvoir.compose_minecraft.platform.ui.text.withColor
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import java.io.IOException

/**
 * 图片管线测试屏幕:
 *
 * 验证 `MinecraftImageBitmap`(CPU 像素)→ GpuTexture 上传 → 带 UV 的
 * `BlitRenderState` 回放全链路:
 * - 程序生成位图(棋盘格/渐变/半透明)绘制与缩放;
 * - MC 内置资源 PNG 解码(`createImageBitmap`/`decodeToImageBitmap`);
 * - `drawImage`(src/dst 重载)的 src 子矩形 / dst 缩放 / alpha 调制;
 * - 旋转 / graphicsLayer 缩放+alpha / clipRect 裁剪 与图片的组合。
 *
 * 观察要点:棋盘格清晰无错位(UV 映射)、渐变颜色正确(R/B 未交换)、
 * 半透明图片能透出背景色、旋转/裁剪与纯色图形行为一致。
 */
@Composable
fun ImageDevScene() {
    val checker64 = remember { checkerBitmap(64, 8) }
    val gradient = remember { gradientBitmap(128, 32) }
    val alphaImg = remember { alphaBitmap(64) }
    val mcGrass = remember { loadResourceBitmap() }
    val chatGpt = remember { loadChatGptBitmap() }
    val pixelRed = remember { pixelBitmap(0xFFFF0000.toInt()) }
    val pixelWhite = remember { pixelBitmap(0xFFFFFFFF.toInt()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            BasicText(
                "Image Pipeline Test(图片管线)",
                style = Style.EMPTY.withColor(Color(0xFF1A1A1A)).withBold(true).toTextStyle(),
            )
            BasicText(
                "棋盘格/渐变/半透明为程序生成位图;草方块来自 MC 资源 PNG 解码",
                style = Style.EMPTY.withColor(Color(0xFF546E7A)).toTextStyle(),
            )

            // ── 棋盘格:原样 / 放大 2x / 缩小 0.5x(验证 UV 与缩放)──
            Canvas(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                // 1x1 参考:纯红/纯白放大 64x64 —— 若显示纯色则纹理管线正常,
                // 若黑/透明则是上传或采样问题
                drawImage(
                    pixelRed,
                    dstOffset = IntOffset(10, 10),
                    dstSize = IntSize(64, 64),
                )
                drawImage(
                    pixelWhite,
                    dstOffset = IntOffset(84, 10),
                    dstSize = IntSize(64, 64),
                )
                drawImage(
                    checker64,
                    dstOffset = IntOffset(160, 10),
                    dstSize = IntSize(64, 64),
                )
                drawImage(
                    checker64,
                    dstOffset = IntOffset(240, 10),
                    dstSize = IntSize(128, 128),
                )
                drawImage(
                    checker64,
                    dstOffset = IntOffset(390, 20),
                    dstSize = IntSize(32, 32),
                )
            }
            BasicText(
                "① 1x1 红/白放大参考(左二);棋盘 64px:原样 / 放大 2x / 缩小 0.5x",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── 渐变:原样 / 纵向拉伸(颜色通道与采样)──
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                drawImage(
                    gradient,
                    dstOffset = IntOffset(10, 20),
                    dstSize = IntSize(128, 32),
                )
                drawImage(
                    gradient,
                    dstOffset = IntOffset(160, 10),
                    dstSize = IntSize(64, 80),
                )
            }
            BasicText(
                "② 渐变 128x32:原样(左)/ 纵向拉伸 64x80(右)",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── src 子矩形:只取棋盘左上 1/4(32x32)──
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(90.dp)
            ) {
                drawRect(color = Color(0xFF263238))
                drawImage(
                    checker64,
                    srcOffset = IntOffset(0, 0),
                    srcSize = IntSize(32, 32),
                    dstOffset = IntOffset(10, 10),
                    dstSize = IntSize(64, 64),
                )
            }
            BasicText(
                "③ src 子矩形:棋盘左上 1/4 放大到 64px",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── 半透明位图:叠加在色块上(验证 alpha 通道合成)──
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(100.dp)
            ) {
                drawRect(color = Color(0xFF1565C0), topLeft = Offset(10f, 10f), size = Size(80f, 80f))
                drawRect(color = Color(0xFFEF6C00), topLeft = Offset(100f, 10f), size = Size(80f, 80f))
                drawImage(
                    alphaImg,
                    dstOffset = IntOffset(10, 10),
                    dstSize = IntSize(80, 80),
                )
                drawImage(
                    alphaImg,
                    dstOffset = IntOffset(100, 10),
                    dstSize = IntSize(80, 80),
                )
            }
            BasicText(
                "④ 半透明径向渐变图叠在蓝/橙块上(边缘透明、中心不透明)",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── MC 资源 PNG 解码:原样 / 放大 / 缩小 ──
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                val img = mcGrass
                if (img != null) {
                    drawImage(
                        img,
                        dstOffset = IntOffset(10, 10),
                        dstSize = IntSize(img.width, img.height),
                    )
                    drawImage(
                        img,
                        dstOffset = IntOffset(20 + img.width, 5),
                        dstSize = IntSize(img.width * 3, img.height * 3),
                    )
                    drawImage(
                        img,
                        dstOffset = IntOffset(30 + img.width * 4, 30),
                        dstSize = IntSize(img.width / 2, img.height / 2),
                    )
                } else {
                    drawRect(
                        color = Color(0xFFB71C1C),
                        topLeft = Offset(10f, 10f),
                        size = Size(200f, 40f),
                    )
                }
            }
            BasicText(
                if (mcGrass != null)
                    "⑤ MC 资源 grass_block_side.png:原样 / 放大 3x / 缩小 0.5x(解码 PNG)"
                else
                    "⑤ 资源读取失败:检查 minecraft:textures/block/grass_block_side.png",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── alpha 调制:同图 4 档透明度 ──
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(90.dp)
            ) {
                listOf(1f, 0.7f, 0.4f, 0.15f).forEachIndexed { i, a ->
                    drawImage(
                        checker64,
                        dstOffset = IntOffset(10 + i * 70, 15),
                        dstSize = IntSize(56, 56),
                        alpha = a,
                    )
                }
            }
            BasicText(
                "⑥ alpha 调制:1.0 / 0.7 / 0.4 / 0.15",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── 采样对比:棋盘放大 4x,双线性 vs 最近邻 ──
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                drawImage(
                    checker64,
                    dstOffset = IntOffset(10, 5),
                    dstSize = IntSize(128, 128),
                    filterQuality = FilterQuality.Low,
                )
                drawImage(
                    checker64,
                    dstOffset = IntOffset(150, 5),
                    dstSize = IntSize(128, 128),
                    filterQuality = FilterQuality.None,
                )
            }
            BasicText(
                "⑦ 采样对比(棋盘放大 4x):左 LOW 双线性 / 右 None 最近邻",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── 采样对比:资源图放大 3x,双线性 vs 最近邻 ──
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                val img = mcGrass
                if (img != null) {
                    drawImage(
                        img,
                        dstOffset = IntOffset(10, 10),
                        dstSize = IntSize(img.width * 3, img.height * 3),
                        filterQuality = FilterQuality.Low,
                    )
                    drawImage(
                        img,
                        dstOffset = IntOffset(30 + img.width * 3, 10),
                        dstSize = IntSize(img.width * 3, img.height * 3),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            BasicText(
                "⑧ 采样对比(资源图放大 3x):左 LOW 双线性 / 右 None 最近邻",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── ChatGPT 测试图(devOnly 资源,classpath 解码)──
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                val img = chatGpt
                if (img != null) {
                    val scale = 200f / img.width
                    val w = img.width * scale
                    val h = img.height * scale
                    drawImage(
                        img,
                        dstOffset = IntOffset(10, 10),
                        dstSize = IntSize(w.toInt(), h.toInt()),
                    )
                    // 局部放大:取中心区域,固定输出宽 160px,对比采样
                    val cw = img.width / 3
                    val ch = img.height / 3
                    val dw = 160
                    val dh = ch * dw / cw
                    drawImage(
                        img,
                        srcOffset = IntOffset((img.width - cw) / 2, (img.height - ch) / 2),
                        srcSize = IntSize(cw, ch),
                        dstOffset = IntOffset(230, 10),
                        dstSize = IntSize(dw, dh),
                        filterQuality = FilterQuality.Low,
                    )
                    drawImage(
                        img,
                        srcOffset = IntOffset((img.width - cw) / 2, (img.height - ch) / 2),
                        srcSize = IntSize(cw, ch),
                        dstOffset = IntOffset(230 + dw + 16, 10),
                        dstSize = IntSize(dw, dh),
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    drawRect(
                        color = Color(0xFFB71C1C),
                        topLeft = Offset(10f, 10f),
                        size = Size(300f, 40f),
                    )
                }
            }
            BasicText(
                if (chatGpt != null)
                    "⑨ ChatGPT 测试图(devOnly 资源):等比缩放 + 中心 3x 放大,右为最近邻"
                else
                    "⑨ devOnly 资源读取失败:检查 ChatGPT Image 2026年8月2日 10_07_59.png",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── 旋转 45°:图片与纯色块同一变换语义 ──
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                rotate(degrees = 45f, pivot = Offset(90f, 60f)) {
                    drawImage(
                        checker64,
                        dstOffset = IntOffset(58, 28),
                        dstSize = IntSize(64, 64),
                    )
                }
                drawRect(
                    color = Color(0xFFFDD835),
                    topLeft = Offset(170f, 20f),
                    size = Size(64f, 64f),
                )
                rotate(degrees = -30f, pivot = Offset(260f, 60f)) {
                    val img = mcGrass
                    if (img != null) {
                        drawImage(
                            img,
                            dstOffset = IntOffset(228, 28),
                            dstSize = IntSize(img.width * 2, img.height * 2),
                        )
                    }
                }
            }
            BasicText(
                "⑩ 旋转:棋盘 45°(左)/ 资源图 -30°(右),黄色方块为旋转参考",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )

            // ── graphicsLayer:缩放 + alpha + 裁剪组合 ──
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                Canvas(
                    Modifier
                        .padding(start = 10.dp, top = 15.dp)
                        .size(110.dp)
                        .graphicsLayer {
                            scaleX = 1.6f
                            scaleY = 1.6f
                            alpha = 0.8f
                        }
                ) {
                    drawImage(
                        checker64,
                        dstOffset = IntOffset(0, 0),
                        dstSize = IntSize(64, 64),
                    )
                }
                // 裁剪:图片超出裁剪矩形部分被裁掉
                Canvas(Modifier.padding(start = 160.dp, top = 15.dp).size(90.dp)) {
                    clipRect(left = 0f, top = 0f, right = 60f, bottom = 50f) {
                        drawImage(
                            gradient,
                            dstOffset = IntOffset(0, 0),
                            dstSize = IntSize(128, 32),
                        )
                        drawRect(
                            color = Color(0xFFFFAB40),
                            topLeft = Offset(0f, 35f),
                            size = Size(90f, 10f),
                        )
                    }
                }
            }
            BasicText(
                "⑪ graphicsLayer:棋盘 scale 1.6 + alpha 0.8(左)/ clipRect 裁剪 60x50(右)",
                style = Style.EMPTY.withColor(Color(0xFF455A64)).toTextStyle(),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 程序生成位图(CPU 像素 0xAARRGGBB,不经解码,直接验证上传/UV/采样)
// ─────────────────────────────────────────────────────────────────────────────

/** 黑白棋盘格:[size]px,每边 [cells] 格(纯黑/纯白,便于在浅色背景观察) */
private fun checkerBitmap(size: Int, cells: Int): ImageBitmap {
    val buffer = IntArray(size * size)
    val cell = size / cells
    for (y in 0 until size) {
        for (x in 0 until size) {
            val dark = ((x / cell) + (y / cell)) % 2 == 0
            val v = if (dark) 0 else 0xFF
            buffer[y * size + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
    }
    return ImageBitmap(size, size, buffer)
}

/** 渐变:横向 R、纵向 G 线性变化(验证颜色通道无 R/B 交换) */
private fun gradientBitmap(width: Int, height: Int): ImageBitmap {
    val buffer = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val r = x * 255 / (width - 1)
            val g = y * 255 / (height - 1)
            buffer[y * width + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8)
        }
    }
    return ImageBitmap(width, height, buffer)
}

/** 半透明径向渐变:中心不透明红,边缘全透明(验证 alpha 通道) */
private fun alphaBitmap(size: Int): ImageBitmap {
    val buffer = IntArray(size * size)
    val c = (size - 1) / 2f
    val rMax = c
    for (y in 0 until size) {
        for (x in 0 until size) {
            val d = kotlin.math.sqrt((x - c) * (x - c) + (y - c) * (y - c))
            val a = (255 * (1f - (d / rMax).coerceIn(0f, 1f))).toInt()
            buffer[y * size + x] = (a shl 24) or (0xE5 shl 16) or (0x30 shl 8) or 0x5B
        }
    }
    return ImageBitmap(size, size, buffer)
}

/** 1x1 纯色像素图(管线参考:放大绘制仍为纯色) */
private fun pixelBitmap(color: Int): ImageBitmap =
    ImageBitmap(1, 1, intArrayOf(color))

/** 读取 MC 内置资源 PNG 并解码(验证 createImageBitmap 解码管线) */
private fun loadResourceBitmap(): ImageBitmap? = try {
    val location = Identifier.parse("minecraft:textures/block/grass_block_side.png")
    val resource = mc.resourceManager.getResourceOrThrow(location)
    resource.open().use { it.readBytes() }.decodeToImageBitmap()
} catch (e: IOException) {
    null
} catch (e: RuntimeException) {
    null
}

/**
 * 读取 devOnly 资源(common/src/devOnly/resources/)中的测试图并解码。
 * devOnly 资源随 dev run classpath 分发(不进发布 jar),这里经类加载器按文件名读取;
 * 文件名含空格/中文,ClassLoader.getResource 按路径字符串处理,无需 URL 编码。
 */
private object DevResourceAnchor

private fun loadChatGptBitmap(): ImageBitmap? = try {
    val stream = DevResourceAnchor::class.java.classLoader
        .getResourceAsStream("ChatGPT Image 2026年8月2日 10_07_59.png")
        ?: return null
    stream.use { it.readBytes() }.decodeToImageBitmap()
} catch (_: Exception) {
    null
}
