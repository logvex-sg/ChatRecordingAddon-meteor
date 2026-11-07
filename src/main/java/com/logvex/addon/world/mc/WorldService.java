package com.logvex.addon.world.mc;

import com.logvex.addon.framework.LogClientContext;
import com.logvex.addon.framework.events.game.GameJoinedEvent;
import com.logvex.addon.framework.events.game.GameLeftEvent;
import com.logvex.addon.framework.orbit.EventBus;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.utils.world.BlockIterator;
import com.logvex.addon.world.core.LocationCategory;
import com.logvex.addon.world.core.WorldIntelligence;
import com.logvex.addon.world.core.WorldPoint;
import com.logvex.addon.world.core.WorldStore;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Minecraft-facing shell around {@link WorldIntelligence}.
 *
 * <p>It owns the live world, feeds observations into the Minecraft-free core and persists the result
 * off the game thread. It is subscribed once at bootstrap (like the chat recorder), so switching
 * worlds and saving happen even when no module is enabled; the actual scanning is driven by the
 * world-intelligence module, which decides how often and how far to look.
 *
 * <p>Only information the client is actually given is recorded: the player's position, the biome the
 * client resolved, the chunks that are loaded around the player, and blocks the client can see. The
 * client cannot enumerate server-side structure placements, so structure recording is limited to
 * block signatures the user configures plus manual marking.
 */
public final class WorldService {
    private static final WorldService INSTANCE = new WorldService();

    public static WorldService get() {
        return INSTANCE;
    }

    private final WorldIntelligence intelligence = new WorldIntelligence();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-world-io");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private volatile boolean loaded;
    private volatile boolean enabled = true;

    private WorldService() {
    }

    public WorldIntelligence intelligence() {
        return intelligence;
    }

    public void init(EventBus bus) {
        if (bus != null) bus.subscribe(this);
        loadAsync(null);
    }

    /** The directory the world database is written to, under the Log Client data folder. */
    private static Path dataDir() {
        return LogClientContext.FOLDER().toPath().resolve("logclient");
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!enabled) return;
        var mc = LogClientContext.mc;
        if (mc == null) return;
        String server = serverAddress();
        String world = worldName();
        intelligence.enterWorld(server, world, world, System.currentTimeMillis());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        saveAsync();
    }

    private static String serverAddress() {
        var mc = LogClientContext.mc;
        if (mc == null) return "unknown";
        if (mc.getCurrentServerEntry() != null) {
            String address = mc.getCurrentServerEntry().address;
            if (address != null && !address.isBlank()) return address;
        }
        return mc.isInSingleplayer() ? "singleplayer" : "unknown";
    }

    private static String worldName() {
        var mc = LogClientContext.mc;
        if (mc == null) return "default";
        try {
            if (mc.isInSingleplayer() && mc.getServer() != null) {
                String name = mc.getServer().getSaveProperties().getLevelName();
                if (name != null && !name.isBlank()) return name;
            }
        } catch (RuntimeException e) {
            // Fall through to the dimension-derived label.
        }
        if (mc.world != null) return mc.world.getRegistryKey().getValue().getPath();
        return "default";
    }

    // ------------------------------------------------------------------ sampling

    /**
     * Takes one observation of the current world: the chunk the player is in, the biome under them,
     * and a bounded scan for the watched resource and structure-signature blocks.
     *
     * @param radius          horizontal scan radius for the block search, in blocks
     * @param resources       block ids to watch for and record when seen
     * @param structureBlocks block ids that indicate a structure, mapped to the structure's name
     */
    public void sample(int radius, List<String> resources, Map<String, String> structureBlocks) {
        if (!enabled) return;
        var mc = LogClientContext.mc;
        if (mc == null || mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();

        String dimension = mc.world.getRegistryKey().getValue().toString();
        int px = mc.player.getBlockX();
        int py = mc.player.getBlockY();
        int pz = mc.player.getBlockZ();

        intelligence.recordChunk(dimension, px >> 4, pz >> 4, now);
        recordBiome(dimension, px, py, pz, now);

        if (radius > 0 && (!resources.isEmpty() || !structureBlocks.isEmpty())) {
            BlockIterator.register(radius, Math.min(32, radius), (pos, state) -> {
                String id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
                if (resources.contains(id)) {
                    intelligence.recordResource(dimension, pos.getX(), pos.getY(), pos.getZ(), id, now);
                }
                String structure = structureBlocks.get(id);
                if (structure != null) {
                    intelligence.recordStructure(dimension, pos.getX(), pos.getY(), pos.getZ(), structure, now);
                }
            });
        }
    }

    private void recordBiome(String dimension, int x, int y, int z, long now) {
        var mc = LogClientContext.mc;
        if (mc == null || mc.world == null) return;
        try {
            var biomeEntry = mc.world.getBiome(new BlockPos(x, y, z));
            String biome = biomeEntry.getKey().map(key -> key.getValue().toString()).orElse("unknown");
            intelligence.recordBiome(dimension, x, y, z, biome, now);
        } catch (RuntimeException e) {
            // A biome the client cannot resolve is simply not recorded.
        }
    }

    // ------------------------------------------------------------------ public queries

    /** Records a named place at the player's current position. Returns the stored point, or null. */
    public WorldPoint markHere(String name, LocationCategory category) {
        var mc = LogClientContext.mc;
        if (mc == null || mc.player == null || mc.world == null) return null;
        WorldPoint point = intelligence.recordLocation(
            mc.world.getRegistryKey().getValue().toString(),
            mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(),
            name, category, System.currentTimeMillis());
        saveAsync();
        return point;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    // ------------------------------------------------------------------ persistence

    public void loadAsync(Runnable onLoaded) {
        io.execute(() -> {
            WorldStore.load(dataDir(), intelligence);
            loaded = true;
            if (onLoaded != null) {
                if (LogClientContext.mc == null) onLoaded.run();
                else LogClientContext.mc.execute(onLoaded);
            }
        });
    }

    /** Queues an atomic write; repeat calls coalesce into one pending write. */
    public void saveAsync() {
        if (!loaded) return;
        if (!saveQueued.compareAndSet(false, true)) return;
        io.execute(() -> {
            saveQueued.set(false);
            WorldStore.save(dataDir(), intelligence);
        });
    }

    /** Flushes the database and stops the writer. Called once at shutdown. */
    public void shutdown() {
        if (loaded) io.execute(() -> WorldStore.save(dataDir(), intelligence));
        io.shutdown();
    }
}
