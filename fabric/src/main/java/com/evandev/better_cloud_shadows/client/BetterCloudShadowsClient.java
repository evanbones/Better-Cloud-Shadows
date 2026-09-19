package com.evandev.better_cloud_shadows.client;

import com.evandev.better_cloud_shadows.Constants;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.resources.ResourceLocation;

public class BetterCloudShadowsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, CloudShadowRenderer.SHADER_NAME),
                DefaultVertexFormat.POSITION,
                CloudShadowRenderer::setShader));

        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> CloudShadowRenderer.render(
                context.positionMatrix(),
                context.projectionMatrix(),
                context.camera(),
                context.tickCounter().getGameTimeDeltaPartialTick(false)));
    }
}
