package moe.forpleuvoir.compose_minecraft.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 Minecraft.fontManager(private final 字段,无公共 getter),
 * 供自定义字体注册(T.32)访问 FontManager。accessor mixin,非反射。
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("fontManager")
    FontManager fontManager();
}
