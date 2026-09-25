package com.evandev.better_cloud_shadows.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

public final class BlockLightTexture implements AutoCloseable {

    public static final int CHUNKS_RADIUS = 32;
    public static final int CHUNKS_SPAN = CHUNKS_RADIUS * 2; // 64 chunks
    public static final int SIZE = CHUNKS_SPAN * 16; // 1024 blocks

    private final DynamicTexture texture;
    private final IntArrayList litChunks = new IntArrayList();
    private final IntArrayList tempLitChunks = new IntArrayList();

    private int originX;
    private int originZ;
    private int lastMinSecX = Integer.MIN_VALUE;
    private int lastMinSecZ = Integer.MIN_VALUE;
    private long lastSignature;
    private boolean hasAnyLight;
    private boolean populated;

    public BlockLightTexture() {
        this.texture = new DynamicTexture(SIZE, SIZE, true);
    }

    public int textureId() {
        return texture.getId();
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return 0;
    }

    public int originZ() {
        return originZ;
    }

    public boolean hasAnyLight() {
        return hasAnyLight;
    }

    public boolean update(ClientLevel level, Camera camera) {
        Vec3 camPos = camera.getPosition();
        int camSecX = Mth.floor(camPos.x) >> 4;
        int camSecZ = Mth.floor(camPos.z) >> 4;

        int minSecX = camSecX - CHUNKS_RADIUS;
        int minSecZ = camSecZ - CHUNKS_RADIUS;

        boolean moved = (minSecX != lastMinSecX || minSecZ != lastMinSecZ);

        int renderDist = Minecraft.getInstance().options.getEffectiveRenderDistance();
        int chunkRadius = Math.min(CHUNKS_RADIUS, renderDist + 1);

        LayerLightEventListener blockListener = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        long signature = ((long) minSecX * 31 + minSecZ) * 31;
        tempLitChunks.clear();

        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            int secZ = camSecZ + dz;
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                int secX = camSecX + dx;
                ChunkAccess chunk = level.getChunk(secX, secZ, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }

                if (minY > maxY) continue;

                int startSecY = (minY - 1) >> 4;
                int endSecY = (maxY + 2) >> 4;

                boolean chunkHasLight = false;
                for (int sy = startSecY; sy <= endSecY; sy++) {
                    DataLayer dl = blockListener.getDataLayerData(SectionPos.of(secX, sy, secZ));
                    if (dl != null && !dl.isEmpty()) {
                        chunkHasLight = true;
                        signature = signature * 31 + Arrays.hashCode(dl.getData());
                        signature = signature * 31 + secX;
                        signature = signature * 31 + sy;
                        signature = signature * 31 + secZ;
                    }
                }

                if (chunkHasLight) {
                    int relSecX = secX - minSecX;
                    int relSecZ = secZ - minSecZ;
                    tempLitChunks.add(relSecX | (relSecZ << 8) | ((startSecY + 128) << 16) | ((endSecY + 128) << 24));
                }
            }
        }

        if (populated && !moved && signature == lastSignature) {
            return false;
        }

        NativeImage pixels = texture.getPixels();
        if (pixels == null) return false;

        // Clear previously lit chunk areas
        for (int i = 0; i < litChunks.size(); i++) {
            int packed = litChunks.getInt(i);
            int relSecX = packed & 0xFF;
            int relSecZ = (packed >> 8) & 0xFF;
            pixels.fillRect(relSecX << 4, relSecZ << 4, 16, 16, 0);
        }

        for (int i = 0; i < tempLitChunks.size(); i++) {
            int packed = tempLitChunks.getInt(i);
            int relSecX = packed & 0xFF;
            int relSecZ = (packed >> 8) & 0xFF;
            int startSecY = ((packed >> 16) & 0xFF) - 128;
            int endSecY = ((packed >> 24) & 0xFF) - 128;
            int secX = minSecX + relSecX;
            int secZ = minSecZ + relSecZ;

            ChunkAccess chunk = level.getChunk(secX, secZ, ChunkStatus.FULL, false);
            if (chunk == null) continue;

            int secCount = endSecY - startSecY + 1;
            DataLayer[] layers = new DataLayer[secCount];
            for (int s = 0; s < secCount; s++) {
                layers[s] = blockListener.getDataLayerData(SectionPos.of(secX, startSecY + s, secZ));
            }

            int basePixelX = relSecX << 4;
            int basePixelY = relSecZ << 4;

            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                    int airY = surfaceY + 1;

                    int light = getLight(layers, startSecY, lx, airY, lz);
                    int lightSurface = getLight(layers, startSecY, lx, surfaceY, lz);
                    if (lightSurface > light) light = lightSurface;
                    int lightAbove = getLight(layers, startSecY, lx, surfaceY + 2, lz);
                    if (lightAbove > light) light = lightAbove;

                    int pixelX = basePixelX + lx;
                    int pixelY = basePixelY + lz;

                    int encodedY = Mth.clamp(surfaceY + 1024, 0, 65535);
                    int r = light * 17;
                    int g = encodedY & 255;
                    int b = (encodedY >> 8) & 255;
                    pixels.setPixelRGBA(pixelX, pixelY, (255 << 24) | (b << 16) | (g << 8) | r);
                }
            }
        }

        litChunks.clear();
        litChunks.addAll(tempLitChunks);

        lastSignature = signature;
        lastMinSecX = minSecX;
        lastMinSecZ = minSecZ;
        originX = minSecX << 4;
        originZ = minSecZ << 4;
        hasAnyLight = !litChunks.isEmpty();
        populated = true;

        RenderSystem.assertOnRenderThread();
        texture.upload();
        return true;
    }

    private static int getLight(DataLayer[] layers, int startSecY, int x, int y, int z) {
        int secY = y >> 4;
        int idx = secY - startSecY;
        if (idx >= 0 && idx < layers.length) {
            DataLayer dl = layers[idx];
            if (dl != null && !dl.isEmpty()) {
                return dl.get(x, y & 15, z);
            }
        }
        return 0;
    }

    @Override
    public void close() {
        texture.close();
    }
}
