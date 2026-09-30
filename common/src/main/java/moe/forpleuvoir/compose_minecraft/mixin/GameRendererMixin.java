package moe.forpleuvoir.compose_minecraft.mixin;

import moe.forpleuvoir.compose_minecraft.platform.render.pipeline.ComposeGuiRenderer;
import moe.forpleuvoir.compose_minecraft.platform.screen.WorldBackdrop;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 世界渲染开关:ComposeScreen 打开且无人借用世界背景时,取消本帧的世界渲染。
 * <p>
 * 判定与状态维护都在 {@link WorldBackdrop}:
 * 引用计数 > 0(屏级 / 动画级 / 组合级借用中)→ 正常渲染;为 0 且当前屏是
 * ComposeScreen → 跳过。
 * <p>
 * 注入点选择:26.2 的 {@code GameRenderer.renderLevel(DeltaTracker)} 只有两个调用点
 * ——{@code GameRenderer.render(...)} 与 {@code Minecraft.grabPanoramixScreenshot(...)};
 * 后者不在 Compose 屏场景,而判定条件里已限定"当前屏是 ComposeScreen",因此全景截图等
 * 原版路径不受影响。26.2 已不存在 {@code shouldRenderLevel} 局部量,不能再像旧实现那样
 * 用 ModifyVariable 改标志位。
 * <p>
 * 已知代价:本注入在 HEAD 且可取消,会一并跳过 {@code renderLevel} 内部的雾 / 投影 /
 * 深度等设置(Compose 屏的 GUI 走自己的正交投影与清屏),也会连带跳过其他模组对
 * {@code renderLevel} 的注入。
 * <p>
 * 背景模糊半径:Compose 屏请求了具体半径时(见
 * {@link ComposeGuiRenderer#backgroundBlurRadius}),替换写入 Globals UBO 的
 * {@code MenuBlurRadius}——该 uniform 只被 box_blur 后处理读取(模糊半径)。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void composeMinecraft$skipLevelRender(CallbackInfo ci) {
        if (WorldBackdrop.shouldSkipLevelRender()) {
            ci.cancel();
        }
    }

    /**
     * Globals UBO 的 {@code MenuBlurRadius} 取值替换:Compose 屏请求了背景模糊
     * ({@link ComposeGuiRenderer#backgroundBlurRadius} 非 null)时用其半径,
     * 否则用原版「菜单背景模糊度」({@link OptionsRenderState#menuBackgroundBlurriness})。
     */
    @Redirect(
        method = "render",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;menuBackgroundBlurriness:I",
            // 180 = Opcodes.GETFIELD(该字段在 render 内只被读取一次;编译类路径无 ASM)
            opcode = 180
        )
    )
    private int composeMinecraft$menuBlurRadius(OptionsRenderState optionsState) {
        ComposeGuiRenderer active = ComposeGuiRenderer.getActive();
        Integer radius = active == null ? null : active.backgroundBlurRadius;
        return radius != null ? radius : optionsState.menuBackgroundBlurriness;
    }
}
