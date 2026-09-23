package com.evandev.better_cloud_shadows.clouds;

import com.evandev.better_cloud_shadows.compat.betterclouds.BetterCloudsCompat;
import com.evandev.better_cloud_shadows.compat.cirrus.CirrusCompat;
import com.evandev.better_cloud_shadows.compat.cloudlayers.CloudLayersCompat;
import com.evandev.better_cloud_shadows.compat.cloudtweaks.CloudTweaksCompat;
import com.evandev.better_cloud_shadows.compat.distanthorizons.DistantHorizonsCompat;
import com.evandev.better_cloud_shadows.config.ModConfig;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public final class CloudFields {

    private CloudFields() {
    }

    public static CloudField current(ClientLevel level, float partialTick) {
        CloudField near = null;
        if (CirrusCompat.ownsClouds()) {
            near = CirrusCompat.field(level, partialTick);
        } else if (Minecraft.getInstance().options.getCloudsType() != CloudStatus.OFF) {
            if (CloudTweaksCompat.ownsClouds()) {
                near = CloudTweaksCompat.field(level, partialTick);
            } else if (CloudLayersCompat.ownsClouds()) {
                near = CloudLayersCompat.field(level, partialTick);
            } else if (BetterCloudsCompat.ownsClouds(level)) {
                near = BetterCloudsCompat.field(level, partialTick);
            } else {
                near = VanillaClouds.field(level, partialTick);
            }
        }

        CloudField distant = ModConfig.get().distantHorizonsClouds
                ? DistantHorizonsCompat.field()
                : null;

        return CloudField.merge(near, distant);
    }

    public static void invalidate() {
        VanillaClouds.invalidate();
        CirrusCompat.invalidate();
        CloudLayersCompat.invalidate();
        CloudTweaksCompat.invalidate();
        DistantHorizonsCompat.invalidate();
    }
}
