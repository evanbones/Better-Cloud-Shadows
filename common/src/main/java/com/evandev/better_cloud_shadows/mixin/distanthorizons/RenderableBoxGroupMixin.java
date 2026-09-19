package com.evandev.better_cloud_shadows.mixin.distanthorizons;

import com.evandev.better_cloud_shadows.compat.distanthorizons.DhCloudTracker;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@IfModLoaded("distanthorizons")
@Mixin(value = RenderableBoxGroup.class, remap = false)
public abstract class RenderableBoxGroupMixin {

    @Shadow
    @Final
    public String resourceLocationPath;

    @Inject(method = "setOriginBlockPos", at = @At("HEAD"))
    private void better_cloud_shadows$trackCloudOrigin(DhApiVec3d pos, CallbackInfo ci) {
        if (pos == null || !DhCloudTracker.CLOUD_GROUP_PATH.equals(this.resourceLocationPath)) return;
        DhCloudTracker.observe(pos.x, pos.y, pos.z, (List<?>) this);
    }
}
