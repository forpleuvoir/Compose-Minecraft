package moe.forpleuvoir.compose_minecraft.mixin;

import moe.forpleuvoir.compose_minecraft.platform.render.ComposeGuiRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 仅调整 F3 debug overlay 的渲染分段(不碰 HUD/toast 提取)。
 * <p>
 * Compose 屏打开时,在 F3 debug overlay(nextStratum)提取后调用
 * {@link GuiGraphicsExtractor#blurBeforeThisStratum()},使 F3 debug overlay
 * 所在的 stratum 归入 after-blur 段。对应的 prepare() 将 F3 的 draws 排入
 * 第二段(after-blur),原版 draw() 会先执行 before-blur 段(普通 GUI: HUD/
 * screen/toast),再执行 after-blur 段(F3)。Compose 画在两段之间,
 * 从而精确实现「原版普通 GUI < Compose < F3」层级。
 * <p>
 * 本注入不隐藏任何 HUD/toast 元素,不改变原版提取逻辑,
 * 仅调整 F3 在渲染管线中的分段位置。
 * <p>
 * Compose 屏关闭时 [ComposeGuiRenderer.getActive] 为 null,本注入为 no-op。
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugOverlayMixin {

    @Inject(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;nextStratum()V",
            shift = At.Shift.AFTER
        )
    )
    private void composeMinecraft$blurAfterDebugStratum(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (ComposeGuiRenderer.getActive() != null) {
            try {
                // 将 F3 所在 stratum 标记为 after-blur 段边界,
                // 使 F3 进入 after-blur 段,普通 GUI 留在 before-blur 段。
                graphics.blurBeforeThisStratum();
            } catch (IllegalStateException ignored) {
                // 每帧只允许 blur 一次,若已 blur 则忽略
            }
        }
    }
}