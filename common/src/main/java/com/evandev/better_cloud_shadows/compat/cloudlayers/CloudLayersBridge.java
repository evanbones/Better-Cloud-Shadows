package com.evandev.better_cloud_shadows.compat.cloudlayers;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.mojang.datafixers.util.Either;
import mod.lwhrvw.cloud_layers.CloudLayers;
import mod.lwhrvw.cloud_layers.ICloudLayersConfig;
import mod.lwhrvw.cloud_layers.Layer;
import mod.lwhrvw.cloud_layers.MapManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CloudLayersBridge {

    private static final float Z_OFFSET = 0.33f;
    private static final double TIME_SCALE = 0.05;
    private static final float VISIBILITY_STEPS = 64f;
    private static final double MAP_TIME_STEPS = 4.0;

    private static int loggedLayers = -1;

    private CloudLayersBridge() {
    }

    static boolean ownsClouds() {
        ICloudLayersConfig config = CloudLayers.config;
        return config != null && config.isModEnabled() && CloudLayers.isFancyEnabled();
    }

    static CloudField field(ClientLevel level, float partialTick) {
        List<Tuple<Layer, Double>> visible = CloudLayers.layerManager.getVisibleLayers(level, partialTick);
        if (visible.isEmpty()) return null;

        double time = ((float) CloudLayers.getWorldTime() + partialTick) * TIME_SCALE;
        int heightOffset = CloudLayers.getHeightOffset();

        List<Tuple<Layer, Double>> sorted = new ArrayList<>(visible);
        sorted.sort(Comparator.comparingDouble(entry -> entry.getA().height()));

        List<CloudField.Layer> layers = new ArrayList<>(CloudField.MAX_LAYERS);
        for (Tuple<Layer, Double> entry : sorted) {
            CloudField.Layer built = layer(entry.getA(), entry.getB(), time, heightOffset);
            if (built == null) continue;
            layers.add(built);
            if (layers.size() == CloudField.MAX_LAYERS) break;
        }
        if (layers.isEmpty()) return null;

        if (loggedLayers != layers.size()) {
            loggedLayers = layers.size();
            Constants.LOG.info("Tracking {} Cloud Layers cloud layer(s)", layers.size());
        }
        return new CloudField(CloudLayers.getRenderDistance() * 16f, List.copyOf(layers));
    }

    static void invalidate() {
        loggedLayers = -1;
    }

    private static CloudField.Layer layer(Layer source, double rawVisibility, double time, int heightOffset) {
        if (source.skyboxMode()) return null;

        float spacing = source.scale();
        if (!(spacing > 0)) return null;

        double visibility = Mth.clamp(rawVisibility, 0.0, 1.0);
        float opacity = quantise((float) (visibility * visibility));
        if (opacity <= 0) return null;

        Sample color = alphaOf(source.color());
        Sample density = valueOf(source.opacity());
        Sample thickness = valueOf(source.thickness());

        double originX = source.offset().getFirst() + time * source.velocity().getFirst();
        double originZ = source.offset().getLast() + time * source.velocity().getLast() - Z_OFFSET * spacing;

        long signature = source.hashCode();
        signature = signature * 31 + Float.floatToIntBits(opacity);
        signature = signature * 31 + mapTimeKey(time, Math.max(color.rate(), density.rate()));

        return new CloudField.Layer(
                originX,
                originZ,
                spacing,
                spacing,
                (float) source.height() + heightOffset,
                Math.max(thickness.max(), 0f),
                signature,
                (cellX, cellZ) -> coverage(color, density, cellX, cellZ, time) * opacity);
    }

    private static float coverage(Sample color, Sample density, int cellX, int cellZ, double time) {
        float alpha = color.at(cellX, cellZ, time);
        float opacity = Mth.clamp(density.at(cellX, cellZ, time), 0f, 1f);
        return Mth.clamp(alpha * opacity, 0f, 1f);
    }

    private static float quantise(float value) {
        return Math.round(value * VISIBILITY_STEPS) / VISIBILITY_STEPS;
    }

    private static long mapTimeKey(double time, double rate) {
        if (!(rate > 0)) return 0;
        return Math.round(time * rate * MAP_TIME_STEPS);
    }

    private static Sample valueOf(Either<Layer.TextureMap, Float> option) {
        return new Sample(option.right().orElse(0f), node(option.left().orElse(null)), false);
    }

    private static Sample alphaOf(Either<Layer.TextureMap, List<Float>> option) {
        List<Float> constant = option.right().orElse(null);
        float alpha = constant == null || constant.size() < 4 ? 1f : constant.get(3);
        return new Sample(alpha, node(option.left().orElse(null)), true);
    }

    private static MapNode node(Layer.TextureMap source) {
        if (source == null) return null;

        MapManager.LoadedTextureMap map = CloudLayers.mapManager.get(source.texture()).orElse(null);
        Either<Layer.TextureMap, Float> add = source.add();
        return new MapNode(
                map,
                multiply(source.multiply()),
                node(add.left().orElse(null)),
                add.right().orElse(0f),
                source.scale(),
                source.offset().getFirst(),
                source.offset().getLast(),
                source.velocity().getFirst(),
                source.velocity().getLast());
    }

    private static float[] multiply(List<Float> source) {
        float[] channels = new float[4];
        for (int i = 0; i < channels.length && i < source.size(); i++) {
            channels[i] = source.get(i);
        }
        return channels;
    }

    private record Sample(float constant, MapNode node, boolean alphaChannel) {

        float at(int cellX, int cellZ, double time) {
            if (node == null) return constant;
            return alphaChannel ? node.alpha(cellX, cellZ, time) : node.value(cellX, cellZ, time);
        }

        float max() {
            return node == null ? constant : node.max();
        }

        double rate() {
            return node == null ? 0 : node.rate();
        }
    }

    private record MapNode(
            MapManager.LoadedTextureMap map,
            float[] multiply,
            MapNode add,
            float addConstant,
            double scale,
            double offsetX,
            double offsetZ,
            double velocityX,
            double velocityZ
    ) {

        float value(int cellX, int cellZ, double time) {
            int[] channels = channels(cellX, cellZ, time);
            if (channels == null) return 0f;

            float sum = add == null ? addConstant : add.value(cellX, cellZ, time);
            for (int i = 0; i < 4; i++) {
                sum += channels[i] / 255f * multiply[i];
            }
            return sum;
        }

        float alpha(int cellX, int cellZ, double time) {
            int[] channels = channels(cellX, cellZ, time);
            return channels == null ? 1f : channels[3] / 255f * multiply[3];
        }

        float max() {
            float sum = add == null ? addConstant : add.max();
            for (float channel : multiply) {
                if (channel > 0) sum += channel;
            }
            return sum;
        }

        double rate() {
            double own = scale == 0 ? 0 : Math.max(Math.abs(velocityX), Math.abs(velocityZ)) / Math.abs(scale);
            return add == null ? own : Math.max(own, add.rate());
        }

        private int[] channels(int cellX, int cellZ, double time) {
            if (map == null) return null;
            int u = (int) Math.floor((cellX + offsetX + velocityX * time) / scale);
            int v = (int) Math.floor((cellZ + offsetZ + velocityZ * time) / scale);
            return map.get(u, v);
        }
    }
}
