package moe.forpleuvoir.compose_minecraft.mixin;

import moe.forpleuvoir.compose_minecraft.platform.render.ComposeGuiRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 帧钩子(T.24 独立渲染工作流,用户拍板解除「无帧钩子 mixin」约定):
 * 注入 {@link GuiRenderer#render} 中 draw() 调用**之后**(shift=AFTER),提交
 * Compose 屏的独立渲染器内容 —— 即原版 GUI(遮罩/HUD/toasts)全部画完后,
 * Compose 内容画在最上层。
 * <p>
 * 修复记录(注入点演进,每轮用户实测反馈驱动):
 * 1. 初版:GameRenderer.render() 的 guiRenderer.render()V 调用**之前** → Compose
 *    先画、原版 GUI 后画盖住 Compose(用户:「渲染的位置不对,变成在屏幕之前渲染了」)。
 * 2. 二版:GuiRenderer.draw() 第二个 executeDrawRange(after-blur 段)**之前**
 *    (层级 = before-blur < Compose < HUD)—— 但该注入点**依赖原版 draws 非空**:
 *    ComposeScreen 打开时原版 GUI 内容实际只有菜单遮罩(after-blur 段),
 *    ComposeScreen.extractBackground 空实现移除遮罩后 draws 全空,draw() 直接
 *    return,注入点不触发 → Compose 完全不渲染(用户:「直接啥都不渲染了」)。
 * 3. 现版:render() 的 draw() 调用点 AFTER —— draw() 调用指令无条件存在,
 *    **不依赖原版 draws 状态**;Compose 画在原版 GUI 之后(最上层)。
 * <p>
 * 层级说明:ComposeScreen 已空实现 extractBackground(不渲染原版菜单遮罩),
 * Compose 内容自绘背景;实测 ComposeScreen 打开时原版 HUD 不产生元素,
 * 故「最上层」即「唯一 GUI 层」。若未来业务让 HUD 渲染,Compose 会盖在其上
 * (已知限制,可再调整注入点)。
 * <p>
 * Compose 屏关闭/未打开时 [ComposeGuiRenderer.getActive] 为 null,此注入为 no-op,
 * 原版行为完全不变。
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;draw()V",
            shift = At.Shift.AFTER
        )
    )
    private void composeMinecraft$renderComposeGui(CallbackInfo ci) {
        ComposeGuiRenderer active = ComposeGuiRenderer.getActive();
        if (active != null) {
            active.render();
        }
    }
}
