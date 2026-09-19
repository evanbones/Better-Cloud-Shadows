#version 150

#moj_import <fog.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D DhDepthSampler;
uniform sampler2D CoverageSampler;

uniform mat4 InvViewProjMat;
uniform mat4 DhInvViewProjMat;
uniform int UseDhDepth;
uniform vec3 CameraPos;
uniform vec3 SunDir;
uniform float ShearScale;
uniform float ShearLimit;
uniform vec4 CloudHeights;
uniform vec4 CloudThickness;
uniform int LayerCount;
uniform vec4 CoverageOrigin0;
uniform vec4 CoverageOrigin1;
uniform vec4 CoverageOrigin2;
uniform vec4 CoverageOrigin3;
uniform vec4 ShadowColor;
uniform vec2 FadeParams;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;

in vec2 texCoord;

out vec4 fragColor;

vec3 unproject(mat4 inverseViewProjection, float depth) {
    vec4 clip = vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 unprojected = inverseViewProjection * clip;
    return unprojected.xyz / unprojected.w;
}

bool insideLayer(float y, float height, float thickness) {
    return y >= height - 1.0 && y <= height + thickness + 1.0;
}

float layerCoverage(vec3 world, float height, float thickness, vec4 origin, vec4 channel) {
    float toCloud = height - world.y;
    if (toCloud <= thickness) return 0.0;

    vec2 hit = world.xz + SunDir.xz * min(toCloud / SunDir.y, ShearLimit) * ShearScale;

    vec2 uv = (hit - origin.xy) * origin.z;
    vec2 toEdge = min(uv, 1.0 - uv);
    float edgeFade = smoothstep(0.0, origin.w, min(toEdge.x, toEdge.y));
    if (edgeFade <= 0.0) return 0.0;

    return dot(texture(CoverageSampler, clamp(uv, 0.0, 1.0)), channel) * edgeFade;
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec3 relative;
    if (depth < 1.0) {
        relative = unproject(InvViewProjMat, depth);
    } else if (UseDhDepth == 1) {
        float dhDepth = texture(DhDepthSampler, texCoord).r;
        if (dhDepth >= 1.0) discard;
        relative = unproject(DhInvViewProjMat, dhDepth);
    } else {
        discard;
    }

    vec3 world = relative + CameraPos;

    if (insideLayer(world.y, CloudHeights.x, CloudThickness.x)) discard;
    if (LayerCount > 1 && insideLayer(world.y, CloudHeights.y, CloudThickness.y)) discard;
    if (LayerCount > 2 && insideLayer(world.y, CloudHeights.z, CloudThickness.z)) discard;
    if (LayerCount > 3 && insideLayer(world.y, CloudHeights.w, CloudThickness.w)) discard;

    float transmittance = 1.0 - layerCoverage(world, CloudHeights.x, CloudThickness.x, CoverageOrigin0, vec4(1.0, 0.0, 0.0, 0.0));
    if (LayerCount > 1) {
        transmittance *= 1.0 - layerCoverage(world, CloudHeights.y, CloudThickness.y, CoverageOrigin1, vec4(0.0, 1.0, 0.0, 0.0));
    }
    if (LayerCount > 2) {
        transmittance *= 1.0 - layerCoverage(world, CloudHeights.z, CloudThickness.z, CoverageOrigin2, vec4(0.0, 0.0, 1.0, 0.0));
    }
    if (LayerCount > 3) {
        transmittance *= 1.0 - layerCoverage(world, CloudHeights.w, CloudThickness.w, CoverageOrigin3, vec4(0.0, 0.0, 0.0, 1.0));
    }

    float coverage = 1.0 - transmittance;
    if (coverage <= 0.0) discard;

    float fade = 1.0 - smoothstep(FadeParams.x, FadeParams.y, length(relative.xz));
    if (fade <= 0.0) discard;

    float visibility = mix(1.0, linear_fog_fade(fog_distance(relative, FogShape), FogStart, FogEnd), FogColor.a);
    if (visibility <= 0.0) discard;

    float shade = clamp(ShadowColor.a * coverage * fade * visibility, 0.0, 1.0);
    fragColor = vec4(mix(vec3(1.0), ShadowColor.rgb, shade), 1.0);
}
