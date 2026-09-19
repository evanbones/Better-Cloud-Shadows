package com.evandev.better_cloud_shadows.compat.distanthorizons;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.evandev.better_cloud_shadows.platform.Services;
import org.joml.Matrix4f;

public final class DistantHorizonsCompat {

    public static final String MOD_ID = "distanthorizons";

    private static Boolean loaded;
    private static boolean broken;

    private DistantHorizonsCompat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = Services.PLATFORM.isModLoaded(MOD_ID);
        }
        return loaded;
    }

    public static int depthTextureId(Matrix4f inverseViewProjection) {
        if (!isLoaded() || broken) return 0;
        try {
            DhRenderState.bind();
            if (!DhRenderState.inverseViewProjection(inverseViewProjection)) return 0;
            return DhRenderState.depthTextureId();
        } catch (LinkageError | RuntimeException e) {
            broken = true;
            Constants.LOG.error("Distant Horizons cloud shadows disabled, the compatibility layer failed", e);
            return 0;
        }
    }

    public static CloudField field() {
        if (!isLoaded() || broken) return null;
        try {
            return DistantHorizonsBridge.field();
        } catch (LinkageError | RuntimeException e) {
            broken = true;
            Constants.LOG.error("Distant Horizons cloud shadows disabled, the compatibility layer failed", e);
            return null;
        }
    }

    public static void invalidate() {
        DhCloudTracker.clear();
        if (!isLoaded()) return;
        try {
            DistantHorizonsBridge.invalidate();
            DhRenderState.clear();
        } catch (LinkageError | RuntimeException ignored) {
            // nothing to reset if DH never loaded
        }
    }
}
