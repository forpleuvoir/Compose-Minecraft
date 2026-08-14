#version 330

// Compose-Minecraft 自定义 GUI 三角形着色器(顶点)
//
// 与 MC 自带 core/gui 相同的 uniform 布局(DynamicTransforms / Projection),
// 顶点格式为 POSITION_COLOR_LINE_WIDTH:LineWidth 属性在本平台承载
// 「到最近真实轮廓的有符号屏幕像素距离」(coverage,由 GeometryTessellator
// 计算:外侧负、轮廓 0、内侧正),片元着色器据此做边缘抗锯齿。
//
// 保持保守 GLSL 330 语法、不依赖 OpenGL 特有扩展,可被 MC 的
// OpenGL / Vulkan 两个渲染后端正常编译。
//
// 平台适配点:MC 26.2 的 core shader 加载器(ShaderSource + GpuDevice
// .precompilePipeline)要求 shader 声明与 pipeline 的 BindGroupLayout
// 完全一致的 uniform 块(DynamicTransforms / Projection),否则链接失败;
// 顶点 attribute 名必须与 VertexFormat 的语义名一致(Position/Color/LineWidth)。

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;
in float LineWidth;

out vec4 vertexColor;
out float edgeCoverage;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    edgeCoverage = LineWidth;
}
