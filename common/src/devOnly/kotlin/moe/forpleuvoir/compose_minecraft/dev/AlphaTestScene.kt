package moe.forpleuvoir.compose_minecraft.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import moe.forpleuvoir.compose_minecraft.mc
import moe.forpleuvoir.compose_minecraft.platform.screen.ComposeScreen
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.minecraftItem
import moe.forpleuvoir.compose_minecraft.platform.ui.draw.minecraftTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

@Composable
fun AlphaTestScene() {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x00121212))
            .graphicsLayer { alpha = 0.75f }
            .clickable { if (!ComposeScreen.closeCurrent()) ComposeScreen.open { MinecraftDevSceneContent() } }
    ) {
        // 单个纹理 + graphicsLayer alpha
        Box(
            Modifier
                .size(100.dp)
                .minecraftTexture(Identifier.parse("minecraft:textures/block/dirt.png"))
        )
        Box(
            Modifier
                .size(100.dp)
                .background(Color(0xFF66CCFF))
        )
        Box(
            Modifier
                .size(100.dp)
                .minecraftItem(mc.player?.mainHandItem?:ItemStack(Items.DIAMOND))
        )
    }
}