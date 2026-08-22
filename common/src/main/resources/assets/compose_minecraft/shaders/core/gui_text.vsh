#version 330

// Compose-Minecraft TrueType 文本渲染着色器(顶点,T.TT)
//
// 与 MC 自带 core/position_tex_color 相同的 uniform 布局(DynamicTransforms /
// Projection)与顶点格式 POSITION_TEX_COLOR(Position/UV0/Color):
// - Position 为命令局部坐标,几何变换经 addVertexWith2DPose 折进 ModelViewMat;
// - UV0 为 R8 字形图集页内的归一化坐标;
// - Color 为顶点色 tint(run 颜色 × alpha)。
//
// 平台适配点:shader 声明必须与 pipeline 的 BindGroupLayout 完全一致
// (MATRICES_PROJECTION + SAMPLER0,见 MinecraftGuiText),否则链接失败;
// 保守 GLSL 330,OpenGL / Vulkan 渲染后端均可编译。

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
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
