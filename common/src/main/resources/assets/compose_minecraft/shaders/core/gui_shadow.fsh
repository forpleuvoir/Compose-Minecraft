#version 330

// Compose-Minecraft 自定义 GUI 阴影着色器(片元)
//
// GPU 软阴影:对「到形状轮廓的有符号距离场」应用高斯模糊的解析解。
// 半无限平面(形状边缘局部)经高斯核卷积的精确结果:
//     alpha(d) = A/2 · erfc(d / (σ√2))
// 其中 d 为到轮廓距离(内部负、外部正),σ 为高斯标准差。
// CPU 端已把顶点距离归一化 d' = d/(σ√2),此处只需 erfc(d')。
//
// 参数语义完全参照 Skia SkShadowUtils:
//     σ = elevation · lightRadius / lightHeight / 2(默认 800/600 → 0.667·e)
//     alpha = kAmbientAlpha(0.039) | kSpotAlpha(0.19) · (1 - e/lightHeight)
// 每个阴影由两个元素组成:ambient(无偏移)+ spot(偏移),颜色均为黑色,
// alpha 通道 = 各自阴影 alpha;顶点颜色 RGB = 0(黑色),混合为普通
// TRANSLUCENT alpha 混合。
//
// erf 近似:Abramowitz-Stegun 7.1.26(最大误差 1.5e-7),GLSL 330 无内置 erf。
// 调试:把 DEBUG_DISTANCE 改为 1,输出距离灰度图(0 黑 / 0.5 轮廓 / 1 白)。

#define DEBUG_DISTANCE 0

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
in float edgeCoverage;

out vec4 fragColor;

float erfApprox(float x) {
    float s = x < 0.0 ? -1.0 : 1.0;
    float ax = abs(x);
    float t = 1.0 / (1.0 + 0.3275911 * ax);
    float y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax);
    return s * y;
}

void main() {
    if (DEBUG_DISTANCE > 0) {
        float debugValue = clamp(edgeCoverage * 0.5 + 0.5, 0.0, 1.0);
        fragColor = vec4(debugValue, debugValue, debugValue, 1.0);
        return;
    }
    // 高斯模糊解析解:alpha = A/2 · erfc(d/(σ√2)) = A/2 · (1 - erf(d')).
    // 顶点距离语义:内部为正、外部为负(GeometryTessellator coverage 约定),
    // 因此这里取 (1 + erf(d')):内部(d' >> 0)趋近 A(实心)、轮廓(d'=0)为 A/2、
    // 外部(d' << 0)按 erfc 衰减到 0。符号写反会导致阴影空心(中心比边缘淡)。
    float alpha = 0.5 * (1.0 + erfApprox(edgeCoverage));
    if (alpha <= 0.004) {
        discard;
    }
    fragColor = vec4(0.0, 0.0, 0.0, vertexColor.a * alpha) * ColorModulator;
}
