package moe.forpleuvoir.compose_minecraft.mixin;

import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.ComposeGuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 帧钩子(T.24 独立渲染工作流,用户拍板解除「无帧钩子 mixin」约定):
 * <p>
 * 注入点演进(每轮用户实测反馈驱动):
 * 1. 初版:GameRenderer.render() 的 guiRenderer.render()V 调用**之前** —— Compose
 *    先画、原版 GUI 后画盖住 Compose(用户:「渲染的位置不对,变成在屏幕之前渲染了」)。
 * 2. 二版:GuiRenderer.draw() 第二个 executeDrawRange(after-blur 段)**之前**
 *    —— 但该注入点**依赖原版 draws 非空**:ComposeScreen 打开时原版 GUI 内容
 *    实际只有菜单遮罩(after-blur 段),ComposeScreen.extractBackground 空实现
 *    移除遮罩后 draws 全空,draw() 直接 return,注入点不触发 → Compose 完全不
 *    渲染(用户:「直接啥都不渲染了」)。
 * 3. 三版:render() 的 draw() 调用点 AFTER —— draw() 调用指令无条件存在,
 *    **不依赖原版 draws 状态**,Compose 必渲染;但层级为原版 GUI 之后
 *    (最上层),F3 调试覆盖层(FPS 信息)被 Compose 盖住(用户:「渲染的位置
 *    太靠后了都比 fps信息渲染还靠后了」)。
 * 4. 四版:render() 的 draw() 调用点 BEFORE —— draw() 调用指令无条件存在,
 *    **不依赖原版 draws 状态**,Compose 必渲染;Compose 先画、原版随后画,
 *    F3 调试覆盖层(FPS 信息)画在 Compose 之上(满足「Compose 在 F3 之前」)。
 *    但原版 HUD/toasts 也画在 Compose 之上(用户实测:「HUD都渲染到屏幕上了」)。
 * 5. 现版(用户拍板「插入原版屏幕之后、F3 之前」):
 *    Compose 画在原版 draw() 的「before-blur 段(普通 GUI: HUD/screen/toast)
 *    之后、after-blur 段(F3 debug overlay)之前」—— 配合 {@link DebugOverlayMixin}
 *    使 F3 进入 after-blur 段,Compose 精确位于普通 GUI 与 F3 之间。
 *    原版 HUD/toast 提取逻辑完全不变、不做任何隐藏;F3 调试覆盖层保持最上。
 * <p>
 * Compose 屏关闭/未打开时 [ComposeGuiRenderer.getActive] 为 null,所有注入为 no-op,
 * 原版行为完全不变。
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    @Unique
    private boolean composeMinecraft$rendered = false;

    /** 每帧开始重置渲染标记。 */
    @Inject(method = "render", at = @At("HEAD"))
    private void composeMinecraft$resetRenderFlag(CallbackInfo ci) {
        this.composeMinecraft$rendered = false;
    }

    /**
     * 主注入点:在 after-blur 段(第二个 executeDrawRange)执行之前画 Compose。
     * 此时 before-blur 段(普通 GUI: HUD/screen/toast)已画完,after-blur 段(F3)尚未画,
     * 层级 = 普通 GUI < Compose < F3。
     * 仅当 {@link DebugOverlayMixin} 成功将 F3 排入 after-blur 段时触发(即 F3 开启时)。
     */
    @Inject(
        method = "draw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;II)V",
            ordinal = 1,
            shift = At.Shift.BEFORE
        )
    )
    private void composeMinecraft$renderBetweenSegments(CallbackInfo ci) {
        ComposeGuiRenderer active = ComposeGuiRenderer.getActive();
        if (active != null) {
            active.render();
            this.composeMinecraft$rendered = true;
        }
    }

    /**
     * 兜底:当 after-blur 段不存在(无 F3 或 F3 未开启)时,在 draw() 调用之后画 Compose。
     * 此时没有 F3 需要覆盖,Compose 画在最上层(普通 GUI 之上,若有则被盖住)。
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;draw()V",
            shift = At.Shift.AFTER
        )
    )
    private void composeMinecraft$renderFallback(CallbackInfo ci) {
        if (this.composeMinecraft$rendered) return;
        ComposeGuiRenderer active = ComposeGuiRenderer.getActive();
        if (active != null) {
            active.render();
        }
    }

    /**
     * 跳过 processBlurEffect(仅 Compose 屏打开时):
     * 因 {@link DebugOverlayMixin} 强制 F3 进入 after-blur 段,原版 draw() 的
     * after-blur 块会调用 processBlurEffect(真实模糊)。Compose 屏无需模糊背景,
     * 故跳过此调用,同时保留 after-blur 段的分段结构(clearDepthTexture 等正常执行)。
     * Compose 屏关闭时不影响原版模糊行为(如暂停菜单背景模糊)。
     */
    @Redirect(
        method = "draw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"
        )
    )
    private void composeMinecraft$skipBlurWhenComposeActive(GameRenderer gameRenderer) {
        if (ComposeGuiRenderer.getActive() == null) {
            gameRenderer.processBlurEffect();
        }
    }
}