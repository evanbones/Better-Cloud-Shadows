package com.evandev.better_cloud_shadows.mixin.lambdynlights;

import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import dev.lambdaurora.lambdynlights.LambDynLights;
import dev.lambdaurora.lambdynlights.engine.source.DynamicLightSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@IfModLoaded("lambdynlights")
@Mixin(value = LambDynLights.class, remap = false)
public interface LambDynLightsAccessor {

    @Accessor(value = "dynamicLightSources", remap = false)
    Set<DynamicLightSource> better_cloud_shadows$getDynamicLightSources();
}
