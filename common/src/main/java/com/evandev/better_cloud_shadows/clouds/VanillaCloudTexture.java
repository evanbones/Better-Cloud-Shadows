package com.evandev.better_cloud_shadows.clouds;

import com.evandev.better_cloud_shadows.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;

public final class VanillaCloudTexture {

    private static final ResourceLocation CLOUDS_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");

    private static float[] alpha;
    private static int width;
    private static int height;
    private static long signature;
    private static boolean failed;

    private VanillaCloudTexture() {
    }

    public static boolean ensureLoaded() {
        if (alpha != null) return true;
        if (failed) return false;

        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(CLOUDS_TEXTURE);
        if (resource.isEmpty()) {
            failed = true;
            Constants.LOG.warn("Missing {}, cloud shadows are unavailable", CLOUDS_TEXTURE);
            return false;
        }

        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();
            if (imageWidth <= 0 || imageHeight <= 0) {
                failed = true;
                return false;
            }

            float[] pixels = new float[imageWidth * imageHeight];
            for (int y = 0; y < imageHeight; y++) {
                for (int x = 0; x < imageWidth; x++) {
                    pixels[y * imageWidth + x] = (image.getPixelRGBA(x, y) >>> 24) / 255f;
                }
            }
            width = imageWidth;
            height = imageHeight;
            alpha = pixels;
            signature = ((long) imageWidth << 32 | imageHeight) * 31 + Arrays.hashCode(pixels);
            return true;
        } catch (IOException e) {
            failed = true;
            Constants.LOG.error("Failed to read {}", CLOUDS_TEXTURE, e);
            return false;
        }
    }

    public static float alphaAt(int x, int z) {
        float[] pixels = alpha;
        if (pixels == null) return 0;
        return pixels[Math.floorMod(z, height) * width + Math.floorMod(x, width)];
    }

    public static long signature() {
        return signature;
    }

    public static void invalidate() {
        alpha = null;
        failed = false;
    }
}
