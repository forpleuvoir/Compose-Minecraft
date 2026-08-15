#version 330

// Compose-Minecraft 自定义 GUI 三角形着色器(片元,描边专用)
//
// 与填充 shader(gui_triangles.fsh)的差异只在 AA 过渡带策略:
//
// 填充(coverage = 连续真距离场):fwidth(smoothstep(-1.0*aa, 1.0*aa, d))
//   过渡带恒 2 物理像素,fwidth 测量稳定。
//
// 描边(coverage = 近似距离场):带四边形由两个三角形拼接,共享对角线两侧
//   的 coverage 场不连续(每个三角形各自近似「到对侧轮廓的距离」),fwidth
//   在拼接处测量到巨大梯度 → smoothstep 区间突变 → 过渡带内出现单像素
//   alpha 骤降(毛刺:模糊色中突然缺一个像素)。
//   因此描边改用**固定过渡** smoothstep(-1, 1, d):
//   - coverage 已按物理像素距离归一化(设计梯度 ≈ 1),过渡带恒约 2px;
//   - 不依赖 fwidth,彻底消除拼接处的过渡带突变(毛刺);
//   - 代价:coverage 场梯度偏离 1 的局部(插值误差)过渡带略锐/略糊,
//     但单调无噪声,视觉稳定。
//
// 混合/丢弃语义与 core/gui 一致:alpha == 0 直接丢弃,输出 vertexColor
// * ColorModulator。

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
    if (DEBUG_COVERAGE > 0) {
        float debugValue = clamp(edgeCoverage * 0.5 + 0.5, 0.0, 1.0);
        fragColor = vec4(debugValue, debugValue, debugValue, 1.0);
        return;
    }
    // 固定过渡:轮廓上 0.5,内侧(≥1px) 1,外侧(≤-1px) 0;
    // 过渡带恒 2 物理像素,与几何外扩/内缩深度 1.5px 兼容(边缘 alpha 归零)。
    float coverage = smoothstep(-1.0, 1.0, edgeCoverage);
    color.a *= coverage;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
