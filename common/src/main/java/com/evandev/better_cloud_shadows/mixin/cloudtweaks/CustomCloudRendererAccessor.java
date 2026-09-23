package com.evandev.better_cloud_shadows.mixin.cloudtweaks;

import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.not_thefirst.story_mode_clouds.renderer.CustomCloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@IfModLoaded("cloud_tweaks")
@Mixin(value = CustomCloudRenderer.class, remap = false)
public interface CustomCloudRendererAccessor {

    @Accessor("layers")
    List<CustomCloudRenderer.LayerState> better_cloud_shadows$layers();
}
