package com.evandev.better_cloud_shadows.compat.distanthorizons;

import com.evandev.better_cloud_shadows.clouds.CloudField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DhCloudTracker {

    public static final String CLOUD_GROUP_PATH = "Clouds";
    private static final long STALE_NANOS = 500_000_000L;
    private static final double SAME_LAYER_EPSILON = 1.0;

    private static final Entry[] TRACKED = new Entry[CloudField.MAX_LAYERS];

    static {
        for (int i = 0; i < TRACKED.length; i++) {
            TRACKED[i] = new Entry();
        }
    }

    private DhCloudTracker() {
    }

    public static synchronized void observe(double x, double y, double z, List<?> boxes) {
        long now = System.nanoTime();

        for (Entry entry : TRACKED) {
            if (entry.live(now) && Math.abs(entry.height - y) <= SAME_LAYER_EPSILON) {
                entry.set(y, x, z, boxes, now);
                return;
            }
        }

        for (Entry entry : TRACKED) {
            if (!entry.live(now)) {
                entry.set(y, x, z, boxes, now);
                return;
            }
        }

        Entry highest = TRACKED[0];
        for (Entry entry : TRACKED) {
            if (entry.height > highest.height) highest = entry;
        }
        if (highest.height > y) {
            highest.set(y, x, z, boxes, now);
        }
    }

    public static synchronized List<Observed> snapshot() {
        long now = System.nanoTime();
        List<Observed> result = new ArrayList<>(TRACKED.length);
        for (Entry entry : TRACKED) {
            if (!entry.live(now) || entry.boxes == null) continue;
            result.add(new Observed(entry.height, entry.originX, entry.originZ, entry.boxes));
        }
        result.sort(Comparator.comparingDouble(Observed::height));
        return result;
    }

    public static synchronized void clear() {
        for (Entry entry : TRACKED) {
            entry.stamp = 0;
            entry.boxes = null;
        }
    }

    public record Observed(double height, double originX, double originZ, List<?> boxes) {
    }

    private static final class Entry {
        double height;
        double originX;
        double originZ;
        List<?> boxes;
        long stamp;

        boolean live(long now) {
            return stamp != 0 && now - stamp <= STALE_NANOS;
        }

        void set(double height, double originX, double originZ, List<?> boxes, long stamp) {
            this.height = height;
            this.originX = originX;
            this.originZ = originZ;
            this.boxes = boxes;
            this.stamp = stamp;
        }
    }
}
