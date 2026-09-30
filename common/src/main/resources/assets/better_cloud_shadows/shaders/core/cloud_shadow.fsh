#version 150

#moj_import <fog.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D DhDepthSampler;
uniform sampler2D CoverageSampler;
uniform sampler2D BlockLightSampler;
uniform sampler2D SkyDistanceSampler;

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

vec4 bsplineWeights(float t) {
    vec4 n = vec4(1.0, 2.0, 3.0, 4.0) - t;
    vec4 s = n * n * n;
    float x = s.x;
    float y = s.y - 4.0 * s.x;
    float z = s.z - 4.0 * s.y + 6.0 * s.x;
    return vec4(x, y, z, 6.0 - x - y - z) / 6.0;
}

vec4 sampleCoverage(vec2 uv) {
    vec2 size = vec2(textureSize(CoverageSampler, 0));
    vec2 texel = uv * size - 0.5;
    vec2 f = fract(texel);
    texel -= f;

    vec4 xw = bsplineWeights(f.x);
    vec4 yw = bsplineWeights(f.y);
    vec4 sums = vec4(xw.xz + xw.yw, yw.xz + yw.yw);
    vec4 offsets = (texel.xxyy + vec2(-0.5, 1.5).xyxy + vec4(xw.yw, yw.yw) / sums) / size.xxyy;

    vec4 s0 = texture(CoverageSampler, offsets.xz);
    vec4 s1 = texture(CoverageSampler, offsets.yz);
    vec4 s2 = texture(CoverageSampler, offsets.xw);
    vec4 s3 = texture(CoverageSampler, offsets.yw);

    float sx = sums.x / (sums.x + sums.y);
    float sy = sums.z / (sums.z + sums.w);
    return mix(mix(s3, s2, sx), mix(s1, s0, sx), sy);
}

float layerCoverage(vec3 world, float height, float thickness, vec4 origin, vec4 channel) {
    float toCloud = height - world.y;
    if (toCloud <= thickness) return 0.0;

    vec2 hit = world.xz + SunDir.xz * min(toCloud / SunDir.y, ShearLimit) * ShearScale;

    vec2 uv = (hit - origin.xy) * origin.z;
    vec2 toEdge = min(uv, 1.0 - uv);
    float edgeFade = smoothstep(0.0, origin.w, min(toEdge.x, toEdge.y));
    if (edgeFade <= 0.0) return 0.0;

    return dot(sampleCoverage(clamp(uv, 0.0, 1.0)), channel) * edgeFade;
}

float skyDistance(ivec2 texel, float surfaceY, float y) {
    float depth = surfaceY + 1.0 - y;
    if (depth <= 0.0) return 0.0;

    vec4 steps = texelFetch(SkyDistanceSampler, texel, 0) * 255.0;
    if (depth <= steps.x) return 2.0 * depth / steps.x;
    if (depth <= steps.y) return 2.0 + 2.0 * (depth - steps.x) / (steps.y - steps.x);
    if (depth <= steps.z) return 4.0 + 2.0 * (depth - steps.y) / (steps.z - steps.y);
    if (depth <= steps.w) return 6.0 + 2.0 * (depth - steps.z) / (steps.w - steps.z);
    return 8.0 + depth - steps.w;
}

vec2 columnLight(ivec2 column, ivec2 size, ivec2 wrap, float y, inout float valid) {
    ivec2 texel = (clamp(column, ivec2(0), size - 1) + wrap) & (size - 1);
    vec4 tex = texelFetch(BlockLightSampler, texel, 0);
    valid = min(valid, tex.a);

    float surfaceY = (tex.g * 255.0 + tex.b * 255.0 * 256.0) - 1024.0;
    float blockLight = max(tex.r * 15.0 - max(abs(y - (surfaceY + 1.5)) - 0.5, 0.0), 0.0);
    return vec2(blockLight, skyDistance(texel, surfaceY, y));
}

vec2 sampleSurfaceInfo(vec3 world) {
    ivec2 size = textureSize(BlockLightSampler, 0);
    ivec2 wrap = ivec2(SurfaceLightOrigin.zw);
    vec2 local = world.xz - SurfaceLightOrigin.xy;
    if (local.x < 0.0 || local.y < 0.0 || local.x > float(size.x) || local.y > float(size.y)) {
        return vec2(0.0);
    }

    ivec2 cell = ivec2(floor(local));
    vec2 f = local - vec2(cell);

    float valid = 1.0;
    vec2 s[9];
    for (int dz = 0; dz < 3; dz++) {
        for (int dx = 0; dx < 3; dx++) {
            s[dz * 3 + dx] = columnLight(cell + ivec2(dx - 1, dz - 1), size, wrap, world.y, valid);
        }
    }
    if (valid < 0.5) return vec2(0.0);

    vec2 c00 = vec2(max(max(s[0].x, s[1].x), max(s[3].x, s[4].x)), (s[0].y + s[1].y + s[3].y + s[4].y) * 0.25);
    vec2 c10 = vec2(max(max(s[1].x, s[2].x), max(s[4].x, s[5].x)), (s[1].y + s[2].y + s[4].y + s[5].y) * 0.25);
    vec2 c01 = vec2(max(max(s[3].x, s[4].x), max(s[6].x, s[7].x)), (s[3].y + s[4].y + s[6].y + s[7].y) * 0.25);
    vec2 c11 = vec2(max(max(s[4].x, s[5].x), max(s[7].x, s[8].x)), (s[4].y + s[5].y + s[7].y + s[8].y) * 0.25);
    vec2 light = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);

    vec2 toEdge = min(local, vec2(size) - local);
    float edgeFade = clamp(min(toEdge.x, toEdge.y) / 16.0, 0.0, 1.0);
    float blockLight = HasBlockLight == 1 ? light.x * edgeFade : 0.0;

    return vec2(blockLight, light.y);
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

    vec2 surfaceInfo = sampleSurfaceInfo(world);
    float blockLight = surfaceInfo.x;

    float surfaceFactor = 1.0 - smoothstep(2.0, 8.0, surfaceInfo.y);
    if (surfaceFactor <= 0.0) discard;

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
