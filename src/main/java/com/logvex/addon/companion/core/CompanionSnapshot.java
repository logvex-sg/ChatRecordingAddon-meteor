package com.logvex.addon.companion.core;

import java.util.List;

/**
 * An immutable picture of one client, as the companion API serves it.
 *
 * <p>Everything the app can display lives here, and nothing here touches Minecraft: the Minecraft
 * layer builds a snapshot from its own services and hands it over, so the serialisation and the API
 * surface can be tested without a game. Every list is copied and capped by the builder, so a
 * snapshot is a value object that never changes underneath a response.
 *
 * <p>The snapshot is deliberately read-only. Actions (start the bot, run a task, send a chat line)
 * go through {@link CompanionApi} instead, so a read can never mutate state by accident.
 */
public record CompanionSnapshot(
    NodeInfo node,
    List<ModuleView> modules,
    List<TaskView> tasks,
    List<PlayerView> players,
    List<ChatEntryView> chat,
    StatsView stats,
    StorageView storage,
    BotView bot,
    WorldView world,
    List<String> capabilities,
    List<MetricView> metrics,
    InventoryView inventory
) {
    public static final String API_VERSION = "15";

    /** Identity and connection state of this client. */
    public record NodeInfo(
        String id,
        String name,
        String addonVersion,
        String minecraftVersion,
        String playerName,
        String serverAddress,
        boolean inWorld,
        boolean singleplayer,
        String gameMode,
        long uptimeMs,
        long nowEpochMs
    ) {
    }

    /** One module and whether it is on. */
    public record ModuleView(
        String name,
        String title,
        String category,
        String description,
        boolean active
    ) {
    }

    /**
     * One setting of one module, in a shape a browser can build a control from without knowing the
     * setting's Java type.
     *
     * <p>{@code type} is one of {@code bool}, {@code int}, {@code double}, {@code enum},
     * {@code string}, {@code string-list}, {@code color}, {@code keybind} or {@code other}. The
     * extra fields are only present where they mean something: {@code min}/{@code max} for a number,
     * {@code options} for an enum, {@code value} always. A setting whose {@code visible} is false is
     * hidden by the module's own rule, which usually means a sibling setting is set to a value that
     * makes this one irrelevant.
     */
    public record SettingView(
        String name,
        String title,
        String description,
        String group,
        String type,
        Object value,
        Object defaultValue,
        Number min,
        Number max,
        List<String> options,
        boolean visible
    ) {
    }

    /** One queued or running bot task. */
    public record TaskView(
        String id,
        String type,
        String description,
        String state,
        int priority,
        long enqueuedAt,
        long startedAt,
        String failureReason
    ) {
    }

    /**
     * One player, as the client can see them.
     *
     * <p>Two sources feed this list and they are not the same thing. The tab list is who the server
     * says is online right now, which is complete even when chat logging is off. The activity
     * tracker is what this client has actually observed, which survives a player leaving and is
     * empty for a player who has said nothing. A row can come from either, so {@code messages},
     * {@code firstSeen} and {@code lastSeen} are 0 for someone who is only known from the tab list.
     *
     * @param ping     latency in milliseconds, or -1 when the server does not report one
     * @param gameMode "survival", "creative", "spectator", "adventure" or "" when unknown
     */
    public record PlayerView(
        String name,
        String uuid,
        long firstSeen,
        long lastSeen,
        int messages,
        long onlineMs,
        boolean online,
        boolean excluded,
        int ping,
        String gameMode
    ) {
    }

    /** One chat line, already classified by the addon's own classifier. */
    public record ChatEntryView(
        long timestamp,
        String type,
        String sender,
        String message
    ) {
    }

    /** Session counters. */
    public record StatsView(
        long messages,
        long players,
        long joins,
        long leaves,
        long deaths,
        long sessionStartEpochMs
    ) {
    }

    /** A summary of what is on disk, so the app can show storage use without touching the files. */
    public record StorageView(
        int segments,
        long bytes,
        int sessions,
        long oldestEpochMs,
        long newestEpochMs,
        long capBytes,
        boolean autoCleanup
    ) {
        /** Kept for callers that predate the retention cap being reported. */
        public StorageView(int segments, long bytes, int sessions, long oldestEpochMs, long newestEpochMs) {
            this(segments, bytes, sessions, oldestEpochMs, newestEpochMs, 0L, false);
        }

        public StorageView(int segments, long bytes, int sessions, long oldestEpochMs, long newestEpochMs,
                           long capBytes) {
            this(segments, bytes, sessions, oldestEpochMs, newestEpochMs, capBytes, false);
        }
    }

    /** The bot's live state. */
    public record BotView(
        String state,
        String activeTask,
        int queued,
        int completed,
        int failed,
        boolean paused,
        List<String> recentEvents
    ) {
    }

    /**
     * A compact picture of what the client can see right now, for the app's World tab.
     *
     * <p>This is the pruning of a schematic viewer that fits a web page: the player's own position
     * and look direction, the block under them, the dimension and light level, plus a short list of
     * the blocks and entities nearby. It is read-only and rebuilt on each poll, so nothing here can
     * change the game, and the lists are capped so a snapshot stays small enough to send every
     * second.
     *
     * @param blockBelow the id of the block directly under the player, or "" when unknown
     * @param blocks     the nearest non-air blocks, nearest first, each at most one per position
     * @param entities   the nearest living entities, nearest first
     */
    public record WorldView(
        boolean inWorld,
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        int blockX,
        int blockY,
        int blockZ,
        String blockBelow,
        int lightLevel,
        int health,
        long timeOfDay,
        boolean raining,
        boolean thundering,
        List<BlockView> blocks,
        List<EntityView> entities
    ) {
        /** An empty view, for a client that is not in a world. */
        public static WorldView empty() {
            return new WorldView(false, "", 0, 0, 0, 0, 0, 0, 0, 0, "", 0, 0, 0, false, false,
                List.of(), List.of());
        }
    }

    /**
     * One telemetry series as the app's graphs need it: its recent samples and honest statistics.
     *
     * <p>{@code latest}, {@code min}, {@code max} and {@code average} are {@link Double#NaN} when the
     * series is empty, which serialises as JSON {@code null} rather than a misleading zero. The app
     * shows "no data" for those, because a client that never measured a value should not claim one.
     */
    public record MetricView(
        String name,
        List<Long> timestamps,
        List<Double> values,
        double latest,
        double min,
        double max,
        double average
    ) {
    }

    /**
     * The player's own inventory, read-only.
     *
     * <p>Slots are the container indices Minecraft uses (0-8 hotbar, 9-35 main, 36-39 armour,
     * 40 offhand), so the app can draw the same layout the game does. {@code itemId} is empty for an
     * empty slot, which is a real state and not a missing value.
     */
    public record InventoryView(
        boolean available,
        int selectedSlot,
        List<ItemView> slots
    ) {
        /** An inventory that could not be read, because the client is not in a world. */
        public static InventoryView unavailable() {
            return new InventoryView(false, -1, List.of());
        }
    }

    /** One inventory slot: what is in it and how many. */
    public record ItemView(int slot, String itemId, String name, int count, int maxCount, int damage,
                           boolean enchanted) {
    }

    /** One nearby block, with the distance that puts it in scan order. */
    public record BlockView(int x, int y, int z, String id, double distance) {
    }

    /**
     * One nearby entity.
     *
     * @param kind     "player", "hostile", "passive" or "other"
     * @param distance distance from the player in blocks, rounded to one decimal
     */
    public record EntityView(String id, String name, String kind, double distance, int health) {
    }

    /** An empty snapshot, used before the first build and when the client is not in a world. */
    public static CompanionSnapshot empty(String nodeId, String name, String addonVersion) {
        return new CompanionSnapshot(
            new NodeInfo(nodeId, name, addonVersion, "unknown", "", "", false, false, "unknown",
                0L, System.currentTimeMillis()),
            List.of(), List.of(), List.of(), List.of(),
            new StatsView(0, 0, 0, 0, 0, 0),
            new StorageView(0, 0, 0, 0, 0),
            new BotView("Disabled", "", 0, 0, 0, false, List.of()),
            WorldView.empty(),
            List.of(),
            List.of(),
            InventoryView.unavailable());
    }
}
