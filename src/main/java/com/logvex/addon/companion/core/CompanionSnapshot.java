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
    List<String> capabilities
) {
    public static final String API_VERSION = "13";

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

    /** An empty snapshot, used before the first build and when the client is not in a world. */
    public static CompanionSnapshot empty(String nodeId, String name, String addonVersion) {
        return new CompanionSnapshot(
            new NodeInfo(nodeId, name, addonVersion, "unknown", "", "", false, false, "unknown",
                0L, System.currentTimeMillis()),
            List.of(), List.of(), List.of(), List.of(),
            new StatsView(0, 0, 0, 0, 0, 0),
            new StorageView(0, 0, 0, 0, 0),
            new BotView("Disabled", "", 0, 0, 0, false, List.of()),
            List.of());
    }
}
