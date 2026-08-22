#version 330

// Compose-Minecraft TrueType 文本渲染着色器(片元,T.TT)
//
// R8 单通道图集:采样 R 通道得字形 coverage(stb_truetype 光栅化灰度,
// LINEAR 采样在放大/缩小时提供边缘抗锯齿),coverage 乘顶点色 alpha 后
// 走 SrcOver(TRANSLUCENT)混合 —— 与原版文本的观感一致、边缘更平滑。
//
// 顶点色 tint:run 颜色 × 图层 alpha 已在记录端折算进 Color;
// ColorModulator 与 MC 全部 GUI 管线一致地做最终调制。

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

// 小字号可读性:轻度 gamma 提压(中间调增强),对齐系统渲染器的"实"感;
// 线性覆盖度直出会偏淡/发虚(与 DirectWrite 等对比实测反馈)
#define COVERAGE_GAIN 1.18
#define COVERAGE_BIAS (-0.02)

void main() {
    float coverage = texture(Sampler0, texCoord0).r;
    coverage = clamp(coverage * COVERAGE_GAIN + COVERAGE_BIAS, 0.0, 1.0);
    vec4 color = vertexColor;
    color.a *= coverage;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
