package moe.forpleuvoir.compose_minecraft.mixin;

import moe.forpleuvoir.compose_minecraft.ComposeWarmup;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    private volatile boolean running;


    @Inject(method = "run", at = @At("HEAD"))
    public void runStarting(CallbackInfo ci) {
        ComposeWarmup.INSTANCE.schedule();
    }

}
