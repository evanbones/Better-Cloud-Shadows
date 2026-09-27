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
    private static final int MAX_BUILDS_PER_FRAME = 64;
    private static final int MAX_PARTIAL_UPLOADS = 64;

    private static final int[] SCAN_ORDER = IntStream.range(0, SLOTS)
            .map(i -> (i % CHUNKS_SPAN) | (i / CHUNKS_SPAN) << 8)
            .boxed()
            .sorted(Comparator.comparingInt(BlockLightTexture::distanceSq))
            .mapToInt(Integer::intValue)
            .toArray();

    private static final boolean[] dirtySlots = new boolean[SLOTS];
    private static boolean anyDirty;

    private final DynamicTexture texture;
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    private final long[] slotPos = new long[SLOTS];
    private final LevelChunk[] slotChunk = new LevelChunk[SLOTS];
    private final boolean[] slotHasLight = new boolean[SLOTS];
    private final int[] changedSlots = new int[SLOTS];
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
        Arrays.fill(slotPos, ChunkPos.INVALID_CHUNK_POS);
    }

    public static void markDirty(int secX, int secZ) {
        dirtySlots[slot(secX, secZ)] = true;
        anyDirty = true;
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
            setSlotHasLight(slot, writeChunk(pixels, chunk, secX, secZ, blockListener));
            changedSlots[changed++] = slot;
        }

        if (changed == 0) return;

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

    private boolean writeChunk(NativeImage pixels, LevelChunk chunk, int secX, int secZ, LayerLightEventListener blockListener) {
        int pixelBaseX = (secX & (CHUNKS_SPAN - 1)) << 4;
        int pixelBaseY = (secZ & (CHUNKS_SPAN - 1)) << 4;
        if (chunk == null) {
            pixels.fillRect(pixelBaseX, pixelBaseY, 16, 16, 0);
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
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);
        boolean sawLeaves = false;

        while (y >= minY) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            if (section.hasOnlyAir()) {
                y = (y & ~15) - 1;
                continue;
            }

            BlockState state = section.getBlockState(lx, y & 15, lz);
            Block block = state.getBlock();
            if (block instanceof LeavesBlock || state.is(BlockTags.LEAVES)) {
                sawLeaves = true;
            } else if (!(block instanceof SnowLayerBlock)
                    && (state.blocksMotion() || !state.getFluidState().isEmpty())) {
                break;
            }
            y--;
        }

        return sawLeaves ? Math.max(y, minY) : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
    }

    private void setSlotHasLight(int slot, boolean hasLight) {
        if (slotHasLight[slot] != hasLight) {
            slotHasLight[slot] = hasLight;
            litSlots += hasLight ? 1 : -1;
        }
    }

    private static int slot(int secX, int secZ) {
        return (secZ & (CHUNKS_SPAN - 1)) * CHUNKS_SPAN + (secX & (CHUNKS_SPAN - 1));
    }

    private static int distanceSq(int packed) {
        int dx = (packed & 0xFF) - CHUNKS_RADIUS;
        int dz = (packed >> 8) - CHUNKS_RADIUS;
        return dx * dx + dz * dz;
    }

    @Override
    public void close() {
        texture.close();
    }
}
