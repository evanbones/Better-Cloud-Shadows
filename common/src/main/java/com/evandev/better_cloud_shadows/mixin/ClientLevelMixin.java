package com.evandev.better_cloud_shadows.mixin;

import com.evandev.better_cloud_shadows.client.BlockLightTexture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void better_cloud_shadows$markBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        BlockLightTexture.markDirty(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void better_cloud_shadows$markSectionChanged(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
        BlockLightTexture.markDirty(sectionX, sectionZ);
    }
}
