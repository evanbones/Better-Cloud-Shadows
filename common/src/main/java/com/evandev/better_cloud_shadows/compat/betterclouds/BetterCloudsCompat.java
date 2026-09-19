package com.evandev.better_cloud_shadows.compat.betterclouds;

import com.evandev.better_cloud_shadows.platform.Services;
import net.minecraft.client.multiplayer.ClientLevel;

public final class BetterCloudsCompat {

    public static final String MOD_ID = "betterclouds";

    private static Boolean loaded;

    private BetterCloudsCompat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = Services.PLATFORM.isModLoaded(MOD_ID);
        }
        return loaded;
    }

    public static CloudField field(ClientLevel level, float partialTick) {
        if (!isLoaded()) return null;
        try {
            return BetterCloudsBridge.field(level, partialTick);
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    public static float coverage(int gridX, int gridZ, CloudField field) {
        return BetterCloudsBridge.coverage(gridX, gridZ, field);
    }

    public static void invalidate() {
        if (!isLoaded()) return;
        BetterCloudsBridge.invalidate();
    }
}
