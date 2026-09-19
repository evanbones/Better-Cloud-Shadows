package com.evandev.better_cloud_shadows.clouds;

/**
 * A snapshot of the cloud layer for a single frame, expressed as a uniform grid of cells.
 */
public record CloudField(
        double originX,
        double originZ,
        float spacing,
        float footprint,
        float cloudHeight,
        float shadowDistance,
        long signature,
        Coverage coverage
) {

    public interface Coverage {
        float at(int cellX, int cellZ);
    }
}
