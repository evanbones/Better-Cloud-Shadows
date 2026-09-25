package com.evandev.better_cloud_shadows.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.Vec3;

public final class BlockLightTexture implements AutoCloseable {

    public static final int CHUNKS_RADIUS = 16;
    public static final int CHUNKS_SPAN = CHUNKS_RADIUS * 2; // 32 chunks
    public static final int SIZE = CHUNKS_SPAN * 16; // 512 blocks

    private final DynamicTexture texture;
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    private int originX;
    private int originZ;
    private int lastMinSecX = Integer.MIN_VALUE;
    private int lastMinSecZ = Integer.MIN_VALUE;
    private boolean lastAffectedByLights;
    private int lastLoadedChunks = -1;
    private long lastSignature;
    private long lastCheckGameTime = -1;
    private boolean hasAnyLight;
    private boolean populated;

    public BlockLightTexture() {
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

    public boolean hasAnyLight() {
        return hasAnyLight;
    }

    public void update(ClientLevel level, Camera camera, boolean affectedByLights) {
        Vec3 camPos = camera.getPosition();
        int camSecX = Mth.floor(camPos.x) >> 4;
        int camSecZ = Mth.floor(camPos.z) >> 4;

        int minSecX = camSecX - CHUNKS_RADIUS;
        int minSecZ = camSecZ - CHUNKS_RADIUS;

        boolean moved = (minSecX != lastMinSecX || minSecZ != lastMinSecZ);
        boolean modeChanged = (affectedByLights != lastAffectedByLights);
        int loadedChunks = level.getChunkSource().getLoadedChunksCount();
        long gameTime = level.getGameTime();

        LayerLightEventListener blockListener = affectedByLights
                ? level.getLightEngine().getLayerListener(LightLayer.BLOCK)
                : null;

        if (populated && !moved && !modeChanged && loadedChunks == lastLoadedChunks) {
            if (!affectedByLights || gameTime - lastCheckGameTime < 10) {
                return;
            }
            long checkSig = computeLightSignature(level, blockListener, camSecX, camSecZ);
            if (checkSig == lastSignature) {
                return;
            }
        }
        lastCheckGameTime = gameTime;

        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;

        hasAnyLight = false;

        int renderDist = Minecraft.getInstance().options.getEffectiveRenderDistance();
        int chunkRadius = Math.min(CHUNKS_RADIUS, renderDist + 1);

        for (int dz = 0; dz < CHUNKS_SPAN; dz++) {
            int secZ = minSecZ + dz;
            int basePixelY = dz << 4;
            int distZ = Math.abs(secZ - camSecZ);

            for (int dx = 0; dx < CHUNKS_SPAN; dx++) {
                int secX = minSecX + dx;
                int basePixelX = dx << 4;
                int distX = Math.abs(secX - camSecX);

                if (distX > chunkRadius || distZ > chunkRadius) {
                    pixels.fillRect(basePixelX, basePixelY, 16, 16, 0);
                    continue;
                }

                ChunkAccess chunk = level.getChunk(secX, secZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    pixels.fillRect(basePixelX, basePixelY, 16, 16, 0);
                    continue;
                }

                boolean chunkHasBlockLight = false;
                if (affectedByLights && blockListener != null) {
                    int minSec = chunk.getMinSection();
                    int maxSec = chunk.getMaxSection();
                    for (int sy = minSec; sy < maxSec; sy++) {
                        DataLayer dl = blockListener.getDataLayerData(SectionPos.of(secX, sy, secZ));
                        if (dl != null && !dl.isEmpty()) {
                            chunkHasBlockLight = true;
                            break;
                        }
                    }
                }

                int blockBaseX = secX << 4;
                int blockBaseZ = secZ << 4;

                for (int lz = 0; lz < 16; lz++) {
                    int worldZ = blockBaseZ + lz;
                    int pixelY = basePixelY + lz;

                    for (int lx = 0; lx < 16; lx++) {
                        int worldX = blockBaseX + lx;
                        int pixelX = basePixelX + lx;

                        int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);

                        int light = 0;
                        if (chunkHasBlockLight) {
                            mutablePos.set(worldX, surfaceY + 1, worldZ);
                            light = blockListener.getLightValue(mutablePos);
                            if (light < 15) {
                                mutablePos.set(worldX, surfaceY, worldZ);
                                int surfaceLight = blockListener.getLightValue(mutablePos);
                                if (surfaceLight > light) light = surfaceLight;
                            }
                            if (light > 0) hasAnyLight = true;
                        }

                        int encodedY = Mth.clamp(surfaceY + 1024, 0, 65535);
                        int r = light * 17;
                        int g = encodedY & 255;
                        int b = (encodedY >> 8) & 255;
                        pixels.setPixelRGBA(pixelX, pixelY, (255 << 24) | (b << 16) | (g << 8) | r);
                    }
                }
            }
        }

        lastSignature = affectedByLights ? computeLightSignature(level, blockListener, camSecX, camSecZ) : 0L;
        lastMinSecX = minSecX;
        lastMinSecZ = minSecZ;
        lastAffectedByLights = affectedByLights;
        lastLoadedChunks = loadedChunks;
        originX = minSecX << 4;
        originZ = minSecZ << 4;
        populated = true;

        RenderSystem.assertOnRenderThread();
        texture.upload();
    }

    private long computeLightSignature(ClientLevel level, LayerLightEventListener blockListener, int camSecX, int camSecZ) {
        if (blockListener == null) return 0L;
        long sig = 1L;
        for (int dz = -4; dz <= 4; dz++) {
            int secZ = camSecZ + dz;
            for (int dx = -4; dx <= 4; dx++) {
                int secX = camSecX + dx;
                ChunkAccess chunk = level.getChunk(secX, secZ, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                int minSec = chunk.getMinSection();
                int maxSec = chunk.getMaxSection();
                for (int sy = minSec; sy < maxSec; sy++) {
                    DataLayer dl = blockListener.getDataLayerData(SectionPos.of(secX, sy, secZ));
                    if (dl != null && !dl.isEmpty()) {
                        sig = sig * 31 + System.identityHashCode(dl);
                        sig = sig * 31 + sy;
                    }
                }
            }
        }
        return sig;
    }

    @Override
    public void close() {
        texture.close();
    }
}
