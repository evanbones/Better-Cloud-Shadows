package com.evandev.better_cloud_shadows.compat.betterclouds;

import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.evandev.better_cloud_shadows.mixin.betterclouds.ChunkedGeneratorAccessor;
import com.qendolin.betterclouds.BetterClouds;
import com.qendolin.betterclouds.clouds.*;
import com.qendolin.betterclouds.config.Config;
import com.qendolin.betterclouds.config.ConfigManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;

final class BetterCloudsBridge {

    private BetterCloudsBridge() {
    }

    static boolean ownsClouds(ClientLevel level) {
        if (!ConfigManager.isInitialized() || !BetterClouds.isEnabled()) return false;

        Renderer renderer = BetterClouds.getCloudsRenderer();
        if (renderer == null) return false;

        Resources resources = renderer.resources();
        if (resources == null || resources.failedToLoadCritical()) return false;

        ResourceKey<DimensionType> dimension = level.dimensionTypeRegistration().unwrapKey().orElse(null);
        return ConfigManager.instance().enabledDimensions.contains(dimension);
    }

    static CloudField field(ClientLevel level, float partialTick) {
        Renderer renderer = BetterClouds.getCloudsRenderer();
        if (renderer == null) return null;

        Resources resources = renderer.resources();
        if (resources == null) return null;

        ChunkedGenerator generator = resources.generator();
        if (generator == null || !generator.canRender()) return null;

        Config config = generator.config();
        if (config == null || config.spacing <= 0) return null;

        float cloudHeight = level.effects().getCloudHeight();
        if (Float.isNaN(cloudHeight)) return null;

        // Better Clouds quantises cloudiness the same way before it regenerates geometry
        float cloudiness = (float) (Math.ceil(CloudinessProvider.getCloudiness(level, partialTick) * 100) / 100.0);
        if (cloudiness <= 0) return null;

        Sampler noise = ((ChunkedGeneratorAccessor) generator).better_cloud_shadows$sampler();
        if (noise == null) return null;

        float spacing = config.spacing;
        float sparsity = config.sparsity;
        float fuzziness = config.fuzziness;
        float samplingScale = config.samplingScale;

        return CloudField.single(
                generator.originX() - spacing * 0.5,
                generator.originZ() - spacing * 0.5,
                spacing,
                config.sizeXZ,
                cloudHeight + config.yOffset,
                config.sizeY,
                config.blockDistance(),
                signatureOf(noise, config, cloudiness),
                (cellX, cellZ) -> coverage(noise, cellX, cellZ, spacing, sparsity, fuzziness, samplingScale, cloudiness)
        );
    }

    private static float coverage(
            Sampler noise, int cellX, int cellZ,
            float spacing, float sparsity, float fuzziness, float samplingScale, float cloudiness) {
        if (sparsity > 0 && Sampler.hashToFloat(noise.getSeed(), 'G', cellX, cellZ) < sparsity) return 0;

        int sampleX = Mth.floor(cellX * spacing);
        int sampleZ = Mth.floor(cellZ * spacing);
        return noise.sample(sampleX, sampleZ, cloudiness, fuzziness, samplingScale) > 0 ? 1f : 0f;
    }

    private static long signatureOf(Sampler noise, Config config, float cloudiness) {
        long hash = noise.getSeed();
        hash = hash * 31 + System.identityHashCode(noise);
        hash = hash * 31 + Float.floatToIntBits(config.spacing);
        hash = hash * 31 + Float.floatToIntBits(config.sparsity);
        hash = hash * 31 + Float.floatToIntBits(config.fuzziness);
        hash = hash * 31 + Float.floatToIntBits(config.samplingScale);
        hash = hash * 31 + Float.floatToIntBits(cloudiness);
        return hash;
    }
}
