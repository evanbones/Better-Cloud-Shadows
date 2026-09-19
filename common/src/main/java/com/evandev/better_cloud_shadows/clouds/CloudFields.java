package com.evandev.better_cloud_shadows.clouds;

import com.evandev.better_cloud_shadows.compat.betterclouds.BetterCloudsCompat;
import net.minecraft.client.multiplayer.ClientLevel;

public final class CloudFields {

    private CloudFields() {
    }

    public static CloudField current(ClientLevel level, float partialTick) {
        if (BetterCloudsCompat.ownsClouds(level)) {
            return BetterCloudsCompat.field(level, partialTick);
        }
        return VanillaClouds.field(level, partialTick);
    }

    public static void invalidate() {
        VanillaClouds.invalidate();
    }
}
