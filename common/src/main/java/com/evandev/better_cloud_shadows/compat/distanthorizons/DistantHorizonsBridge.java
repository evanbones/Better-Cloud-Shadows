package com.evandev.better_cloud_shadows.compat.distanthorizons;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.client.CloudCoverageTexture;
import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;

import java.util.ArrayList;
import java.util.List;

final class DistantHorizonsBridge {

    private static final int MIN_CELL = 8;
    private static final float FALLBACK_SHADOW_DISTANCE = 2048f;

    private static Mask mask;
    private static boolean loggedField;
    private static boolean loggedMiss;

    private DistantHorizonsBridge() {
    }

    static CloudField field() {
        List<DhCloudTracker.Observed> observed = DhCloudTracker.snapshot();
        if (observed.isEmpty()) {
            report("no Distant Horizons cloud groups have reported a position yet");
            return null;
        }

        float shadowDistance = shadowDistance();
        int cell = cellSize(shadowDistance);

        Mask current = ensureMask(observed.getFirst().boxes(), cell);
        if (current == null) {
            report("the Distant Horizons cloud groups are empty");
            return null;
        }

        List<CloudField.Layer> layers = new ArrayList<>(observed.size());
        for (DhCloudTracker.Observed layer : observed) {
            layers.add(new CloudField.Layer(
                    layer.originX(),
                    layer.originZ(),
                    cell,
                    cell,
                    (float) layer.height(),
                    current.thickness(),
                    current.signature(),
                    current::coverage));
            if (layers.size() == CloudField.MAX_LAYERS) break;
        }

        if (!loggedField) {
            loggedField = true;
            Constants.LOG.info(
                    "Tracking {} Distant Horizons cloud layer(s), {} block cells over a {} block period",
                    layers.size(), cell, current.cells() * cell);
        }
        return new CloudField(shadowDistance, List.copyOf(layers));
    }

    static void invalidate() {
        mask = null;
        loggedField = false;
        loggedMiss = false;
    }

    private static void report(String reason) {
        if (loggedMiss) return;
        loggedMiss = true;
        Constants.LOG.debug("No Distant Horizons cloud shadows: {}", reason);
    }

    private static int cellSize(float shadowDistance) {
        int cell = MIN_CELL;
        while (cell < 128 && CloudCoverageTexture.SAFE_RADIUS * cell < shadowDistance) {
            cell <<= 1;
        }
        return cell;
    }

    private static float shadowDistance() {
        IDhApiConfig configs = DhApi.Delayed.configs;
        if (configs == null) return FALLBACK_SHADOW_DISTANCE;

        Integer chunks = configs.graphics().chunkRenderDistance().getValue();
        if (chunks == null || chunks <= 0) return FALLBACK_SHADOW_DISTANCE;
        return chunks * 16f;
    }

    private static Mask ensureMask(List<?> boxes, int cell) {
        int count = boxes.size();
        if (count == 0) return null;

        Mask cached = mask;
        if (cached != null && cached.boxCount == count && cached.cell == cell) return cached;

        double extent = 0;
        double thickness = 0;
        List<DhApiRenderableBox> copy = new ArrayList<>(count);
        for (Object element : boxes) {
            if (!(element instanceof DhApiRenderableBox box) || box.minPos == null || box.maxPos == null) continue;
            copy.add(box);
            extent = Math.max(extent, Math.max(box.maxPos.x, box.maxPos.z));
            thickness = Math.max(thickness, box.maxPos.y - box.minPos.y);
        }
        if (copy.isEmpty() || extent <= 0) return null;

        // the period has to be a whole number of cells so the grid can tile by simple wrapping
        int cells = Math.max(1, (int) Math.ceil(extent / cell));
        if ((long) cells * cells > 4_194_304L) return null;

        boolean[] grid = new boolean[cells * cells];
        for (DhApiRenderableBox box : copy) {
            int minX = Math.floorDiv((int) Math.floor(box.minPos.x), cell);
            int minZ = Math.floorDiv((int) Math.floor(box.minPos.z), cell);
            int maxX = Math.floorDiv((int) Math.ceil(box.maxPos.x) - 1, cell);
            int maxZ = Math.floorDiv((int) Math.ceil(box.maxPos.z) - 1, cell);
            for (int x = minX; x <= maxX; x++) {
                int row = Math.floorMod(x, cells) * cells;
                for (int z = minZ; z <= maxZ; z++) {
                    grid[row + Math.floorMod(z, cells)] = true;
                }
            }
        }

        Mask built = new Mask(grid, cells, cell, count, (float) thickness);
        mask = built;
        return built;
    }

    private record Mask(boolean[] grid, int cells, int cell, int boxCount, float thickness) {

        long signature() {
            return ((long) cells * 31 + cell) * 31L + boxCount;
        }

        float coverage(int cellX, int cellZ) {
            return grid[Math.floorMod(cellX, cells) * cells + Math.floorMod(cellZ, cells)] ? 1f : 0f;
        }
    }
}
