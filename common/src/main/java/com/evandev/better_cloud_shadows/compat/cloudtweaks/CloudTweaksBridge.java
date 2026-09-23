package com.evandev.better_cloud_shadows.compat.cloudtweaks;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.evandev.better_cloud_shadows.mixin.cloudtweaks.CustomCloudRendererAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.not_thefirst.story_mode_clouds.config.CloudsConfiguration;
import net.not_thefirst.story_mode_clouds.renderer.CustomCloudRenderer;
import net.not_thefirst.story_mode_clouds.renderer.MeshBuilder;
import net.not_thefirst.story_mode_clouds.renderer.RendererHolder;
import net.not_thefirst.story_mode_clouds.renderer.types.MeshTypeRegistry;
import net.not_thefirst.story_mode_clouds.utils.math.Texture;
import net.not_thefirst.story_mode_clouds.utils.minecraft.DimensionProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CloudTweaksBridge {

    private static final float Z_OFFSET = 0.33f;
    private static final float TICKS_PER_SECOND = 20f;
    private static final float VANILLA_ALPHA = 0.8f;
    private static final float OPACITY_STEPS = 64f;
    private static final String FLAT_MODE = "NORMAL_FAST";

    private static int loggedLayers = -1;

    private CloudTweaksBridge() {
    }

    static boolean ownsClouds() {
        CloudsConfiguration config = CloudsConfiguration.getInstance();
        return config != null && config.CLOUDS_RENDERED;
    }

    static CloudField field(ClientLevel level, float partialTick) {
        CloudsConfiguration config = CloudsConfiguration.getInstance();
        CloudsConfiguration.Dimension dimension = DimensionProvider.getCurrentDimension();
        if (dimension == null || !config.getCloudRendered(dimension)) return null;

        CustomCloudRenderer renderer = RendererHolder.get();
        if (renderer == null) return null;

        List<CustomCloudRenderer.LayerState> states = ((CustomCloudRendererAccessor) renderer).better_cloud_shadows$layers();
        int count = Math.min(config.getLayerCount(dimension), states.size());

        double time = (level.getGameTime() + partialTick) / TICKS_PER_SECOND;

        List<CloudField.Layer> layers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CloudField.Layer built = layer(i, config.getLayerInDimension(dimension, i), states.get(i).texture(), time);
            if (built != null) layers.add(built);
        }
        if (layers.isEmpty()) return null;

        layers.sort(Comparator.comparingDouble(CloudField.Layer::height));
        if (layers.size() > CloudField.MAX_LAYERS) {
            layers = layers.subList(0, CloudField.MAX_LAYERS);
        }

        if (loggedLayers != layers.size()) {
            loggedLayers = layers.size();
            Constants.LOG.info("Tracking {} Cloud Tweaks cloud layer(s)", layers.size());
        }
        return new CloudField(config.getCloudDistanceChunks() * 16f, List.copyOf(layers));
    }

    static void invalidate() {
        loggedLayers = -1;
    }

    private static CloudField.Layer layer(int index, CloudsConfiguration.LayerConfiguration source, Texture.TextureData texture, double time) {
        if (!source.LAYER_RENDERED || texture == null) return null;
        if (MeshTypeRegistry.getInstance().tryGetObject(source.MODE) == null) return null;

        CloudsConfiguration.LayerConfiguration.AppearanceParameters appearance = source.APPEARANCE;
        float alpha = appearance.USES_CUSTOM_ALPHA ? appearance.BASE_ALPHA / 255f : 1f;
        float opacity = quantise(alpha / VANILLA_ALPHA);
        if (opacity <= 0) return null;

        float cellSize = MeshBuilder.CELL_SIZE_IN_BLOCKS;
        double originX = -(appearance.LAYER_OFFSET_X + time * appearance.LAYER_SPEED_X);
        double originZ = -(Z_OFFSET * cellSize + appearance.LAYER_OFFSET_Z + time * appearance.LAYER_SPEED_Z);

        float thickness = FLAT_MODE.equals(source.MODE)
                ? 0f
                : MeshBuilder.HEIGHT_IN_BLOCKS * (source.IS_ENABLED ? appearance.CLOUD_Y_SCALE : 1f);

        long signature = System.identityHashCode(texture);
        signature = signature * 31 + index;
        signature = signature * 31 + Float.floatToIntBits(opacity);

        long[] cells = texture.cells;
        int width = texture.width;
        int height = texture.height;

        return new CloudField.Layer(
                originX,
                originZ,
                cellSize,
                cellSize,
                source.LAYER_HEIGHT + appearance.LAYER_HEIGHT_OFFSET,
                Math.max(thickness, 0f),
                signature,
                (cellX, cellZ) -> cells[Math.floorMod(cellZ, height) * width + Math.floorMod(cellX, width)] != 0L ? opacity : 0f);
    }

    private static float quantise(float value) {
        return Mth.clamp(Math.round(value * OPACITY_STEPS) / OPACITY_STEPS, 0f, 1f);
    }
}
