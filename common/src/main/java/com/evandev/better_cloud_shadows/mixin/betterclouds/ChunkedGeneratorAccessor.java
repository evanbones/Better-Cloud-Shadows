package com.evandev.better_cloud_shadows.mixin.betterclouds;

import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import com.qendolin.betterclouds.clouds.ChunkedGenerator;
import com.qendolin.betterclouds.clouds.Sampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@IfModLoaded("betterclouds")
@Mixin(value = ChunkedGenerator.class, remap = false)
public interface ChunkedGeneratorAccessor {

    @Accessor("sampler")
    Sampler better_cloud_shadows$sampler();
}
