#version 150

#moj_import <fog.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D CoverageSampler;

uniform mat4 InvViewProjMat;
uniform vec3 CameraPos;
uniform vec3 SunDir;
uniform vec2 CloudPlane;
uniform vec4 CoverageOrigin;
uniform vec4 ShadowColor;
uniform vec2 FadeParams;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;

in vec2 texCoord;

out vec4 fragColor;

float coverageAt(vec2 hit) {
    vec2 uv = (hit - CoverageOrigin.xy) * CoverageOrigin.z;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return 0.0;
    return texture(CoverageSampler, uv).r;
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (depth >= 1.0) discard;

    vec4 clip = vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 unprojected = InvViewProjMat * clip;
    vec3 relative = unprojected.xyz / unprojected.w;
    vec3 world = relative + CameraPos;

    float toCloud = CloudPlane.x - world.y;
    if (toCloud <= 0.0) discard;

    vec2 hit = world.xz + SunDir.xz * (toCloud / SunDir.y);

    float tap = CloudPlane.y;
    float coverage = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            coverage += coverageAt(hit + vec2(float(x), float(y)) * tap);
        }
    }
    coverage /= 9.0;
    if (coverage <= 0.0) discard;

    float fade = 1.0 - smoothstep(FadeParams.x, FadeParams.y, length(relative.xz));
    if (fade <= 0.0) discard;

    float visibility = mix(1.0, linear_fog_fade(fog_distance(relative, FogShape), FogStart, FogEnd), FogColor.a);
    if (visibility <= 0.0) discard;

    float shade = clamp(ShadowColor.a * coverage * fade * visibility, 0.0, 1.0);
    fragColor = vec4(mix(vec3(1.0), ShadowColor.rgb, shade), 1.0);
}
