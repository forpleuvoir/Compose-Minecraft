#version 330

// Compose-Minecraft 自定义 GUI 三角形着色器(顶点,描边专用)
//
// 与 gui_triangles.vsh 完全相同:MC 26.2 core shader 加载器要求 uniform 块
// 与 pipeline 的 BindGroupLayout 完全一致,顶点 attribute 名与 VertexFormat
// 语义名一致(Position/Color/LineWidth)。独立文件供描边 pipeline 引用,
// 与填充 pipeline 分离(片元着色器 AA 策略不同)。

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
