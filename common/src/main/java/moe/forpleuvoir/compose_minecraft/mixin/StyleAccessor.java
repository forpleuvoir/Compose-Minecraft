/*
 * Compose Minecraft Platform Mod — Style Mixin(只读暴露私有字段)
 * Licensed under the Apache License, Version 2.0 (see LICENSE / NOTICE).
 */

package moe.forpleuvoir.compose_minecraft.mixin;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 平台适配点:MC {@link Style} 私有字段的只读访问器接口,由 Mixin 生成 accessor 实现。
 * Kotlin 侧经 `(this as StyleMixin)` 调用(见 `StyleExtensions.kt`)。
 *
 * <p><b>只覆盖 MC public getter 能力不足的字段(6 个):</b>
 * <ul>
 *   <li>5 个布尔三态字段(bold/italic/underlined/strikethrough/obfuscated):MC public
 *       getter `isBold()` 等把 {@code Boolean?} 归一化为 {@code boolean}(`this.bold == TRUE`),
 *       丢失"未设置(null)"状态。文本度量/渲染对齐时需要区分"未设置"与"显式 false",
 *       故经 Mixin 暴露原始三态。</li>
 *   <li>{@code font}:MC `getFont()` 在字段为 null 时返回 {@link FontDescription#DEFAULT}
 *       (默认化),丢失"未设置"。本接口暴露原始 nullable。</li>
 * </ul>
 *
 * <p><b>不需要 Mixin 的字段(5 个 — MC 原生 public getter 已保留 null 语义):</b>
 * {@code color}(getColor())、{@code shadowColor}(getShadowColor())、
 * {@code clickEvent}(getClickEvent())、{@code hoverEvent}(getHoverEvent())、
 * {@code insertion}(getInsertion()),Kotlin 中直接以属性访问即可。
 *
 * <p>Style 不可变(11 个字段全 {@code private final}),本接口只读,不提供 setter。
 */
@Mixin(Style.class)
public interface StyleAccessor {

    /** 加粗三态(null = 未设置,false = 显式不加粗)。 */
    @Accessor("bold")
    @Nullable Boolean bold();

    /** 斜体三态(null = 未设置)。 */
    @Accessor("italic")
    @Nullable Boolean italic();

    /** 下划线三态(null = 未设置)。 */
    @Accessor("underlined")
    @Nullable Boolean underlined();

    /** 删除线三态(null = 未设置)。 */
    @Accessor("strikethrough")
    @Nullable Boolean strikethrough();

    /** 乱码三态(null = 未设置)。 */
    @Accessor("obfuscated")
    @Nullable Boolean obfuscated();

    /** 字体描述(原始 nullable;null = 未设置;区别于 getFont() 的 DEFAULT 默认化)。 */
    @Accessor("font")
    @Nullable FontDescription font();
}