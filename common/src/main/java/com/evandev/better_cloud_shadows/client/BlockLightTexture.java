package com.evandev.better_cloud_shadows.client;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
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
    private static final int SKY_WINDOW = 16 + MAX_SKY_DISTANCE * 2;
    private static final int NO_SURFACE = Integer.MAX_VALUE;
    private static final int UNKNOWN_SKY = 0xFFFFFFFF;
    private static final int FAR_SURFACE = 1 << 28;
    private static final byte COLUMN_LEAVES = 1;
    private static final byte COLUMN_LOG = 2;
    private static final byte COLUMN_SOLID = 4;
    private static final long SKY_BUDGET_NS = 1_000_000L;
    private static final long BUILD_BUDGET_NS = 2_000_000L;
    private static boolean anyDirty;
    private static int windowSecX;
    private static int windowSecZ;
    private final DynamicTexture texture;
    private final DynamicTexture skyDistanceTexture;
    private final int[] surfaceHeights = new int[SIZE * SIZE];
    private final boolean[] skyDirtySlots = new boolean[SLOTS];
    private final int[] skyTiles = new int[SLOTS];
    private final int[] skyHeights = new int[SKY_WINDOW * SKY_WINDOW];
    private final int[] skyRowMins = new int[(MAX_SKY_DISTANCE + 1) * SKY_WINDOW * 16];
    private final Reference2ByteOpenHashMap<BlockState> columnKinds = new Reference2ByteOpenHashMap<>();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final long[] slotPos = new long[SLOTS];
    private final LevelChunk[] slotChunk = new LevelChunk[SLOTS];
    private final boolean[] slotHasLight = new boolean[SLOTS];
    private final int[] changedSlots = new int[SLOTS];
    private final int[] resetSkySlots = new int[SLOTS];
    private final int[] rowMinX = new int[CHUNKS_SPAN];
    private final int[] rowMaxX = new int[CHUNKS_SPAN];
    private int skyDirtyCount;
    private boolean heightsChanged;
    private boolean pixelsChanged;
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

    public static void markDirty(int secX, int secZ) {
        int dx = secX - windowSecX;
        int dz = secZ - windowSecZ;
        if (dx < -CHUNKS_RADIUS || dx >= CHUNKS_RADIUS || dz < -CHUNKS_RADIUS || dz >= CHUNKS_RADIUS) return;
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
        NativeImage skyPixels = skyDistanceTexture.getPixels();
        if (pixels == null || skyPixels == null) return;

        lastCamSecX = camSecX;
        lastCamSecZ = camSecZ;
        lastChunkRadius = chunkRadius;
        lastLoadedChunks = loadedChunks;
        windowSecX = camSecX;
        windowSecZ = camSecZ;
        originX = (camSecX - CHUNKS_RADIUS) << 4;
        originZ = (camSecZ - CHUNKS_RADIUS) << 4;
        anyDirty = false;
        pending = false;
        columnKinds.clear();

        LayerLightEventListener blockListener = affectedByLights
                ? level.getLightEngine().getLayerListener(LightLayer.BLOCK)
                : null;

        long start = System.nanoTime();
        int builds = 0;
        int changed = 0;
        int resetSky = 0;
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

            if (chunk != null && (builds >= MAX_BUILDS_PER_FRAME || builds > 0 && System.nanoTime() - start > BUILD_BUDGET_NS)) {
                pending = true;
                if (sameChunk) continue;
                chunk = null;
            }

            if (chunk != null) builds++;
            boolean newChunk = !sameChunk || slotChunk[slot] != chunk;
            dirtySlots[slot] = false;
            slotPos[slot] = pos;
            slotChunk[slot] = chunk;
            heightsChanged = false;
            pixelsChanged = false;
            setSlotHasLight(slot, writeChunk(pixels, chunk, secX, secZ, blockListener));
            if (heightsChanged) {
                markSkyDirty(secX, secZ);
                if (newChunk && chunk != null) {
                    skyPixels.fillRect((slot % CHUNKS_SPAN) << 4, (slot / CHUNKS_SPAN) << 4, 16, 16, UNKNOWN_SKY);
                    resetSkySlots[resetSky++] = slot;
                }
            }
            if (pixelsChanged) changedSlots[changed++] = slot;
        }

        if (changed > 0) {
            RenderSystem.assertOnRenderThread();
            uploadSlots(texture, pixels, changedSlots, changed);
        }
        if (resetSky > 0) uploadSlots(skyDistanceTexture, skyPixels, resetSkySlots, resetSky);

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

        if (tiles > 0) uploadSlots(skyDistanceTexture, pixels, skyTiles, tiles);
    }

    // merges changed tiles into one strip per tile row, since each glTexSubImage2D call is far more expensive than the bytes it moves
    private void uploadSlots(DynamicTexture target, NativeImage pixels, int[] slots, int count) {
        if (count > MAX_PARTIAL_UPLOADS) {
            target.upload();
            return;
        }

        Arrays.fill(rowMinX, CHUNKS_SPAN);
        Arrays.fill(rowMaxX, -1);
        for (int i = 0; i < count; i++) {
            int x = slots[i] % CHUNKS_SPAN;
            int y = slots[i] / CHUNKS_SPAN;
            rowMinX[y] = Math.min(rowMinX[y], x);
            rowMaxX[y] = Math.max(rowMaxX[y], x);
        }

        target.bind();
        for (int y = 0; y < CHUNKS_SPAN; y++) {
            if (rowMaxX[y] < 0) continue;
            int px = rowMinX[y] << 4;
            int py = y << 4;
            pixels.upload(0, px, py, px, py, (rowMaxX[y] - rowMinX[y] + 1) << 4, 16, false, false);
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
        for (int iz = 0; iz < SKY_WINDOW; iz++) {
            int z = blockBaseZ - MAX_SKY_DISTANCE + iz;
            boolean rowInside = z >= originZ && z < originZ + SIZE;
            int row = (z & (SIZE - 1)) * SIZE;
            for (int ix = 0; ix < SKY_WINDOW; ix++) {
                int x = blockBaseX - MAX_SKY_DISTANCE + ix;
                int height = rowInside && x >= originX && x < originX + SIZE ? surfaceHeights[row + (x & (SIZE - 1))] : NO_SURFACE;
                skyHeights[iz * SKY_WINDOW + ix] = height == NO_SURFACE ? FAR_SURFACE : height;
            }
        }

        for (int iz = 0; iz < SKY_WINDOW; iz++) {
            int rowBase = iz * SKY_WINDOW + MAX_SKY_DISTANCE;
            for (int lx = 0; lx < 16; lx++) {
                int center = rowBase + lx;
                int best = skyHeights[center];
                skyRowMins[iz * 16 + lx] = best;
                for (int r = 1; r <= MAX_SKY_DISTANCE; r++) {
                    best = Math.min(best, Math.min(skyHeights[center - r], skyHeights[center + r]) + r);
                    skyRowMins[(r * SKY_WINDOW + iz) * 16 + lx] = best;
                }
            }
        }

        for (int lz = 0; lz < 16; lz++) {
            int row = ((blockBaseZ + lz) & (SIZE - 1)) * SIZE;
            for (int lx = 0; lx < 16; lx++) {
                int surface = surfaceHeights[row + ((blockBaseX + lx) & (SIZE - 1))];
                if (surface == NO_SURFACE) {
                    pixels.setPixelRGBA(pixelBaseX + lx, pixelBaseY + lz, 0);
                    continue;
                }

                int rgba = 0;
                for (int k = 0; k < SKY_STEPS.length; k++) {
                    int step = SKY_STEPS[k];
                    int best = FAR_SURFACE;
                    for (int dz = -step; dz <= step; dz++) {
                        int distance = Math.abs(dz);
                        best = Math.min(best, skyRowMins[((step - distance) * SKY_WINDOW + lz + MAX_SKY_DISTANCE + dz) * 16 + lx] + distance);
                    }
                    if (best >= FAR_SURFACE) best = Integer.MAX_VALUE;
                    int depth = Mth.clamp(surface - best + step, 0, 255);
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
            for (int lz = 0; lz < 16; lz++) {
                int row = (pixelBaseY + lz) * SIZE + pixelBaseX;
                for (int lx = 0; lx < 16; lx++) {
                    setPixel(pixels, pixelBaseX + lx, pixelBaseY + lz, 0);
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
                setPixel(pixels, pixelBaseX + lx, pixelBaseY + lz, (255 << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return hasLight;
    }

    private void setPixel(NativeImage pixels, int x, int y, int rgba) {
        if (pixels.getPixelRGBA(x, y) != rgba) {
            pixels.setPixelRGBA(x, y, rgba);
            pixelsChanged = true;
        }
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

            byte kind = columnKind(section.getBlockState(lx, y & 15, lz));
            if ((kind & COLUMN_LEAVES) != 0) {
                sawLeaves = true;
                logTop = Integer.MIN_VALUE;
            } else if (sawLeaves && (kind & COLUMN_LOG) != 0) {
                // branches under a canopy are skipped so the ground beneath them isn't considered underground
                if (logTop == Integer.MIN_VALUE) logTop = y;
            } else if ((kind & COLUMN_SOLID) != 0) {
                break;
            } else {
                logTop = Integer.MIN_VALUE;
            }
            y--;
        }

        if (logTop != Integer.MIN_VALUE && y >= minY) return logTop;
        return Math.max(y, minY);
    }

    private byte columnKind(BlockState state) {
        byte kind = columnKinds.getOrDefault(state, (byte) -1);
        if (kind != -1) return kind;
        Block block = state.getBlock();
        kind = 0;
        if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)) kind |= COLUMN_LEAVES;
        if (state.is(BlockTags.LOGS) || state.is(Blocks.BEE_NEST)) kind |= COLUMN_LOG;
        if (!(block instanceof SnowLayerBlock) && (state.blocksMotion() || !state.getFluidState().isEmpty())) kind |= COLUMN_SOLID;
        columnKinds.put(state, kind);
        return kind;
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
