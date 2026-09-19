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
    private static final float MAX_BLUR_SIGMA = 5f;
    private static final float SIGMA_STEP = 16f;

    private static final int PADDED = SIZE + PAD * 2;

    private final DynamicTexture texture;
    private final Slice[] slices = new Slice[CloudField.MAX_LAYERS];
    private final float[] work = new float[PADDED * PADDED];
    private final float[] scratch = new float[PADDED * PADDED];

    private int uploadedLayers;

    public CloudCoverageTexture() {
        this.texture = new DynamicTexture(SIZE, SIZE, false);
        for (int i = 0; i < slices.length; i++) {
            slices[i] = new Slice();
        }
    }

    private static int toByte(float value) {
        return Mth.clamp(Math.round(value * 255f), 0, 255);
    }

    public static float blurRadius(float sigma) {
        float target = Mth.clamp(Math.round(sigma * SIGMA_STEP) / SIGMA_STEP, 0f, MAX_BLUR_SIGMA);
        if (target <= 0) return 0;

        float variance = target * target * 0.5f;
        for (int whole = 0; whole < MAX_BLUR_RADIUS; whole++) {
            float inner = whole * (whole + 1) * (2 * whole + 1) / 3f;
            float outer = (whole + 1) * (whole + 1);
            float weight = 1 + 2 * whole;
            if (variance > (inner + 2 * outer) / (weight + 2)) continue;

            float edge = (variance * weight - inner) / (2 * (outer - variance));
            return whole + Mth.clamp(edge, 0f, 1f);
        }
        return MAX_BLUR_RADIUS;
    }

    public int textureId() {
        return texture.getId();
    }

    public int originX(int layer) {
        return slices[layer].originX;
    }

    public int originZ(int layer) {
        return slices[layer].originZ;
    }

    public int uploadedLayers() {
        return uploadedLayers;
    }

    public boolean update(int layer, CloudField.Layer source, int centerX, int centerZ, int dilate, float blur) {
        Slice slice = slices[layer];

        int wantedX = centerX - SIZE / 2;
        int wantedZ = centerZ - SIZE / 2;
        int dx = wantedX - slice.originX;
        int dz = wantedZ - slice.originZ;

        boolean resample = !slice.populated || source.signature() != slice.signature;
        boolean recenter = Math.abs(dx) > RECENTER_MARGIN || Math.abs(dz) > RECENTER_MARGIN;
        if (!resample && !recenter && dilate == slice.builtDilate && blur == slice.builtBlur) return false;

        slice.signature = source.signature();
        slice.builtDilate = dilate;
        slice.builtBlur = blur;

        if (resample || Math.abs(dx) >= PADDED || Math.abs(dz) >= PADDED) {
            slice.originX = wantedX;
            slice.originZ = wantedZ;
            sample(slice, source, 0, 0, PADDED, PADDED);
        } else if (recenter) {
            slice.originX = wantedX;
            slice.originZ = wantedZ;
            scroll(slice, source, dx, dz);
        }
        slice.populated = true;

        System.arraycopy(slice.raw, 0, work, 0, slice.raw.length);
        if (dilate > 0) dilate(dilate);
        if (blur > 0) {
            blur(blur);
            blur(blur);
        }
        System.arraycopy(work, 0, slice.filtered, 0, work.length);
        return true;
    }

    public void upload(int layerCount) {
        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;

        float[] red = slices[0].filtered;
        float[] green = layerCount > 1 ? slices[1].filtered : null;
        float[] blue = layerCount > 2 ? slices[2].filtered : null;
        float[] alpha = layerCount > 3 ? slices[3].filtered : null;

        for (int y = 0; y < SIZE; y++) {
            int row = (y + PAD) * PADDED + PAD;
            for (int x = 0; x < SIZE; x++) {
                int r = toByte(red[row + x]);
                int g = green == null ? 0 : toByte(green[row + x]);
                int b = blue == null ? 0 : toByte(blue[row + x]);
                int a = alpha == null ? 0 : toByte(alpha[row + x]);
                pixels.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        RenderSystem.assertOnRenderThread();
        texture.bind();
        pixels.upload(0, 0, 0, 0, 0, SIZE, SIZE, true, true, false, false);
        uploadedLayers = layerCount;
    }

    private void scroll(Slice slice, CloudField.Layer source, int dx, int dz) {
        float[] raw = slice.raw;
        int keptWidth = PADDED - Math.abs(dx);
        int srcColumn = Math.max(dx, 0);
        int dstColumn = Math.max(-dx, 0);

        int from = dz > 0 ? 0 : PADDED - 1;
        int to = dz > 0 ? PADDED : -1;
        int direction = dz > 0 ? 1 : -1;
        for (int y = from; y != to; y += direction) {
            int src = y + dz;
            if (src < 0 || src >= PADDED) continue;
            System.arraycopy(raw, src * PADDED + srcColumn, raw, y * PADDED + dstColumn, keptWidth);
        }

        if (dz > 0) {
            sample(slice, source, 0, PADDED - dz, PADDED, PADDED);
        } else if (dz < 0) {
            sample(slice, source, 0, 0, PADDED, -dz);
        }

        int rowStart = Math.max(-dz, 0);
        int rowEnd = PADDED - Math.max(dz, 0);
        if (dx > 0) {
            sample(slice, source, PADDED - dx, rowStart, PADDED, rowEnd);
        } else if (dx < 0) {
            sample(slice, source, 0, rowStart, -dx, rowEnd);
        }
    }

    private void sample(Slice slice, CloudField.Layer source, int minX, int minZ, int maxX, int maxZ) {
        CloudField.Coverage coverage = source.coverage();
        float[] raw = slice.raw;
        for (int y = minZ; y < maxZ; y++) {
            int cellZ = slice.originZ - PAD + y;
            int row = y * PADDED;
            for (int x = minX; x < maxX; x++) {
                raw[row + x] = coverage.at(slice.originX - PAD + x, cellZ);
            }
        }
    }

    private void dilate(int radius) {
        for (int y = 0; y < PADDED; y++) {
            int row = y * PADDED;
            for (int x = 0; x < PADDED; x++) {
                float best = 0;
                for (int d = -radius; d <= radius; d++) {
                    float value = work[row + Mth.clamp(x + d, 0, PADDED - 1)];
                    if (value > best) best = value;
                }
                scratch[row + x] = best;
            }
        }
        for (int y = 0; y < PADDED; y++) {
            for (int x = 0; x < PADDED; x++) {
                float best = 0;
                for (int d = -radius; d <= radius; d++) {
                    float value = scratch[Mth.clamp(y + d, 0, PADDED - 1) * PADDED + x];
                    if (value > best) best = value;
                }
                work[y * PADDED + x] = best;
            }
        }
    }

    private void blur(float radius) {
        int whole = (int) radius;
        float edge = radius - whole;
        float weight = 1f / (1f + 2f * whole + 2f * edge);

        for (int y = 0; y < PADDED; y++) {
            int row = y * PADDED;
            for (int x = 0; x < PADDED; x++) {
                float sum = work[row + x];
                for (int d = 1; d <= whole; d++) {
                    sum += work[row + Mth.clamp(x - d, 0, PADDED - 1)];
                    sum += work[row + Mth.clamp(x + d, 0, PADDED - 1)];
                }
                if (edge > 0) {
                    sum += edge * work[row + Mth.clamp(x - whole - 1, 0, PADDED - 1)];
                    sum += edge * work[row + Mth.clamp(x + whole + 1, 0, PADDED - 1)];
                }
                scratch[row + x] = sum * weight;
            }
        }
        for (int y = 0; y < PADDED; y++) {
            for (int x = 0; x < PADDED; x++) {
                float sum = scratch[y * PADDED + x];
                for (int d = 1; d <= whole; d++) {
                    sum += scratch[Mth.clamp(y - d, 0, PADDED - 1) * PADDED + x];
                    sum += scratch[Mth.clamp(y + d, 0, PADDED - 1) * PADDED + x];
                }
                if (edge > 0) {
                    sum += edge * scratch[Mth.clamp(y - whole - 1, 0, PADDED - 1) * PADDED + x];
                    sum += edge * scratch[Mth.clamp(y + whole + 1, 0, PADDED - 1) * PADDED + x];
                }
                work[y * PADDED + x] = sum * weight;
            }
        }
    }

    @Override
    public void close() {
        texture.close();
    }

    private static final class Slice {
        final float[] raw = new float[PADDED * PADDED];
        final float[] filtered = new float[PADDED * PADDED];

        int originX;
        int originZ;
        long signature;
        int builtDilate = -1;
        float builtBlur = -1;
        boolean populated;
    }
}
