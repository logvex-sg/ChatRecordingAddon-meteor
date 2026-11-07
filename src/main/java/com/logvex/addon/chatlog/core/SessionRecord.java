package com.logvex.addon.chatlog.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A finished (or in-progress) recording session summary.
 *
 * <p>One record per stay on a server. It is produced when a session ends so the addon can show a
 * history of past sessions without re-reading the log files. Only counters that were actually
 * observed are stored.
 *
 * @param id            session id, matching {@link LogSession#id()}
 * @param server        server address, or {@code singleplayer}/{@code unknown}
 * @param startedAt     epoch milliseconds when the session began
 * @param endedAt       epoch milliseconds when the session ended, or 0 while it is still open
 * @param messages      messages recorded during the session
 * @param uniquePlayers distinct senders seen during the session
 * @param joins         join events observed
 * @param leaves        leave events observed
 * @param deaths        death messages observed
 * @param keywordAlerts keyword alerts raised during the session
 */
public record SessionRecord(
    String id,
    String server,
    long startedAt,
    long endedAt,
    long messages,
    long uniquePlayers,
    long joins,
    long leaves,
    long deaths,
    long keywordAlerts
) {
    public SessionRecord {
        if (id == null) id = "";
        if (server == null || server.isBlank()) server = "unknown";
    }

    /** Builds a record from the live session and its final statistics. */
    public static SessionRecord from(LogSession session, StatsSnapshot snapshot) {
        if (session == null) return null;
        StatsSnapshot stats = snapshot == null ? StatsSnapshot.empty() : snapshot;
        return new SessionRecord(
            session.id(),
            session.server(),
            session.startedAt(),
            session.endedAt(),
            stats.totalMessages(),
            stats.uniquePlayers(),
            stats.joinEvents(),
            stats.leaveEvents(),
            stats.deathEvents(),
            stats.keywordAlerts()
        );
    }

    /** True while the session has no end timestamp. */
    public boolean open() {
        return endedAt <= 0;
    }

    public long durationMs(long now) {
        long end = endedAt > 0 ? endedAt : now;
        return Math.max(0, end - startedAt);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("server", server);
        map.put("started", startedAt);
        map.put("ended", endedAt);
        map.put("messages", messages);
        map.put("players", uniquePlayers);
        map.put("joins", joins);
        map.put("leaves", leaves);
        map.put("deaths", deaths);
        map.put("alerts", keywordAlerts);
        return map;
    }

    public static SessionRecord fromMap(Map<?, ?> map) {
        if (map == null) return null;
        return new SessionRecord(
            str(map.get("id")),
            str(map.get("server")),
            num(map.get("started")),
            num(map.get("ended")),
            num(map.get("messages")),
            num(map.get("players")),
            num(map.get("joins")),
            num(map.get("leaves")),
            num(map.get("deaths")),
            num(map.get("alerts"))
        );
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
