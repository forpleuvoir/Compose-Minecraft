package moe.forpleuvoir.compose_minecraft.mixin;

import moe.forpleuvoir.compose_minecraft.platform.render.GuiShadowRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 平台适配点(T.14 阴影):GuirEndState 按 sortKey 排序元素,自定义纹理
 * pipeline 的 sortKey 大于 MC 静态 pipeline,阴影元素会排在内容之后绘制
 * ("阴影盖内容")。此 Mixin 在 {@link GuiRenderState#sortElements} 排序完成后,
 * 把 {@link GuiShadowRenderState} 类型的阴影元素移动到列表**最前**(最先绘制 =
 * 最底层),内容后画自然盖住阴影重叠部分 —— 等价于 Skia setShadowLayer 的
 * "先画阴影、后画内容"语义,阴影可完整绘制而无需几何裁剪。
 *
 * ⚠ 已知风险(待渲染链路拆分后处理):
 * 1. 置底只在**节点内**生效:阴影元素与内容相交被 findAppropriateNode `up()`
 *    到上层节点时,排序不跨节点,阴影仍可能盖内容(当前靠"阴影命令先记录"
 *    规避,脆弱);
 * 2. @Redirect 硬编码 lambda$sortElements$0 内部结构,MC 升级/混淆后可能
 *    静默失效(阴影盖内容回归且无报错);
 * 3. 不保证"全场景最底":阴影所在节点晚于其他内容绘制时,阴影画在其上
 *    (符合物理,但非"永远在一切之下")。
 */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {

    // ── 阴影置底排序 ──

    @Redirect(
        method = "lambda$sortElements$0",
        at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V")
    )
    private static void sortShadowFirst(List<GuiElementRenderState> list, Comparator<GuiElementRenderState> c) {
        list.sort(c);
        // 阴影元素移到最前(保持阴影之间、非阴影之间的相对顺序稳定)。
        // 注意不能用"移除后插头并重置扫描下标"的写法:头部连续阴影时
        // remove(0)+addFirst 不改变列表,i 重置导致死循环(渲染线程卡死)。
        List<GuiElementRenderState> shadows = new ArrayList<>();
        List<GuiElementRenderState> rest = new ArrayList<>(list.size());
        for (GuiElementRenderState e : list) {
            if (e instanceof GuiShadowRenderState) {
                shadows.add(e);
            } else {
                rest.add(e);
            }
        }
        list.clear();
        list.addAll(shadows);
        list.addAll(rest);
    }
}
