package com.evandev.better_cloud_shadows.compat.cirrus;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.evandev.better_cloud_shadows.platform.Services;
import net.minecraft.client.multiplayer.ClientLevel;

public final class CirrusCompat {

    public static final String MOD_ID = "cirrus";

    private static Boolean loaded;
    private static boolean broken;

    private CirrusCompat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = Services.PLATFORM.isModLoaded(MOD_ID);
        }
        return loaded;
    }

    public static boolean ownsClouds() {
        if (!isLoaded() || broken) return false;
        try {
            return CirrusBridge.ownsClouds();
        } catch (LinkageError | RuntimeException e) {
            fail(e);
            return false;
        }
    }

    public static CloudField field(ClientLevel level, float partialTick) {
        if (!isLoaded() || broken) return null;
        try {
            return CirrusBridge.field(level, partialTick);
        } catch (LinkageError | RuntimeException e) {
            fail(e);
            return null;
        }
    }

    public static void invalidate() {
        if (!isLoaded()) return;
        try {
            CirrusBridge.invalidate();
        } catch (LinkageError | RuntimeException ignored) {
            // nothing to reset if Cirrus never loaded
        }
    }

    private static void fail(Throwable cause) {
        broken = true;
        Constants.LOG.error("Cirrus cloud shadows disabled, the compatibility layer failed", cause);
    }
}
