package com.evandev.better_cloud_shadows.clouds;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.mixin.LevelRendererAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;

public final class VanillaClouds {

    private static final ResourceLocation CLOUDS_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");

    private static final float CELL_SIZE = 12f;
    private static final float TRAVEL_SPEED = 0.03f;
    private static final float Z_OFFSET = 0.33f;
    private static final float SHADOW_DISTANCE = CELL_SIZE * 32f;

    private static float[] alpha;
    private static int width;
    private static int height;
    private static long signature;
    private static boolean failed;

    private VanillaClouds() {
    }

    public static CloudField field(ClientLevel level, float partialTick) {
        float cloudHeight = level.effects().getCloudHeight();
        if (Float.isNaN(cloudHeight)) return null;

        if (!ensureLoaded()) return null;

        int ticks = ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer).better_cloud_shadows$ticks();
        double travel = (ticks + partialTick) * TRAVEL_SPEED;

        return new CloudField(
                -travel,
                -Z_OFFSET * CELL_SIZE,
                CELL_SIZE,
                CELL_SIZE,
                cloudHeight,
                SHADOW_DISTANCE,
                signature,
                VanillaClouds::coverage
        );
    }

    public static void invalidate() {
        alpha = null;
        failed = false;
    }

    private static float coverage(int cellX, int cellZ) {
        float[] pixels = alpha;
        if (pixels == null) return 0;
        return pixels[Math.floorMod(cellZ, height) * width + Math.floorMod(cellX, width)];
    }

    private static boolean ensureLoaded() {
        if (alpha != null) return true;
        if (failed) return false;

        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(CLOUDS_TEXTURE);
        if (resource.isEmpty()) {
            failed = true;
            Constants.LOG.warn("Missing {}, cloud shadows are unavailable", CLOUDS_TEXTURE);
            return false;
        }

        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            width = image.getWidth();
            height = image.getHeight();
            if (width <= 0 || height <= 0) {
                failed = true;
                return false;
            }

            float[] pixels = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = (image.getPixelRGBA(x, y) >>> 24) / 255f;
                }
            }
            alpha = pixels;
            signature = ((long) width << 32 | height) * 31 + Arrays.hashCode(pixels);
            return true;
        } catch (IOException e) {
            failed = true;
            Constants.LOG.error("Failed to read {}", CLOUDS_TEXTURE, e);
            return false;
        }
    }
}
