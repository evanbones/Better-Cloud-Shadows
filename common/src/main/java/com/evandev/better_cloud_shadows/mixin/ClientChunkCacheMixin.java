package com.evandev.better_cloud_shadows.mixin;

import com.evandev.better_cloud_shadows.client.BlockLightTexture;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {

    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void better_cloud_shadows$markLightChanged(LightLayer type, SectionPos pos, CallbackInfo ci) {
        if (type == LightLayer.BLOCK) BlockLightTexture.markDirty(pos.x(), pos.z());
    }
}
