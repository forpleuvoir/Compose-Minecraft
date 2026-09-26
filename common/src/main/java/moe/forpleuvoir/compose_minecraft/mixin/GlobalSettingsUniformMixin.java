package moe.forpleuvoir.compose_minecraft.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 构造完成即发布 Globals UBO。
 * <p>
 * 原版只在 {@link GlobalSettingsUniform#update} 里调用
 * {@code RenderSystem.setGlobalSettingsUniform(buffer)},而该方法由
 * {@code GameRenderer.render} 每帧调用 —— 也就是**第一帧渲染之前**
 * {@code RenderSystem.getGlobalSettingsUniform()} 恒为 null,而
 * {@code RenderSystem.bindDefaultUniforms} 取到 null 时会整体跳过 Globals,
 * 使这段时间内创建的 render pass 全部缺 Globals。
 * <p>
 * 启动阶段会走的原版路径是图集动画上传({@code TextureAtlas.uploadAnimationFrames},
 * 按动画计时触发,可能早于第一帧;启动越慢越容易命中)。校验模式下
 * ({@code GlRenderPass.VALIDATION = SharedConstants.IS_RUNNING_IN_IDE})会直接抛
 * {@code IllegalStateException: Missing uniform Globals (should be UNIFORM_BUFFER)}。
 * <p>
 * buffer 在构造期已创建,故此处提前发布即可让 Globals 自始可用;每帧 update 仍照常
 * 覆盖其内容。{@code getGlobalSettingsUniform()} 的唯一消费者就是
 * {@code bindDefaultUniforms},提前发布无其他副作用。
 */
@Mixin(GlobalSettingsUniform.class)
public abstract class GlobalSettingsUniformMixin {

    @Shadow
    @Final
    private GpuBuffer buffer;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void composeMinecraft$publishGlobalSettingsUniform(CallbackInfo ci) {
        RenderSystem.setGlobalSettingsUniform(this.buffer);
    }
}
