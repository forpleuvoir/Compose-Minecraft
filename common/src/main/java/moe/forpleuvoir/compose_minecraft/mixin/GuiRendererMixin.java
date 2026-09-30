package moe.forpleuvoir.compose_minecraft.mixin;

import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.ComposeGuiRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 帧钩子:把 Compose 场景插进原版 draw() 的「before-blur 段(普通 GUI:HUD/screen/toast)
 * 之后、after-blur 段(F3 debug overlay)之前」,层级 = 普通 GUI &lt; Compose &lt; F3。
 * <p>
 * 落点选择:两个 executeDrawRange 之间**依赖原版 draws 非空**(before-blur 段有内容才会
 * 走到那里;ComposeScreen 不画原版背景,原版 GUI 只剩菜单遮罩,遮罩一去掉
 * draws 可能全空 → draw() 直接 return,注入点不触发)。故此处只负责 F3 开启时的精确层级,
 * 配合 {@link DebugOverlayMixin} 把 F3 排进 after-blur 段。
 * <p>
 * 原版 HUD/toast 提取逻辑完全不变、不做任何隐藏;F3 调试覆盖层保持最上。
 * <p>
 * 背景模糊:Compose 屏经 {@link ComposeGuiRenderer#backgroundBlurRadius} 请求模糊时,
 * 在提交 Compose 内容之前执行一次原版 blur 后处理
 * ({@link GameRenderer#processBlurEffect()})——原版 draw() 已调用过的沿用其位置,
 * 原版不会调用的(无 after-blur 段 draw)在这里补做。
 * <p>
 * Compose 屏关闭/未打开时 {@link ComposeGuiRenderer#getActive} 为 null,所有注入为 no-op,
 * 原版行为完全不变。
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    @Unique
    private boolean composeMinecraft$rendered = false;

    /** 本帧是否已执行过 blur 后处理(每帧只允许执行一次)。 */
    @Unique
    private boolean composeMinecraft$blurred = false;

    /** 每帧开始重置渲染标记与模糊标记。 */
    @Inject(method = "render", at = @At("HEAD"))
    private void composeMinecraft$resetRenderFlag(CallbackInfo ci) {
        this.composeMinecraft$rendered = false;
        this.composeMinecraft$blurred = false;
    }

    /**
     * 主注入点:在 after-blur 段(第二个 executeDrawRange)执行之前画 Compose。
     * 此时 before-blur 段(普通 GUI: HUD/screen/toast)已画完,after-blur 段(F3)尚未画,
     * 层级 = 普通 GUI < Compose < F3。
     * 仅当 {@link DebugOverlayMixin} 成功将 F3 排入 after-blur 段时触发(即 F3 开启时)。
     * <p>
     * 存疑(未修复):注入点用 ordinal = 1 + {@code At.Shift.BEFORE} 定位到第二个
     * executeDrawRange 之前,原版指令序变化时可能失配(IDE 亦标记 brittle)。
     * 当前行为正常,复现"Compose 层级错位 / 完全不渲染"时优先看此处。
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
     * <p>
     * 背景模糊补做:原版只在存在 after-blur 段 draw 时调用 processBlurEffect
     * (draw() 内 {@code draws.size() > firstDrawIndexAfterBlur} 分支),而 Compose 屏的
     * 背景模糊不产生 after-blur 段 draw(draws 可能为空)——此处按原版位置补一次,
     * 顺序为 before-blur 段(世界 / HUD / 普通 GUI)已入主渲染目标 → 模糊 → 提交 Compose 内容。
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
        if (active == null) return;
        if (!this.composeMinecraft$blurred && active.backgroundBlurRadius != null) {
            Minecraft.getInstance().gameRenderer.processBlurEffect();
        }
        active.render();
    }

    /**
     * processBlurEffect 的条件执行:Compose 屏未打开时完全按原版(调用);
     * Compose 屏请求了背景模糊({@link ComposeGuiRenderer#backgroundBlurRadius} 非 null)时
     * 按原版位置调用;未请求时跳过该调用,同时保留 after-blur 段的分段结构
     * (clearDepthTexture 等正常执行)。
     */
    @Redirect(
        method = "draw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"
        )
    )
    private void composeMinecraft$blurForComposeScreen(GameRenderer gameRenderer) {
        ComposeGuiRenderer active = ComposeGuiRenderer.getActive();
        if (active == null || active.backgroundBlurRadius != null) {
            gameRenderer.processBlurEffect();
            this.composeMinecraft$blurred = true;
        }
    }
}
