package com.evandev.better_cloud_shadows.compat.cirrus;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.evandev.better_cloud_shadows.clouds.VanillaCloudTexture;
import com.evandev.better_cloud_shadows.mixin.LevelRendererAccessor;
import com.jvn.cirrus.client.CirrusCloudMode;
import com.jvn.cirrus.client.compat.distanthorizons.DistantHorizonsCompat;
import com.jvn.cirrus.client.util.CirrusEasing;
import com.jvn.cirrus.config.CirrusConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

final class CirrusBridge {

    private static final float CELL_SIZE = 12f;
    private static final float TRAVEL_SPEED = 0.03f;
    private static final float FANCY_THICKNESS = 4f;

    private static final int RAIN_PATTERN_OFFSET_X = 83;
    private static final int RAIN_PATTERN_OFFSET_Z = 47;
    private static final int THUNDER_PATTERN_OFFSET_X = 157;
    private static final int THUNDER_PATTERN_OFFSET_Z = 109;

    private static final float WEATHER_STEPS = 64f;
    private static final float OPACITY_STEPS = 64f;

    private static int loggedLayers = -1;

    private CirrusBridge() {
    }

    static boolean ownsClouds() {
        return CirrusCloudMode.isActive(Minecraft.getInstance().options.getCloudsType());
    }

    static CloudField field(ClientLevel level, float partialTick) {
        float cloudHeight = level.effects().getCloudHeight();
        if (Float.isNaN(cloudHeight)) return null;

        if (!VanillaCloudTexture.ensureLoaded()) return null;

        int ticks = ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer).better_cloud_shadows$ticks();
        double travel = (ticks + partialTick) * TRAVEL_SPEED;

        float rain = CirrusEasing.smoothstep(level.getRainLevel(partialTick));
        float thunder = CirrusEasing.smoothstep(level.getThunderLevel(partialTick));
        float rainCoverage = quantise(rain * CirrusConfig.RAIN_CLOUD_COVERAGE.get().floatValue(), WEATHER_STEPS);
        float thunderCoverage = quantise(thunder * CirrusConfig.THUNDER_CLOUD_COVERAGE.get().floatValue(), WEATHER_STEPS);

        double lowerHeight = cloudHeight + CirrusConfig.LOWER_LAYER_HEIGHT_OFFSET.get();
        double upperHeight = lowerHeight + CirrusConfig.UPPER_LAYER_HEIGHT_OFFSET.get();
        double topHeight = upperHeight + CirrusConfig.TOP_LAYER_HEIGHT_OFFSET.get();

        List<CloudField.Layer> layers = new ArrayList<>(CloudField.MAX_LAYERS);
        layers.add(layer(
                Kind.LOWER, travel,
                CirrusConfig.LOWER_LAYER_SPEED.get(),
                (float) lowerHeight,
                thickness(CirrusConfig.LOWER_LAYER_STYLE.get()),
                opacity(CirrusConfig.LOWER_LAYER_OPACITY.get().floatValue(), rain, thunder, 0.22f, 0.10f),
                rainCoverage, thunderCoverage));
        if (CirrusConfig.UPPER_LAYER_ENABLED.get()) {
            layers.add(layer(
                    Kind.UPPER, travel,
                    CirrusConfig.UPPER_LAYER_SPEED.get(),
                    (float) upperHeight,
                    thickness(CirrusConfig.UPPER_LAYER_STYLE.get()),
                    opacity(CirrusConfig.UPPER_LAYER_OPACITY.get().floatValue(), rain, thunder, 0.16f, 0.08f),
                    rainCoverage, thunderCoverage));
        }
        if (CirrusConfig.TOP_LAYER_ENABLED.get()) {
            layers.add(layer(
                    Kind.TOP, travel,
                    CirrusConfig.TOP_LAYER_SPEED.get(),
                    (float) topHeight,
                    thickness(CirrusConfig.TOP_LAYER_STYLE.get()),
                    opacity(CirrusConfig.TOP_LAYER_OPACITY.get().floatValue(), rain, thunder, 0.12f, 0.06f),
                    rainCoverage, thunderCoverage));
        }

        if (loggedLayers != layers.size()) {
            loggedLayers = layers.size();
            Constants.LOG.info("Tracking {} Cirrus cloud layer(s)", layers.size());
        }
        return new CloudField(DistantHorizonsCompat.cloudRenderDistanceChunks() * 16f, List.copyOf(layers));
    }

    static void invalidate() {
        loggedLayers = -1;
    }

    private static CloudField.Layer layer(
            Kind kind, double travel, double speed, float height, float thickness, float opacity,
            float rainCoverage, float thunderCoverage) {
        long signature = VanillaCloudTexture.signature();
        signature = signature * 31 + kind.ordinal();
        signature = signature * 31 + Float.floatToIntBits(opacity);
        signature = signature * 31 + Float.floatToIntBits(rainCoverage);
        signature = signature * 31 + Float.floatToIntBits(thunderCoverage);

        return new CloudField.Layer(
                -travel * speed - CELL_SIZE * kind.patternOffsetX,
                -CELL_SIZE * (double) kind.patternOffsetZ,
                CELL_SIZE,
                CELL_SIZE,
                height,
                thickness,
                signature,
                (cellX, cellZ) -> coverage(cellX, cellZ, rainCoverage, thunderCoverage) * opacity);
    }

    private static float coverage(int cellX, int cellZ, float rainCoverage, float thunderCoverage) {
        float base = VanillaCloudTexture.alphaAt(cellX, cellZ);
        float transparency = 1f - base;
        if (rainCoverage > 0) {
            transparency *= 1f - VanillaCloudTexture.alphaAt(
                    cellX + RAIN_PATTERN_OFFSET_X, cellZ + RAIN_PATTERN_OFFSET_Z) * rainCoverage;
        }
        if (thunderCoverage > 0) {
            transparency *= 1f - VanillaCloudTexture.alphaAt(
                    cellX + THUNDER_PATTERN_OFFSET_X, cellZ + THUNDER_PATTERN_OFFSET_Z) * thunderCoverage;
        }
        return 1f - transparency;
    }

    private static float thickness(CirrusConfig.CloudStyle style) {
        return style == CirrusConfig.CloudStyle.FANCY ? FANCY_THICKNESS : 0f;
    }

    private static float opacity(float base, float rain, float thunder, float rainBoost, float thunderBoost) {
        float rendered = Mth.clamp(base + rain * rainBoost + thunder * thunderBoost, 0f, 1f);
        return quantise((float) Math.sqrt(rendered), OPACITY_STEPS);
    }

    private static float quantise(float value, float steps) {
        return Mth.clamp(Math.round(value * steps) / steps, 0f, 1f);
    }

    private enum Kind {
        LOWER(0, 0),
        UPPER(37, 91),
        TOP(113, 173);

        private final int patternOffsetX;
        private final int patternOffsetZ;

        Kind(int patternOffsetX, int patternOffsetZ) {
            this.patternOffsetX = patternOffsetX;
            this.patternOffsetZ = patternOffsetZ;
        }
    }
}
