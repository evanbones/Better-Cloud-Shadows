package com.evandev.better_cloud_shadows.compat.lambdynlights;

import com.evandev.better_cloud_shadows.mixin.lambdynlights.LambDynLightsAccessor;
import com.evandev.better_cloud_shadows.platform.Services;
import dev.lambdaurora.lambdynlights.LambDynLights;
import dev.lambdaurora.lambdynlights.engine.source.DynamicLightSource;
import dev.lambdaurora.lambdynlights.engine.source.EntityDynamicLightSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class LambDynamicLightsCompat {

    public static final String MOD_ID = "lambdynlights";

    public record LightSource(double x, double y, double z, int luminance) {}

    public static boolean isLoaded() {
        return Services.PLATFORM.isModLoaded(MOD_ID);
    }

    public static List<LightSource> getDynamicLights(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!isLoaded()) {
            return List.of();
        }
        return Handler.getDynamicLights(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static class Handler {
        private static List<LightSource> getDynamicLights(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            LambDynLights ldl = LambDynLights.get();
            if (!ldl.config.getDynamicLightsMode().isEnabled()) {
                return List.of();
            }

            Set<DynamicLightSource> sources = ((LambDynLightsAccessor) ldl).better_cloud_shadows$getDynamicLightSources();
            if (sources.isEmpty()) {
                return List.of();
            }

            List<LightSource> result = new ArrayList<>();
            for (DynamicLightSource src : sources) {
                if (!(src instanceof EntityDynamicLightSource entitySource)) continue;

                int lum = entitySource.getLuminance();
                if (lum <= 0) continue;

                double x = entitySource.getDynamicLightX();
                double y = entitySource.getDynamicLightY();
                double z = entitySource.getDynamicLightZ();

                if (x >= minX - 8.0 && x <= maxX + 8.0 &&
                    y >= minY - 8.0 && y <= maxY + 8.0 &&
                    z >= minZ - 8.0 && z <= maxZ + 8.0) {
                    result.add(new LightSource(x, y, z, lum));
                }
            }
            return result;
        }
    }
}
