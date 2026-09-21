package com.evandev.better_cloud_shadows.client;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.evandev.better_cloud_shadows.clouds.CloudFields;
import com.evandev.better_cloud_shadows.compat.distanthorizons.DistantHorizonsCompat;
import com.evandev.better_cloud_shadows.config.ModConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.List;

public final class CloudShadowRenderer {

    public static final String SHADER_NAME = "cloud_shadow";

    private static final float SHADOW_TINT_RED = 0.58f;
    private static final float SHADOW_TINT_GREEN = 0.62f;
    private static final float SHADOW_TINT_BLUE = 0.76f;

    private static final float SUN_FADE_START = 0.12f;
    private static final float SUN_FADE_END = 0.35f;

    private static final float EDGE_FADE_TEXELS = 16f;

    private static final float SHEAR_LIMIT = 512f;
    private static final float PENUMBRA_RATIO = 0.0093f;

    private static final String[] COVERAGE_ORIGIN_UNIFORMS = {
            "CoverageOrigin0", "CoverageOrigin1", "CoverageOrigin2", "CoverageOrigin3"
    };
    private static final float[] layerHeight = new float[CloudField.MAX_LAYERS];
    private static final float[] layerThickness = new float[CloudField.MAX_LAYERS];
    private static final float[] layerOriginX = new float[CloudField.MAX_LAYERS];
    private static final float[] layerOriginZ = new float[CloudField.MAX_LAYERS];
    private static final float[] layerInvExtent = new float[CloudField.MAX_LAYERS];

    private static final Matrix4f dhInverseViewProjection = new Matrix4f();
    private static final int[] scissorBox = new int[4];

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

        CloudField field = CloudFields.current(level, partialTick);
        if (field == null) return;

        List<CloudField.Layer> layers = field.layers();
        int layerCount = Math.min(layers.size(), CloudField.MAX_LAYERS);
        if (layerCount == 0) return;

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
        float softness = config.cloudShadowSoftness;
        float shearScale = (float) config.cloudShadowShear;

        if (coverage == null) coverage = new CloudCoverageTexture();

        boolean dirty = coverage.uploadedLayers() != layerCount;
        float coveredRadius = 0f;

        for (int i = 0; i < layerCount; i++) {
            CloudField.Layer layer = layers.get(i);
            float texelSize = layer.spacing();
            if (texelSize <= 0) return;

            float referenceY = Math.min((float) camera.y, layer.height());
            float shear = Math.min((layer.height() - referenceY) / sunY, SHEAR_LIMIT) * shearScale;
            double centerX = camera.x + sunX * shear;
            double centerZ = camera.z;

            int centerTexelX = Mth.floor((centerX - layer.originX()) / texelSize);
            int centerTexelZ = Mth.floor((centerZ - layer.originZ()) / texelSize);

            int dilate = Mth.clamp(
                    Math.round((layer.footprint() - texelSize) / (2f * texelSize)),
                    0, CloudCoverageTexture.MAX_DILATE_RADIUS);
            float penumbra = PENUMBRA_RATIO * (layer.height() - referenceY);
            float blur = CloudCoverageTexture.blurRadius((softness + penumbra) / texelSize);

            dirty |= coverage.update(i, layer, centerTexelX, centerTexelZ, dilate, blur);

            layerHeight[i] = layer.height();
            layerThickness[i] = layer.thickness();
            layerOriginX[i] = (float) (layer.originX() + coverage.originX(i) * (double) texelSize);
            layerOriginZ[i] = (float) (layer.originZ() + coverage.originZ(i) * (double) texelSize);
            layerInvExtent[i] = 1f / (texelSize * CloudCoverageTexture.SIZE);

            coveredRadius = Math.max(coveredRadius, CloudCoverageTexture.SAFE_RADIUS * texelSize);
        }
        if (dirty) coverage.upload(layerCount);

        float shadowDistance = config.matchCloudRenderDistance
                ? field.shadowDistance()
                : Math.min(field.shadowDistance(), config.cloudShadowDistance);
        float fadeEnd = Math.min(shadowDistance, coveredRadius * 0.95f);

        RenderTarget main = minecraft.getMainRenderTarget();
        if (depthCopy == null || depthCopy.width != main.width || depthCopy.height != main.height) {
            if (depthCopy != null) depthCopy.destroyBuffers();
            depthCopy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            matchDepthFormat(depthCopy, main);
        }

        RenderSystem.depthMask(true);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissorEnabled) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        RenderSystem.disableScissor();
        depthCopy.copyDepthFrom(main);
        main.bindWrite(false);

        Matrix4f inverseViewProjection = new Matrix4f(projectionMatrix)
                .mul(modelViewMatrix)
                .invert();

        int dhDepthTexture = config.distantHorizonsClouds
                ? DistantHorizonsCompat.depthTextureId(dhInverseViewProjection)
                : 0;

        instance.setSampler("DepthSampler", depthCopy.getDepthTextureId());
        instance.setSampler("DhDepthSampler", dhDepthTexture != 0 ? dhDepthTexture : depthCopy.getDepthTextureId());
        instance.safeGetUniform("UseDhDepth").set(dhDepthTexture != 0 ? 1 : 0);
        instance.safeGetUniform("DhInvViewProjMat").set(dhInverseViewProjection);
        instance.setSampler("CoverageSampler", coverage.textureId());
        instance.safeGetUniform("InvViewProjMat").set(inverseViewProjection);
        instance.safeGetUniform("CameraPos").set((float) camera.x, (float) camera.y, (float) camera.z);
        instance.safeGetUniform("SunDir").set(sunX, sunY, 0f);
        instance.safeGetUniform("ShearScale").set(shearScale);
        instance.safeGetUniform("ShearLimit").set(SHEAR_LIMIT);
        instance.safeGetUniform("LayerCount").set(layerCount);
        instance.safeGetUniform("CloudThickness").set(
                layerThickness[0],
                layerCount > 1 ? layerThickness[1] : layerThickness[0],
                layerCount > 2 ? layerThickness[2] : layerThickness[0],
                layerCount > 3 ? layerThickness[3] : layerThickness[0]);
        instance.safeGetUniform("CloudHeights").set(
                layerHeight[0],
                layerCount > 1 ? layerHeight[1] : layerHeight[0],
                layerCount > 2 ? layerHeight[2] : layerHeight[0],
                layerCount > 3 ? layerHeight[3] : layerHeight[0]);
        for (int i = 0; i < COVERAGE_ORIGIN_UNIFORMS.length; i++) {
            int source = Math.min(i, layerCount - 1);
            instance.safeGetUniform(COVERAGE_ORIGIN_UNIFORMS[i]).set(
                    layerOriginX[source], layerOriginZ[source],
                    layerInvExtent[source], EDGE_FADE_TEXELS / CloudCoverageTexture.SIZE);
        }
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
        RenderSystem.defaultBlendFunc();
        if (scissorEnabled) {
            RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        }
    }

    public static void reset() {
        CloudFields.invalidate();
        if (coverage != null) {
            coverage.close();
            coverage = null;
        }
    }

    private static void matchDepthFormat(RenderTarget copy, RenderTarget main) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        int bits = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        int componentType = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);

        int internalFormat;
        int type;
        if (componentType == GL11.GL_FLOAT && bits == 32) {
            internalFormat = GL30.GL_DEPTH_COMPONENT32F;
            type = GL11.GL_FLOAT;
        } else if (bits == 24) {
            internalFormat = GL14.GL_DEPTH_COMPONENT24;
            type = GL11.GL_UNSIGNED_INT;
        } else if (bits == 16) {
            internalFormat = GL14.GL_DEPTH_COMPONENT16;
            type = GL11.GL_UNSIGNED_SHORT;
        } else {
            Constants.LOG.warn(
                    "Unrecognised main depth format ({} bits, component type {}), cloud shadows may not appear",
                    bits, componentType);
            return;
        }

        GlStateManager._bindTexture(copy.getDepthTextureId());
        GlStateManager._texImage2D(
                GL11.GL_TEXTURE_2D, 0, internalFormat, copy.width, copy.height, 0,
                GL11.GL_DEPTH_COMPONENT, type, null);
        GlStateManager._bindTexture(0);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
