#version 150

#moj_import <fog.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D DhDepthSampler;
uniform sampler2D CoverageSampler;
uniform sampler2D BlockLightSampler;

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
uniform float MinWorldY;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;
uniform int HasBlockLight;
uniform vec4 SurfaceLightOrigin;
uniform int DynamicLightCount;
uniform vec4 DynamicLight0;
uniform vec4 DynamicLight1;
uniform vec4 DynamicLight2;
uniform vec4 DynamicLight3;

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

vec3 sampleSurfaceInfo(vec3 world) {
    ivec2 size = textureSize(BlockLightSampler, 0);
    vec2 local = world.xz - SurfaceLightOrigin.xy;
    if (local.x < 0.0 || local.y < 0.0 || local.x > float(size.x) || local.y > float(size.y)) {
        return vec3(0.0, world.y, 0.0);
    }

    vec2 p = local - 0.5;
    ivec2 base = ivec2(floor(p));
    vec2 f = p - vec2(base);

    ivec2 lo = clamp(base, ivec2(0), size - 1);
    ivec2 hi = clamp(base + 1, ivec2(0), size - 1);
    ivec2 wrap = ivec2(SurfaceLightOrigin.zw);
    lo = (lo + wrap) & (size - 1);
    hi = (hi + wrap) & (size - 1);
    vec4 tex00 = texelFetch(BlockLightSampler, lo, 0);
    vec4 tex10 = texelFetch(BlockLightSampler, ivec2(hi.x, lo.y), 0);
    vec4 tex01 = texelFetch(BlockLightSampler, ivec2(lo.x, hi.y), 0);
    vec4 tex11 = texelFetch(BlockLightSampler, hi, 0);

    float valid = min(min(tex00.a, tex10.a), min(tex01.a, tex11.a));
    if (valid < 0.5) {
        return vec3(0.0, world.y, 0.0);
    }

    float y00 = (tex00.g * 255.0 + tex00.b * 255.0 * 256.0) - 1024.0;
    float y10 = (tex10.g * 255.0 + tex10.b * 255.0 * 256.0) - 1024.0;
    float y01 = (tex01.g * 255.0 + tex01.b * 255.0 * 256.0) - 1024.0;
    float y11 = (tex11.g * 255.0 + tex11.b * 255.0 * 256.0) - 1024.0;

    float surfaceY = mix(mix(y00, y10, f.x), mix(y01, y11, f.x), f.y);

    vec2 toEdge = min(local, vec2(size) - local);
    float edgeFade = clamp(min(toEdge.x, toEdge.y) / 16.0, 0.0, 1.0);

    float blockLight = 0.0;
    if (HasBlockLight == 1) {
        vec4 w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
        vec4 heights = vec4(y00, y10, y01, y11);
        vec4 lights = vec4(tex00.r, tex10.r, tex01.r, tex11.r) * 15.0;

        vec4 attenuated = max(lights - max(abs(world.y - (heights + 1.5)) - 0.5, 0.0), 0.0);

        vec4 open = w * step(heights + 0.5, vec4(world.y));
        float openWeight = dot(open, vec4(1.0));
        blockLight = (openWeight > 1e-4 ? dot(open, attenuated) / openWeight : dot(w, attenuated)) * edgeFade;
    }

    return vec3(blockLight, surfaceY, 1.0);
}

vec3 blockLightColor(float level) {
    float f = clamp(level / 15.0, 0.0, 1.0);
    float r = f / (4.0 - 3.0 * f) * 1.5;
    return vec3(r, r * ((r * 0.6 + 0.4) * 0.6 + 0.4), r * (r * r * 0.6 + 0.4));
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

    if (world.y < MinWorldY + 0.002) discard;

    if (insideLayer(world.y, CloudHeights.x, CloudThickness.x)) discard;
    if (LayerCount > 1 && insideLayer(world.y, CloudHeights.y, CloudThickness.y)) discard;
    if (LayerCount > 2 && insideLayer(world.y, CloudHeights.z, CloudThickness.z)) discard;
    if (LayerCount > 3 && insideLayer(world.y, CloudHeights.w, CloudThickness.w)) discard;

    vec3 surfaceInfo = sampleSurfaceInfo(world);
    float blockLight = surfaceInfo.x;
    float surfaceY = surfaceInfo.y;
    float hasSurface = surfaceInfo.z;

    float surfaceFactor = 1.0;
    if (hasSurface > 0.5) {
        float depthBelowSurface = surfaceY - world.y;
        if (depthBelowSurface > 3.0) {
            surfaceFactor = 1.0 - smoothstep(3.0, 13.0, depthBelowSurface);
            if (surfaceFactor <= 0.0) discard;
        }
    }

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

    if (DynamicLightCount > 0) {
        float d = length(world - DynamicLight0.xyz);
        if (d < 7.75) blockLight = max(blockLight, (1.0 - d / 7.75) * DynamicLight0.w);
    }
    if (DynamicLightCount > 1) {
        float d = length(world - DynamicLight1.xyz);
        if (d < 7.75) blockLight = max(blockLight, (1.0 - d / 7.75) * DynamicLight1.w);
    }
    if (DynamicLightCount > 2) {
        float d = length(world - DynamicLight2.xyz);
        if (d < 7.75) blockLight = max(blockLight, (1.0 - d / 7.75) * DynamicLight2.w);
    }
    if (DynamicLightCount > 3) {
        float d = length(world - DynamicLight3.xyz);
        if (d < 7.75) blockLight = max(blockLight, (1.0 - d / 7.75) * DynamicLight3.w);
    }

    float shade = clamp(ShadowColor.a * coverage * fade * visibility * surfaceFactor, 0.0, 1.0);
    vec3 shadowed = mix(vec3(1.0), ShadowColor.rgb, shade);
    if (blockLight > 0.0) {
        shadowed = min(shadowed + blockLightColor(blockLight), vec3(1.0));
        if (all(greaterThanEqual(shadowed, vec3(1.0)))) discard;
    }
    fragColor = vec4(shadowed, 1.0);
}
