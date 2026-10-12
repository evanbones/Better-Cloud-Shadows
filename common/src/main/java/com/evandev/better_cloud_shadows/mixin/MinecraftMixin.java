package com.evandev.better_cloud_shadows.mixin;

import com.evandev.better_cloud_shadows.client.CloudShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "updateLevelInEngines", at = @At("HEAD"))
    private void better_cloud_shadows$releaseLevel(ClientLevel level, CallbackInfo ci) {
        if (level == null) CloudShadowRenderer.reset();
    }
}
