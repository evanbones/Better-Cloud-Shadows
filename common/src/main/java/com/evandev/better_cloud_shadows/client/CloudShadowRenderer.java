package com.evandev.better_cloud_shadows.client;

import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.evandev.better_cloud_shadows.clouds.CloudFields;
import com.evandev.better_cloud_shadows.config.ModConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class CloudShadowRenderer {

    public static final String SHADER_NAME = "cloud_shadow";

    private static final float SHADOW_TINT_RED = 0.58f;
    private static final float SHADOW_TINT_GREEN = 0.62f;
    private static final float SHADOW_TINT_BLUE = 0.76f;

    private static final float SUN_FADE_START = 0.12f;
    private static final float SUN_FADE_END = 0.35f;

    private static final float EDGE_FADE_TEXELS = 16f;

    private static ShaderInstance shader;
    private static RenderTarget depthCopy;
    private static CloudCoverageTexture coverage;
    private static ClientLevel lastLevel;

    private CloudShadowRenderer() {
    }

    public static void setShader(ShaderInstance instance) {
        shader = instance;
        CloudFields.invalidate();
    }

    public static void render(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, Camera cameraView, float partialTick) {
        ModConfig config = ModConfig.get();
        if (!config.cloudShadows) return;

        ShaderInstance instance = shader;
        if (instance == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != lastLevel) {
            lastLevel = level;
            reset();
        }
        if (level == null) return;
        if (minecraft.options.getCloudsType() == CloudStatus.OFF) return;

        CloudField field = CloudFields.current(level, partialTick);
        if (field == null || field.spacing() <= 0) return;

        float sunAngle = level.getSunAngle(partialTick);
        float sunX = -Mth.sin(sunAngle);
        float sunY = Mth.cos(sunAngle);
        float sunFactor = smoothstep(SUN_FADE_START, SUN_FADE_END, sunY);
        if (sunFactor <= 0) return;

        float rain = level.getRainLevel(partialTick);
        float strength = (float) config.cloudShadowStrength * sunFactor * (1f - 0.85f * rain);
        if (strength <= 1f / 255f) return;

        float shade = Math.min(strength, 1f);
        float deepen = Mth.clamp(strength - 1f, 0f, 1f);
        float tintRed = Mth.lerp(deepen, SHADOW_TINT_RED, 0f);
        float tintGreen = Mth.lerp(deepen, SHADOW_TINT_GREEN, 0f);
        float tintBlue = Mth.lerp(deepen, SHADOW_TINT_BLUE, 0f);

        Vec3 camera = cameraView.getPosition();
        float referenceY = Math.min((float) camera.y, field.cloudHeight());
        float shear = (field.cloudHeight() - referenceY) / sunY;
        double centerX = camera.x + sunX * shear;
        double centerZ = camera.z;

        float softness = config.cloudShadowSoftness;
        float texelSize = field.spacing();
        float coveredRadius = CloudCoverageTexture.SAFE_RADIUS * texelSize;

        float shadowDistance = Math.min(field.shadowDistance(), config.cloudShadowDistance);
        float fadeEnd = Math.min(shadowDistance, coveredRadius * 0.95f);

        int centerTexelX = Mth.floor((centerX - field.originX()) / texelSize);
        int centerTexelZ = Mth.floor((centerZ - field.originZ()) / texelSize);

        int dilate = Mth.clamp(
                Math.round((field.footprint() - texelSize) / (2f * texelSize)), 0, CloudCoverageTexture.MAX_DILATE_RADIUS);
        float blur = Mth.clamp(softness / texelSize, 0f, CloudCoverageTexture.MAX_BLUR_RADIUS);

        if (coverage == null) coverage = new CloudCoverageTexture();
        coverage.update(field, centerTexelX, centerTexelZ, dilate, blur);

        RenderTarget main = minecraft.getMainRenderTarget();
        if (depthCopy == null || depthCopy.width != main.width || depthCopy.height != main.height) {
            if (depthCopy != null) depthCopy.destroyBuffers();
            depthCopy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        }
        depthCopy.copyDepthFrom(main);
        main.bindWrite(false);

        Matrix4f inverseViewProjection = new Matrix4f(projectionMatrix)
                .mul(modelViewMatrix)
                .invert();

        float extent = texelSize * CloudCoverageTexture.SIZE;
        float coverageWorldX = (float) (field.originX() + coverage.originX() * (double) texelSize);
        float coverageWorldZ = (float) (field.originZ() + coverage.originZ() * (double) texelSize);

        instance.setSampler("DepthSampler", depthCopy.getDepthTextureId());
        instance.setSampler("CoverageSampler", coverage.textureId());
        instance.safeGetUniform("InvViewProjMat").set(inverseViewProjection);
        instance.safeGetUniform("CameraPos").set((float) camera.x, (float) camera.y, (float) camera.z);
        instance.safeGetUniform("SunDir").set(sunX, sunY, 0f);
        instance.safeGetUniform("CloudHeight").set(field.cloudHeight());
        instance.safeGetUniform("CoverageOrigin").set(
                coverageWorldX, coverageWorldZ, 1f / extent, EDGE_FADE_TEXELS / CloudCoverageTexture.SIZE);
        instance.safeGetUniform("ShadowColor").set(tintRed, tintGreen, tintBlue, shade);
        instance.safeGetUniform("FadeParams").set(fadeEnd * 0.6f, fadeEnd);

        RenderSystem.setShader(() -> instance);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.DST_COLOR,
                GlStateManager.DestFactor.ZERO,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.addVertex(-1f, -1f, 0f);
        builder.addVertex(1f, -1f, 0f);
        builder.addVertex(1f, 1f, 0f);
        builder.addVertex(-1f, 1f, 0f);
        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    public static void reset() {
        CloudFields.invalidate();
        if (coverage != null) {
            coverage.close();
            coverage = null;
        }
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
