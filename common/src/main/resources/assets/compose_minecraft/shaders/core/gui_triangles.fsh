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
// - 过渡带:**固定过渡** smoothstep(-1.0, 1.0, d),恒约 2 物理像素。
//   coverage 已按物理像素距离归一化(设计梯度 ≈ 1),固定斜率与 fwidth
//   方案等价但**无 fwidth 噪声**:fwidth 在三角形边界(coverage 场方向
//   突变,如角部共享边)测量到巨大梯度 → 过渡带抖动 → 边缘线偏移
//   (三角形底边左端视觉下移 1px 的根因)。代价:coverage 场梯度偏离 1
//   的局部(尖角深几何)过渡带略宽(发糊),但单调稳定。
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
    // 调试:把 DEBUG_COVERAGE 改为 1,输出 coverage 灰度图
    // (clamp(d*0.5+0.5) → 0 黑 / 0.5 中灰 / 1 白);验证后必须改回 0
    if (DEBUG_COVERAGE > 0) {
        float debugValue = clamp(edgeCoverage * 0.5 + 0.5, 0.0, 1.0);
        fragColor = vec4(debugValue, debugValue, debugValue, 1.0);
        return;
    }
    // 固定过渡:轮廓上 0.5,内侧(≥1px) 1,外侧(≤-1px) 0;
    // 与几何外扩/内缩深度 1.5px 兼容(边缘 alpha 归零),无 fwidth 抖动。
    float coverage = smoothstep(-1.0, 1.0, edgeCoverage);
    color.a *= coverage;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
