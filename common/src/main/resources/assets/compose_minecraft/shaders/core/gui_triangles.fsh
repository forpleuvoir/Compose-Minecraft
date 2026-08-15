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
// - 过渡带:smoothstep(-1.0·aa, 1.0·aa, d),其中 aa = fwidth(d)。
//   由于 d 已按「矩阵最大轴缩放 × guiScale」换算成物理像素,且 fwidth
//   测量的是 d 在屏幕空间的变化率(≈1px),过渡带宽度恒约为 2 个物理像素
//   (跨真实轮廓两侧各 ±1px),与几何外扩/内缩深度 1px 精确匹配:轮廓上
//   alpha 0.5、轮廓外侧 1px 处 0、内侧 1px 处完全饱和 —— 与三环填充的
//   coverage 0/±1 端点对齐,无阶梯、无发糊;
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
    // 有符号距离 → 覆盖率:轮廓上 0.5,内侧(≥1px) 1,外侧(≤-1px) 0。
    // 过渡带 smoothstep(-1.0aa, 1.0aa, d):±1px —— 与三角化器三环填充的
    // 外扩/内缩深度 1px 精确匹配(coverage 0/±1 端点落在饱和区边缘),
    // 同时保证任何「距轮廓 ≥ 1px 的真距离」顶点都完全饱和,内部填充与
    // 壳带在共享边上 alpha 严格一致(无接缝)。
    float aa = max(fwidth(edgeCoverage), 0.0001);
    float coverage = smoothstep(-1.0 * aa, 1.0 * aa, edgeCoverage);
    color.a *= coverage;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
