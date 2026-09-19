#version 330

// Compose-Minecraft 片元着色器:带纹理的混合模式,按透明度把源色朝「单位元」插值。
//
// 与 MC 自带 core/position_tex_color 的唯一差异:输出前多一步 rgb 插值 ——
//
//     color.rgb = mix(vec3(1.0), color.rgb, vertexColor.a);
//
// 用途:乘 / 取暗族(Modulate / Darken)的单位元是白(1)。纯色绘制在 CPU 侧完成这一步
// (BlendPipelines.fadeColor 的朝白插值),而纹理绘制的源色是逐像素的,只能用着色器做;
// 做完后 α→0 时 min(·) / 乘(·) 的结果回到背景色,而不是把画面乘暗成黑块 ——
// 即把「图层按 alpha 合成回背景」这一步补上。
//
// 其余族的插值不需要逐像素处理(加性 / 取亮族在 CPU 侧预乘顶点色、替换 / 擦除族在 CPU 侧
// 提交黑源 + 修正 alpha),因此它们仍用原版 core/position_tex_color —— 本文件只服务
// 需要逐像素插值的那两族,不是按模式逐个特判。
//
// 顶点着色器复用原版 minecraft:core/position_tex_color(attribute 与输出与之一致:
// Position/UV0/Color → texCoord0/vertexColor)。

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    // 朝白插值:α 取顶点色 alpha(元素 α × 图层 α,由 replayFrom 烘焙)
    color.rgb = mix(vec3(1.0), color.rgb, vertexColor.a);
    fragColor = color * ColorModulator;
}
