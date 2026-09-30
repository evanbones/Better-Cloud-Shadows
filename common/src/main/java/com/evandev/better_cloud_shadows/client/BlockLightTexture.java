package com.evandev.better_cloud_shadows.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

public final class BlockLightTexture implements AutoCloseable {

    public static final int CHUNKS_RADIUS = 16;
    public static final int CHUNKS_SPAN = CHUNKS_RADIUS * 2; // 32 chunks
    public static final int SIZE = CHUNKS_SPAN * 16; // 512 blocks

    private static final int SLOTS = CHUNKS_SPAN * CHUNKS_SPAN;
    private static final int[] SCAN_ORDER = IntStream.range(0, SLOTS)
            .map(i -> (i % CHUNKS_SPAN) | (i / CHUNKS_SPAN) << 8)
            .boxed()
            .sorted(Comparator.comparingInt(BlockLightTexture::distanceSq))
            .mapToInt(Integer::intValue)
            .toArray();
    private static final boolean[] dirtySlots = new boolean[SLOTS];

    private static final int MAX_BUILDS_PER_FRAME = 64;
    private static final int MAX_PARTIAL_UPLOADS = 64;
    private static final int[] SKY_STEPS = {2, 4, 6, 8};
    private static final int MAX_SKY_DISTANCE = SKY_STEPS[SKY_STEPS.length - 1];
    private static final int[] DIAMOND = IntStream.rangeClosed(-MAX_SKY_DISTANCE, MAX_SKY_DISTANCE)
            .flatMap(dz -> IntStream.rangeClosed(-MAX_SKY_DISTANCE, MAX_SKY_DISTANCE)
                    .map(dx -> (dx + 128) | (dz + 128) << 8))
            .filter(packed -> diamondDistance(packed) <= MAX_SKY_DISTANCE)
            .boxed()
            .sorted(Comparator.comparingInt(BlockLightTexture::diamondDistance))
            .mapToInt(Integer::intValue)
            .toArray();
    private static final int[] DIAMOND_X = Arrays.stream(DIAMOND).map(packed -> (packed & 0xFF) - 128).toArray();
    private static final int[] DIAMOND_Z = Arrays.stream(DIAMOND).map(packed -> (packed >> 8) - 128).toArray();
    private static final int[] DIAMOND_DISTANCE = Arrays.stream(DIAMOND).map(BlockLightTexture::diamondDistance).toArray();
    private static final int[] SKY_STEP_END = Arrays.stream(SKY_STEPS)
            .map(step -> (int) Arrays.stream(DIAMOND_DISTANCE).filter(distance -> distance <= step).count())
            .toArray();
    private static final int NO_SURFACE = Integer.MAX_VALUE;
    private static final long SKY_BUDGET_NS = 1_000_000L;
    private static boolean anyDirty;
    private final DynamicTexture texture;
    private final DynamicTexture skyDistanceTexture;
    private final int[] surfaceHeights = new int[SIZE * SIZE];
    private final boolean[] skyDirtySlots = new boolean[SLOTS];
    private final int[] skyTiles = new int[SLOTS];
    private final int[] skyStepMins = new int[SKY_STEPS.length];
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final long[] slotPos = new long[SLOTS];
    private final LevelChunk[] slotChunk = new LevelChunk[SLOTS];
    private final boolean[] slotHasLight = new boolean[SLOTS];
    private final int[] changedSlots = new int[SLOTS];
    private int skyDirtyCount;
    private boolean heightsChanged;
    private int litSlots;

    private int originX;
    private int originZ;
    private int lastCamSecX = Integer.MIN_VALUE;
    private int lastCamSecZ = Integer.MIN_VALUE;
    private int lastChunkRadius = -1;
    private int lastLoadedChunks = -1;
    private boolean lastAffectedByLights;
    private boolean pending = true;

    public BlockLightTexture() {
        this.texture = new DynamicTexture(SIZE, SIZE, false);
        this.skyDistanceTexture = new DynamicTexture(SIZE, SIZE, false);
        Arrays.fill(slotPos, ChunkPos.INVALID_CHUNK_POS);
        Arrays.fill(surfaceHeights, NO_SURFACE);
    }

    private static int diamondDistance(int packed) {
        return Math.abs((packed & 0xFF) - 128) + Math.abs((packed >> 8) - 128);
    }

    public static void markDirty(int secX, int secZ) {
        dirtySlots[slot(secX, secZ)] = true;
        anyDirty = true;
    }

    private static int slot(int secX, int secZ) {
        return (secZ & (CHUNKS_SPAN - 1)) * CHUNKS_SPAN + (secX & (CHUNKS_SPAN - 1));
    }

    private static int distanceSq(int packed) {
        int dx = (packed & 0xFF) - CHUNKS_RADIUS;
        int dz = (packed >> 8) - CHUNKS_RADIUS;
        return dx * dx + dz * dz;
    }

    public int textureId() {
        return texture.getId();
    }

    public int skyDistanceTextureId() {
        return skyDistanceTexture.getId();
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public boolean hasAnyLight() {
        return litSlots > 0;
    }

    public void update(ClientLevel level, Camera camera, boolean affectedByLights) {
        Vec3 camPos = camera.getPosition();
        int camSecX = Mth.floor(camPos.x) >> 4;
        int camSecZ = Mth.floor(camPos.z) >> 4;
        int chunkRadius = Math.min(CHUNKS_RADIUS, Minecraft.getInstance().options.getEffectiveRenderDistance() + 1);
        int loadedChunks = level.getChunkSource().getLoadedChunksCount();

        if (affectedByLights != lastAffectedByLights) {
            Arrays.fill(slotPos, ChunkPos.INVALID_CHUNK_POS);
            lastAffectedByLights = affectedByLights;
        } else if (!pending && !anyDirty && camSecX == lastCamSecX && camSecZ == lastCamSecZ
                && chunkRadius == lastChunkRadius && loadedChunks == lastLoadedChunks) {
            updateSkyDistances();
            return;
        }

        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;

        lastCamSecX = camSecX;
        lastCamSecZ = camSecZ;
        lastChunkRadius = chunkRadius;
        lastLoadedChunks = loadedChunks;
        originX = (camSecX - CHUNKS_RADIUS) << 4;
        originZ = (camSecZ - CHUNKS_RADIUS) << 4;
        anyDirty = false;
        pending = false;

        LayerLightEventListener blockListener = affectedByLights
                ? level.getLightEngine().getLayerListener(LightLayer.BLOCK)
                : null;

        int builds = 0;
        int changed = 0;
        for (int packed : SCAN_ORDER) {
            int dx = (packed & 0xFF) - CHUNKS_RADIUS;
            int dz = (packed >> 8) - CHUNKS_RADIUS;
            int secX = camSecX + dx;
            int secZ = camSecZ + dz;
            int slot = slot(secX, secZ);
            long pos = ChunkPos.asLong(secX, secZ);

            LevelChunk chunk = Math.abs(dx) <= chunkRadius && Math.abs(dz) <= chunkRadius
                    ? level.getChunkSource().getChunk(secX, secZ, false)
                    : null;

            boolean sameChunk = slotPos[slot] == pos;
            if (sameChunk && slotChunk[slot] == chunk && !dirtySlots[slot]) continue;

            if (chunk != null && builds >= MAX_BUILDS_PER_FRAME) {
                pending = true;
                if (sameChunk) continue;
                chunk = null;
            }

            if (chunk != null) builds++;
            dirtySlots[slot] = false;
            slotPos[slot] = pos;
            slotChunk[slot] = chunk;
            heightsChanged = false;
            setSlotHasLight(slot, writeChunk(pixels, chunk, secX, secZ, blockListener));
            if (heightsChanged) markSkyDirty(secX, secZ);
            changedSlots[changed++] = slot;
        }

        if (changed > 0) {
            RenderSystem.assertOnRenderThread();
            if (changed > MAX_PARTIAL_UPLOADS) {
                texture.upload();
            } else {
                texture.bind();
                for (int i = 0; i < changed; i++) {
                    int x = (changedSlots[i] % CHUNKS_SPAN) << 4;
                    int y = (changedSlots[i] / CHUNKS_SPAN) << 4;
                    pixels.upload(0, x, y, x, y, 16, 16, false, false);
                }
            }
        }

        updateSkyDistances();
    }

    private void markSkyDirty(int secX, int secZ) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int slot = slot(secX + dx, secZ + dz);
                if (!skyDirtySlots[slot]) {
                    skyDirtySlots[slot] = true;
                    skyDirtyCount++;
                }
            }
        }
    }

    private void updateSkyDistances() {
        if (skyDirtyCount == 0) return;
        NativeImage pixels = skyDistanceTexture.getPixels();
        if (pixels == null) return;
        long start = System.nanoTime();

        int tiles = 0;
        for (int packed : SCAN_ORDER) {
            int slot = slot(lastCamSecX + (packed & 0xFF) - CHUNKS_RADIUS, lastCamSecZ + (packed >> 8) - CHUNKS_RADIUS);
            if (!skyDirtySlots[slot]) continue;

            skyDirtySlots[slot] = false;
            skyDirtyCount--;
            writeSkyDistances(pixels, slot);
            skyTiles[tiles++] = slot;
            if (skyDirtyCount == 0 || System.nanoTime() - start > SKY_BUDGET_NS) break;
        }

        skyDistanceTexture.bind();
        if (tiles > MAX_PARTIAL_UPLOADS) {
            skyDistanceTexture.upload();
        } else {
            for (int i = 0; i < tiles; i++) {
                int x = (skyTiles[i] % CHUNKS_SPAN) << 4;
                int y = (skyTiles[i] / CHUNKS_SPAN) << 4;
                pixels.upload(0, x, y, x, y, 16, 16, false, false);
            }
        }
    }

    private void writeSkyDistances(NativeImage pixels, int slot) {
        int pixelBaseX = (slot % CHUNKS_SPAN) << 4;
        int pixelBaseY = (slot / CHUNKS_SPAN) << 4;
        long pos = slotPos[slot];
        if (pos == ChunkPos.INVALID_CHUNK_POS) {
            pixels.fillRect(pixelBaseX, pixelBaseY, 16, 16, 0);
            return;
        }

        int blockBaseX = ChunkPos.getX(pos) << 4;
        int blockBaseZ = ChunkPos.getZ(pos) << 4;
        boolean inside = blockBaseX - MAX_SKY_DISTANCE >= originX && blockBaseZ - MAX_SKY_DISTANCE >= originZ
                && blockBaseX + 16 + MAX_SKY_DISTANCE <= originX + SIZE && blockBaseZ + 16 + MAX_SKY_DISTANCE <= originZ + SIZE;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = blockBaseX + lx;
                int z = blockBaseZ + lz;
                int surface = surfaceHeights[(z & (SIZE - 1)) * SIZE + (x & (SIZE - 1))];
                if (surface == NO_SURFACE) {
                    pixels.setPixelRGBA(pixelBaseX + lx, pixelBaseY + lz, 0);
                    continue;
                }

                int best = Integer.MAX_VALUE;
                int i = 0;
                for (int step = 0; step < SKY_STEPS.length; step++) {
                    for (int end = SKY_STEP_END[step]; i < end; i++) {
                        int nx = x + DIAMOND_X[i];
                        int nz = z + DIAMOND_Z[i];
                        if (!inside && (nx < originX || nz < originZ || nx >= originX + SIZE || nz >= originZ + SIZE))
                            continue;
                        int height = surfaceHeights[(nz & (SIZE - 1)) * SIZE + (nx & (SIZE - 1))];
                        if (height != NO_SURFACE) best = Math.min(best, height + DIAMOND_DISTANCE[i]);
                    }
                    skyStepMins[step] = best;
                }

                int rgba = 0;
                for (int k = 0; k < SKY_STEPS.length; k++) {
                    int depth = Mth.clamp(surface - skyStepMins[k] + SKY_STEPS[k], 0, 255);
                    rgba |= depth << (k * 8);
                }
                pixels.setPixelRGBA(pixelBaseX + lx, pixelBaseY + lz, rgba);
            }
        }
    }

    private boolean writeChunk(NativeImage pixels, LevelChunk chunk, int secX, int secZ, LayerLightEventListener blockListener) {
        int pixelBaseX = (secX & (CHUNKS_SPAN - 1)) << 4;
        int pixelBaseY = (secZ & (CHUNKS_SPAN - 1)) << 4;
        if (chunk == null) {
            pixels.fillRect(pixelBaseX, pixelBaseY, 16, 16, 0);
            for (int lz = 0; lz < 16; lz++) {
                int row = (pixelBaseY + lz) * SIZE + pixelBaseX;
                for (int lx = 0; lx < 16; lx++) {
                    if (surfaceHeights[row + lx] != NO_SURFACE) {
                        surfaceHeights[row + lx] = NO_SURFACE;
                        heightsChanged = true;
                    }
                }
            }
            return false;
        }

        boolean chunkHasBlockLight = false;
        if (blockListener != null) {
            for (int sy = chunk.getMinSection(); sy < chunk.getMaxSection(); sy++) {
                DataLayer dl = blockListener.getDataLayerData(SectionPos.of(secX, sy, secZ));
                if (dl != null && !dl.isEmpty()) {
                    chunkHasBlockLight = true;
                    break;
                }
            }
        }

        boolean hasLight = false;
        int blockBaseX = secX << 4;
        int blockBaseZ = secZ << 4;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int surfaceY = shadowSurfaceY(chunk, lx, lz);
                int heightIndex = (pixelBaseY + lz) * SIZE + pixelBaseX + lx;
                if (surfaceHeights[heightIndex] != surfaceY) {
                    surfaceHeights[heightIndex] = surfaceY;
                    heightsChanged = true;
                }

                int light = 0;
                if (chunkHasBlockLight) {
                    mutablePos.set(blockBaseX + lx, surfaceY + 1, blockBaseZ + lz);
                    light = blockListener.getLightValue(mutablePos);
                    if (light < 15) {
                        mutablePos.setY(surfaceY);
                        light = Math.max(light, blockListener.getLightValue(mutablePos));
                    }
                    if (light > 0) hasLight = true;
                }

                int encodedY = Mth.clamp(surfaceY + 1024, 0, 65535);
                int r = light * 17;
                int g = encodedY & 255;
                int b = (encodedY >> 8) & 255;
                pixels.setPixelRGBA(pixelBaseX + lx, pixelBaseY + lz, (255 << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return hasLight;
    }

    private int shadowSurfaceY(ChunkAccess chunk, int lx, int lz) {
        int minY = chunk.getMinBuildHeight();
        int y = Math.min(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz), chunk.getMaxBuildHeight() - 1);

        boolean sawLeaves = false;
        int logTop = Integer.MIN_VALUE;

        while (y >= minY) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            if (section.hasOnlyAir()) {
                logTop = Integer.MIN_VALUE;
                y = (y & ~15) - 1;
                continue;
            }

            BlockState state = section.getBlockState(lx, y & 15, lz);
            Block block = state.getBlock();
            if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)) {
                sawLeaves = true;
                logTop = Integer.MIN_VALUE;
            } else if (sawLeaves && (state.is(BlockTags.LOGS) || state.is(Blocks.BEE_NEST))) {
                // branches under a canopy are skipped so the ground beneath them isn't considered underground
                if (logTop == Integer.MIN_VALUE) logTop = y;
            } else if (!(block instanceof SnowLayerBlock)
                    && (state.blocksMotion() || !state.getFluidState().isEmpty())) {
                break;
            } else {
                logTop = Integer.MIN_VALUE;
            }
            y--;
        }

        if (logTop != Integer.MIN_VALUE && y >= minY) return logTop;
        return Math.max(y, minY);
    }

    private void setSlotHasLight(int slot, boolean hasLight) {
        if (slotHasLight[slot] != hasLight) {
            slotHasLight[slot] = hasLight;
            litSlots += hasLight ? 1 : -1;
        }
    }

    @Override
    public void close() {
        texture.close();
        skyDistanceTexture.close();
    }
}
