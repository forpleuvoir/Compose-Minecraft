package moe.forpleuvoir.compose_minecraft.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import java.util.List;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 FontSet 的 allProviders(private 字段)—— 用于自定义字体注册时把
 * 默认字体的 provider 链合并进自定义 FontSet,实现缺字符回退
 * (自定义字体缺字 → 默认字体字形,而非 missing 方块)。T.32。
 */
@Mixin(FontSet.class)
public interface FontSetAccessor {
    @Accessor("allProviders")
    List<GlyphProvider.Conditional> allProviders();
}
