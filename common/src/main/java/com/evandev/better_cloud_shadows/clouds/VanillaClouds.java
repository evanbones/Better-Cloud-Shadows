package com.evandev.better_cloud_shadows.clouds;

import com.evandev.better_cloud_shadows.mixin.LevelRendererAccessor;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public final class VanillaClouds {

    private static final float CELL_SIZE = 12f;
    private static final float TRAVEL_SPEED = 0.03f;
    private static final float Z_OFFSET = 0.33f;
    private static final float FANCY_THICKNESS = 4f;
    private static final float SHADOW_DISTANCE = CELL_SIZE * 32f;

    private VanillaClouds() {
    }

    public static CloudField field(ClientLevel level, float partialTick) {
        float cloudHeight = level.effects().getCloudHeight();
        if (Float.isNaN(cloudHeight)) return null;

        if (!VanillaCloudTexture.ensureLoaded()) return null;

        Minecraft minecraft = Minecraft.getInstance();
        int ticks = ((LevelRendererAccessor) minecraft.levelRenderer).better_cloud_shadows$ticks();
        double travel = (ticks + partialTick) * TRAVEL_SPEED;

        float thickness = minecraft.options.getCloudsType() == CloudStatus.FANCY ? FANCY_THICKNESS : 0f;

        return CloudField.single(
                -travel,
                -Z_OFFSET * CELL_SIZE,
                CELL_SIZE,
                CELL_SIZE,
                cloudHeight,
                thickness,
                SHADOW_DISTANCE,
                VanillaCloudTexture.signature(),
                VanillaCloudTexture::alphaAt
        );
    }

    public static void invalidate() {
        VanillaCloudTexture.invalidate();
    }
}
