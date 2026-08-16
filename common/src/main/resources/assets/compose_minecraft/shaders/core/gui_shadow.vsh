#version 330

// Compose-Minecraft 自定义 GUI 阴影着色器(顶点)
//
// 与 core/gui_triangles 同构:顶点格式 POSITION_COLOR_LINE_WIDTH,
// LineWidth 属性承载「到阴影形状真实轮廓的有符号距离,归一化 d' = d/(σ√2)」,
// 片元着色器用高斯模糊解析解(erfc)生成软阴影 alpha —— 模糊完全在 GPU,
// CPU 只做形状三角化 + 每顶点距离场(GeometryTessellator.shadowFill)。
//
// 保持保守 GLSL 330 语法、不依赖 OpenGL 特有扩展,可被 MC 的
// OpenGL / Vulkan 两个渲染后端正常编译。
// 平台适配点:uniform 块声明必须与 pipeline 的 BindGroupLayout
// (DynamicTransforms / Projection)完全一致,否则链接失败;
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
