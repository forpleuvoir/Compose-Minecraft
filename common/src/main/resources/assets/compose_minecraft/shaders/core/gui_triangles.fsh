#version 330

// Compose-Minecraft 自定义 GUI 三角形着色器(片元)
//
// 与 MC 自带 core/gui 相同的混合/丢弃语义:alpha == 0 直接丢弃,
// 输出 vertexColor * ColorModulator(与矩形渲染的观感一致)。
//
// 抗锯齿(渲染层,唯一可行手段 —— MC 26.2 GUI 管线无 MSAA、无自定义
// uniform,见 MinecraftGuiTriangles 注释):
// - edgeCoverage 为顶点插值后的「到轮廓边的屏幕像素距离」,轮廓边上为 0、
//   内部为正值;fsh 用 smoothstep 在 1px 内柔和渐变(比线性 clamp 更自然,
//   无硬切边缘),内部三角形恒为大数 → 实心;
// - 兼容性:不依赖 gl_FragCoord(避免 OpenGL/Vulkan 后端 Y 轴语义差异),
//   纯顶点插值,两个渲染后端行为一致。

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
    // 边缘抗锯齿:0..2px 内 smoothstep 柔和渐变
    color.a *= smoothstep(0.0, 2.0, edgeCoverage);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
