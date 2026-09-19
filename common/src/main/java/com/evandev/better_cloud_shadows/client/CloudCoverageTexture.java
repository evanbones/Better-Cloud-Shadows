package com.evandev.better_cloud_shadows.client;

import com.evandev.better_cloud_shadows.compat.betterclouds.BetterCloudsCompat;
import com.evandev.better_cloud_shadows.compat.betterclouds.CloudField;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;

public final class CloudCoverageTexture implements AutoCloseable {

    public static final int SIZE = 128;
    private static final int RECENTER_MARGIN = SIZE / 4;
    private static final int MAX_PAD = 12;

    private final DynamicTexture texture;
    private final int paddedSize = SIZE + MAX_PAD * 2;
    private final float[] front = new float[paddedSize * paddedSize];
    private final float[] back = new float[paddedSize * paddedSize];

    private int originGridX;
    private int originGridZ;
    private long signature = Long.MIN_VALUE;
    private int builtDilate = -1;
    private int builtBlur = -1;
    private boolean populated;

    public CloudCoverageTexture() {
        this.texture = new DynamicTexture(SIZE, SIZE, false);
    }

    public int textureId() {
        return texture.getId();
    }

    public int originGridX() {
        return originGridX;
    }

    public int originGridZ() {
        return originGridZ;
    }

    public void update(CloudField field, int centerGridX, int centerGridZ, int dilateRadius, int blurRadius) {
        int wantedOriginX = centerGridX - SIZE / 2;
        int wantedOriginZ = centerGridZ - SIZE / 2;

        boolean stale = !populated
                || field.signature() != signature
                || dilateRadius != builtDilate
                || blurRadius != builtBlur
                || Math.abs(wantedOriginX - originGridX) > RECENTER_MARGIN
                || Math.abs(wantedOriginZ - originGridZ) > RECENTER_MARGIN;
        if (!stale) return;

        originGridX = wantedOriginX;
        originGridZ = wantedOriginZ;
        signature = field.signature();
        builtDilate = dilateRadius;
        builtBlur = blurRadius;
        populated = true;

        samplePresence(field);
        if (dilateRadius > 0) dilate(dilateRadius);
        if (blurRadius > 0) {
            blur(blurRadius);
            blur(blurRadius);
        }
        upload();
    }

    private void samplePresence(CloudField field) {
        for (int y = 0; y < paddedSize; y++) {
            int gridZ = originGridZ - MAX_PAD + y;
            int row = y * paddedSize;
            for (int x = 0; x < paddedSize; x++) {
                front[row + x] = BetterCloudsCompat.coverage(originGridX - MAX_PAD + x, gridZ, field);
            }
        }
    }

    private void dilate(int radius) {
        for (int y = 0; y < paddedSize; y++) {
            int row = y * paddedSize;
            for (int x = 0; x < paddedSize; x++) {
                float best = 0;
                for (int d = -radius; d <= radius; d++) {
                    int sx = Mth.clamp(x + d, 0, paddedSize - 1);
                    float value = front[row + sx];
                    if (value > best) best = value;
                }
                back[row + x] = best;
            }
        }
        for (int y = 0; y < paddedSize; y++) {
            for (int x = 0; x < paddedSize; x++) {
                float best = 0;
                for (int d = -radius; d <= radius; d++) {
                    int sy = Mth.clamp(y + d, 0, paddedSize - 1);
                    float value = back[sy * paddedSize + x];
                    if (value > best) best = value;
                }
                front[y * paddedSize + x] = best;
            }
        }
    }

    private void blur(int radius) {
        float weight = 1f / (radius * 2 + 1);
        for (int y = 0; y < paddedSize; y++) {
            int row = y * paddedSize;
            for (int x = 0; x < paddedSize; x++) {
                float sum = 0;
                for (int d = -radius; d <= radius; d++) {
                    sum += front[row + Mth.clamp(x + d, 0, paddedSize - 1)];
                }
                back[row + x] = sum * weight;
            }
        }
        for (int y = 0; y < paddedSize; y++) {
            for (int x = 0; x < paddedSize; x++) {
                float sum = 0;
                for (int d = -radius; d <= radius; d++) {
                    sum += back[Mth.clamp(y + d, 0, paddedSize - 1) * paddedSize + x];
                }
                front[y * paddedSize + x] = sum * weight;
            }
        }
    }

    private void upload() {
        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;

        for (int y = 0; y < SIZE; y++) {
            int row = (y + MAX_PAD) * paddedSize + MAX_PAD;
            for (int x = 0; x < SIZE; x++) {
                int value = Mth.clamp(Math.round(front[row + x] * 255f), 0, 255);
                pixels.setPixelRGBA(x, y, 0xFF000000 | (value << 16) | (value << 8) | value);
            }
        }

        RenderSystem.assertOnRenderThread();
        texture.bind();
        pixels.upload(0, 0, 0, 0, 0, SIZE, SIZE, true, true, false, false);
    }

    @Override
    public void close() {
        texture.close();
    }
}
