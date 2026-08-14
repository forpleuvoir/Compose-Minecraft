#version 330

// Compose-Minecraft 自定义 GUI 三角形着色器(片元)
//
// 与 MC 自带 core/gui 相同的混合/丢弃语义:alpha == 0 直接丢弃,
// 输出 vertexColor * ColorModulator(与矩形渲染的观感一致)。
//
// 抗锯齿(渲染层,唯一可行手段 —— MC 26.2 GUI 管线无 MSAA、无自定义
// uniform,见 MinecraftGuiTriangles 注释):
// - edgeCoverage 为顶点插值后的「到最近真实轮廓的有符号屏幕像素距离」
//   (GeometryTessellator 计算):轮廓外侧为负、真实轮廓上为 0、内侧为正;
//   内部实心三角形为 OPAQUE 大数 → smoothstep 恒为 1,完全覆盖;
// - 过渡带:smoothstep(-0.5·aa, 0.5·aa, d),其中 aa = fwidth(d)。
//   由于 d 已按「矩阵最大轴缩放 × guiScale」换算成物理像素,且 fwidth
//   测量的是 d 在屏幕空间的变化率(≈1px),过渡带宽度恒约为 1 个物理像素,
//   跨真实轮廓两侧,与 GUI scale / 矩阵缩放无关;
// - 顶点着色器把 LineWidth 属性(承载 coverage)直接传给本着色器,
//   属性类型 float、平滑插值(非 flat),OpenGL / Vulkan 后端一致。
//
// 调试:临时把 DEBUG_COVERAGE 改为 1,输出 coverage 灰度图
// (clamp(d*0.5+0.5) → 0 黑 / 0.5 中灰 / 1 白),用于验证顶点属性
// 与插值是否正确;验证后必须改回 0 恢复颜色输出。

#define DEBUG_COVERAGE 0

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
in float edgeCoverage;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
#if DEBUG_COVERAGE == 1
    float debugValue = clamp(edgeCoverage * 0.5 + 0.5, 0.0, 1.0);
    fragColor = vec4(debugValue, debugValue, debugValue, 1.0);
    return;
#endif
    // 有符号距离 → 覆盖率:轮廓上 0.5,内侧(≥0.5px) 1,外侧(≤-0.5px) 0
    float aa = max(fwidth(edgeCoverage), 0.0001);
    float coverage = smoothstep(-0.5 * aa, 0.5 * aa, edgeCoverage);
    color.a *= coverage;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
