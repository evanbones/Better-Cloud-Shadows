package com.evandev.better_cloud_shadows.client;

import com.evandev.better_cloud_shadows.Constants;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.io.IOException;

@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class CloudShadowEvents {

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(
                        event.getResourceProvider(),
                        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, CloudShadowRenderer.SHADER_NAME),
                        DefaultVertexFormat.POSITION),
                CloudShadowRenderer::setShader);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        CloudShadowRenderer.render(
                event.getModelViewMatrix(),
                event.getProjectionMatrix(),
                event.getCamera(),
                event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }
}
