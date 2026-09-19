package com.evandev.better_cloud_shadows.clouds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record CloudField(
        float shadowDistance,
        List<Layer> layers
) {

    public static final int MAX_LAYERS = 4;

    public static CloudField single(
            double originX, double originZ,
            float spacing, float footprint,
            float height, float thickness, float shadowDistance,
            long signature, Coverage coverage) {
        return new CloudField(
                shadowDistance,
                List.of(new Layer(originX, originZ, spacing, footprint, height, thickness, signature, coverage)));
    }

    public static CloudField merge(CloudField first, CloudField second) {
        if (first == null) return second;
        if (second == null) return first;

        List<Layer> merged = new ArrayList<>(first.layers().size() + second.layers().size());
        merged.addAll(first.layers());
        merged.addAll(second.layers());
        merged.sort(Comparator.comparingDouble(Layer::height));
        if (merged.size() > MAX_LAYERS) {
            merged = merged.subList(0, MAX_LAYERS);
        }

        return new CloudField(Math.max(first.shadowDistance(), second.shadowDistance()), List.copyOf(merged));
    }

    public interface Coverage {
        float at(int cellX, int cellZ);
    }

    public record Layer(
            double originX,
            double originZ,
            float spacing,
            float footprint,
            float height,
            float thickness,
            long signature,
            Coverage coverage
    ) {
    }
}
