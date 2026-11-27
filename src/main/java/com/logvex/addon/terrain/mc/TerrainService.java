package com.logvex.addon.terrain.mc;

import com.logvex.addon.framework.LogClientContext;
import com.logvex.addon.framework.SafeEventHandler;
import com.logvex.addon.terrain.core.ChunkTerrain;
import com.logvex.addon.terrain.core.TerrainPersistence;
import com.logvex.addon.terrain.core.TerrainWorld;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.IEventBus;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures the surface of chunks the client has actually loaded, for the browser map.
 *
 * <p>Only chunks Minecraft has already received and loaded are read: {@link ClientWorld#isChunkLoaded}
 * is the gate, so a chunk the server has not sent is never asked for and never invented. A column
 * whose heightmap has no value is stored as unknown, which is how the map knows to draw a hole.
 *
 * <p>Capture runs on the client thread because {@code ClientWorld} is not thread-safe, but it is
 * strictly bounded: a fixed number of chunks per pass, nearest to the player first, and only chunks
 * whose surface is not already recorded this session or has gone stale. The scan never walks a whole
 * radius, so a large render distance cannot turn one tick into a stall.
 *
 * <p>Nothing here touches rendering, networking or the database: the store is in memory and is
 * written to disk by {@link TerrainPersistence} on its own thread.
 */
public final class TerrainService {
    private static final TerrainService INSTANCE = new TerrainService();

    public static TerrainService get() {
        return INSTANCE;
    }

    /** How long an observed chunk is trusted before it is read again, in milliseconds. */
    private static final long RESTALE_MS = 30_000L;

    private final TerrainWorld world = new TerrainWorld();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-terrain-io");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private final AtomicInteger capturedThisSession = new AtomicInteger();
    private volatile boolean loaded;
    private volatile boolean enabled = true;
    /** The dimension the client was in when the last capture ran, so a switch resets the cursor. */
    private volatile String dimension = "";

    private TerrainService() {
    }

    public TerrainWorld world() {
        return world;
    }

    public void init(IEventBus bus) {
        if (bus != null) bus.subscribe(this);
        loadAsync(null);
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        SafeEventHandler.guard("TerrainService.onGameJoined", () -> capturedThisSession.set(0)).run();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        // Runs inside MinecraftClient.disconnect(); a throw here would take the game down.
        SafeEventHandler.run("TerrainService.onGameLeft", this::saveAsync);
    }

    // ------------------------------------------------------------------ capture

    /**
     * Captures up to {@code budget} chunks around the player, nearest first.
     *
     * @param radius   how far from the player's chunk to look, in chunks
     * @param budget   the most chunks to read this pass; keeps one tick cheap
     * @return how many chunks were actually captured
     */
    public int capture(int radius, int budget) {
        if (!enabled || budget <= 0) return 0;
        MinecraftClient mc = LogClientContext.mc;
        if (mc == null || mc.world == null || mc.player == null) return 0;
        ClientWorld clientWorld = mc.world;
        ClientPlayerEntity player = mc.player;

        String currentDimension = clientWorld.getRegistryKey().getValue().toString();
        if (!currentDimension.equals(dimension)) {
            dimension = currentDimension;
            capturedThisSession.set(0);
        }

        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        long now = System.currentTimeMillis();

        int captured = 0;
        // Walk a square ring by ring so the nearest chunks are read first and a small budget still
        // fills in the area the player is standing in.
        for (int ring = 0; ring <= radius && captured < budget; ring++) {
            for (int dx = -ring; dx <= ring && captured < budget; dx++) {
                for (int dz = -ring; dz <= ring && captured < budget; dz++) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    int chunkX = playerChunkX + dx;
                    int chunkZ = playerChunkZ + dz;
                    if (!clientWorld.isChunkLoaded(chunkX, chunkZ)) continue;

                    ChunkTerrain existing = world.store().get(currentDimension, chunkX, chunkZ);
                    if (existing != null && now - existing.observedAt() < RESTALE_MS) continue;

                    ChunkTerrain chunk = readChunk(clientWorld, currentDimension, chunkX, chunkZ, now);
                    if (chunk != null) {
                        world.put(chunk);
                        captured++;
                        capturedThisSession.incrementAndGet();
                    }
                }
            }
        }
        if (captured > 0) saveAsync();
        return captured;
    }

    /** Reads one chunk's surface columns; returns null when the chunk is not actually present. */
    private ChunkTerrain readChunk(ClientWorld world, String dimension, int chunkX, int chunkZ, long now) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int[] heights = new int[ChunkTerrain.SIZE * ChunkTerrain.SIZE];
        String[] blocks = new String[heights.length];
        String[] biomes = new String[heights.length];

        for (int localZ = 0; localZ < ChunkTerrain.SIZE; localZ++) {
            for (int localX = 0; localX < ChunkTerrain.SIZE; localX++) {
                int i = localZ * ChunkTerrain.SIZE + localX;
                int blockX = baseX + localX;
                int blockZ = baseZ + localZ;

                int topY;
                try {
                    topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, blockX, blockZ);
                } catch (RuntimeException e) {
                    heights[i] = ChunkTerrain.UNKNOWN;
                    continue;
                }
                // getTopY returns the first air above the surface, so the surface block is one below.
                int surfaceY = topY - 1;
                if (surfaceY < world.getBottomY()) {
                    heights[i] = ChunkTerrain.UNKNOWN;
                    continue;
                }
                BlockPos pos = new BlockPos(blockX, surfaceY, blockZ);
                BlockState state;
                try {
                    state = world.getBlockState(pos);
                } catch (RuntimeException e) {
                    heights[i] = ChunkTerrain.UNKNOWN;
                    continue;
                }
                if (state.isAir()) {
                    heights[i] = ChunkTerrain.UNKNOWN;
                    continue;
                }
                heights[i] = surfaceY;
                blocks[i] = Registries.BLOCK.getId(state.getBlock()).toString();
                biomes[i] = biomeAt(world, pos);
            }
        }
        return ChunkTerrain.of(dimension, chunkX, chunkZ, now, heights, blocks, biomes);
    }

    private static String biomeAt(ClientWorld world, BlockPos pos) {
        try {
            return world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ queries

    /** How many chunks this session has captured, for the statistics engine. */
    public int capturedThisSession() {
        return capturedThisSession.get();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    /** The dimensions that have any observed terrain. */
    public List<String> dimensions() {
        return new ArrayList<>(world.store().dimensions());
    }

    // ------------------------------------------------------------------ persistence

    public void loadAsync(Runnable onLoaded) {
        io.execute(() -> {
            TerrainPersistence.load(TerrainService.dataDir(), world);
            loaded = true;
            if (onLoaded != null) {
                if (LogClientContext.mc == null) onLoaded.run();
                else LogClientContext.mc.execute(onLoaded);
            }
        });
    }

    /** Queues a write; repeat calls coalesce into one pending write. */
    public void saveAsync() {
        if (!loaded) return;
        if (!saveQueued.compareAndSet(false, true)) return;
        io.execute(() -> {
            saveQueued.set(false);
            TerrainPersistence.save(TerrainService.dataDir(), world);
        });
    }

    /** Flushes and stops the writer. Called once at shutdown. */
    public void shutdown() {
        if (loaded) io.execute(() -> TerrainPersistence.save(TerrainService.dataDir(), world));
        com.logvex.addon.common.ShutdownFlush.await(io);
    }

    /** Where the terrain database lives, beside the other Log Client data. */
    public static java.nio.file.Path dataDir() {
        return LogClientContext.FOLDER().toPath().resolve("logclient").resolve("terrain");
    }
}
