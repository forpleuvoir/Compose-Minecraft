package moe.forpleuvoir.compose_minecraft.mixin;

import java.util.Map;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 FontManager.fontSets(private final Map),供自定义字体注册
 * 运行时注入新字体集。accessor mixin,非反射。
 */
@Mixin(FontManager.class)
public interface FontManagerAccessor {
    @Accessor("fontSets")
    Map<Identifier, FontSet> fontSets();
}
