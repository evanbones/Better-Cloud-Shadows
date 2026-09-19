package com.evandev.better_cloud_shadows.client;

import com.evandev.better_cloud_shadows.clouds.CloudField;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;

public final class CloudCoverageTexture implements AutoCloseable {

    public static final int SIZE = 256;
    public static final int MAX_DILATE_RADIUS = 4;
    public static final int MAX_BLUR_RADIUS = 6;
    private static final int RECENTER_MARGIN = 4;
    public static final int SAFE_RADIUS = SIZE / 2 - RECENTER_MARGIN;
    private static final int PAD = 16;
    private final DynamicTexture texture;
    private final int padded = SIZE + PAD * 2;
    private final float[] raw = new float[padded * padded];
    private final float[] work = new float[padded * padded];
    private final float[] scratch = new float[padded * padded];

    private int originX;
    private int originZ;
    private long signature;
    private int builtDilate = -1;
    private float builtBlur = -1;
    private boolean populated;

    public CloudCoverageTexture() {
        this.texture = new DynamicTexture(SIZE, SIZE, false);
    }

    public int textureId() {
        return texture.getId();
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public void update(CloudField field, int centerX, int centerZ, int dilate, float blur) {
        int wantedX = centerX - SIZE / 2;
        int wantedZ = centerZ - SIZE / 2;
        int dx = wantedX - originX;
        int dz = wantedZ - originZ;

        boolean resample = !populated || field.signature() != signature;
        boolean recenter = Math.abs(dx) > RECENTER_MARGIN || Math.abs(dz) > RECENTER_MARGIN;
        if (!resample && !recenter && dilate == builtDilate && blur == builtBlur) return;

        this.signature = field.signature();
        this.builtDilate = dilate;
        this.builtBlur = blur;

        if (resample || Math.abs(dx) >= padded || Math.abs(dz) >= padded) {
            originX = wantedX;
            originZ = wantedZ;
            sample(field, 0, 0, padded, padded);
        } else if (recenter) {
            originX = wantedX;
            originZ = wantedZ;
            scroll(field, dx, dz);
        }
        populated = true;

        System.arraycopy(raw, 0, work, 0, raw.length);
        if (dilate > 0) dilate(dilate);
        if (blur > 0) {
            blur(blur);
            blur(blur);
        }
        upload();
    }

    private void scroll(CloudField field, int dx, int dz) {
        int keptWidth = padded - Math.abs(dx);
        int srcColumn = Math.max(dx, 0);
        int dstColumn = Math.max(-dx, 0);

        int from = dz > 0 ? 0 : padded - 1;
        int to = dz > 0 ? padded : -1;
        int direction = dz > 0 ? 1 : -1;
        for (int y = from; y != to; y += direction) {
            int src = y + dz;
            if (src < 0 || src >= padded) continue;
            System.arraycopy(raw, src * padded + srcColumn, raw, y * padded + dstColumn, keptWidth);
        }

        if (dz > 0) {
            sample(field, 0, padded - dz, padded, padded);
        } else if (dz < 0) {
            sample(field, 0, 0, padded, -dz);
        }

        int rowStart = Math.max(-dz, 0);
        int rowEnd = padded - Math.max(dz, 0);
        if (dx > 0) {
            sample(field, padded - dx, rowStart, padded, rowEnd);
        } else if (dx < 0) {
            sample(field, 0, rowStart, -dx, rowEnd);
        }
    }

    private void sample(CloudField field, int minX, int minZ, int maxX, int maxZ) {
        CloudField.Coverage coverage = field.coverage();
        for (int y = minZ; y < maxZ; y++) {
            int cellZ = originZ - PAD + y;
            int row = y * padded;
            for (int x = minX; x < maxX; x++) {
                raw[row + x] = coverage.at(originX - PAD + x, cellZ);
            }
        }
    }

    private void dilate(int radius) {
        for (int y = 0; y < padded; y++) {
            int row = y * padded;
            for (int x = 0; x < padded; x++) {
                float best = 0;
                for (int d = -radius; d <= radius; d++) {
                    float value = work[row + Mth.clamp(x + d, 0, padded - 1)];
                    if (value > best) best = value;
                }
                scratch[row + x] = best;
            }
        }
        for (int y = 0; y < padded; y++) {
            for (int x = 0; x < padded; x++) {
                float best = 0;
                for (int d = -radius; d <= radius; d++) {
                    float value = scratch[Mth.clamp(y + d, 0, padded - 1) * padded + x];
                    if (value > best) best = value;
                }
                work[y * padded + x] = best;
            }
        }
    }

    private void blur(float radius) {
        int whole = (int) radius;
        float edge = radius - whole;
        float weight = 1f / (1f + 2f * whole + 2f * edge);

        for (int y = 0; y < padded; y++) {
            int row = y * padded;
            for (int x = 0; x < padded; x++) {
                float sum = work[row + x];
                for (int d = 1; d <= whole; d++) {
                    sum += work[row + Mth.clamp(x - d, 0, padded - 1)];
                    sum += work[row + Mth.clamp(x + d, 0, padded - 1)];
                }
                if (edge > 0) {
                    sum += edge * work[row + Mth.clamp(x - whole - 1, 0, padded - 1)];
                    sum += edge * work[row + Mth.clamp(x + whole + 1, 0, padded - 1)];
                }
                scratch[row + x] = sum * weight;
            }
        }
        for (int y = 0; y < padded; y++) {
            for (int x = 0; x < padded; x++) {
                float sum = scratch[y * padded + x];
                for (int d = 1; d <= whole; d++) {
                    sum += scratch[Mth.clamp(y - d, 0, padded - 1) * padded + x];
                    sum += scratch[Mth.clamp(y + d, 0, padded - 1) * padded + x];
                }
                if (edge > 0) {
                    sum += edge * scratch[Mth.clamp(y - whole - 1, 0, padded - 1) * padded + x];
                    sum += edge * scratch[Mth.clamp(y + whole + 1, 0, padded - 1) * padded + x];
                }
                work[y * padded + x] = sum * weight;
            }
        }
    }

    private void upload() {
        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;

        for (int y = 0; y < SIZE; y++) {
            int row = (y + PAD) * padded + PAD;
            for (int x = 0; x < SIZE; x++) {
                int value = Mth.clamp(Math.round(work[row + x] * 255f), 0, 255);
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
