package com.logvex.addon.chatlog.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A recording session. One session covers a continuous stay on a single server (or a singleplayer
 * world); joining a different server starts a new one so logs stay attributable.
 *
 * @param id        unique session id
 * @param server    server address, or {@code singleplayer}/{@code unknown}
 * @param startedAt epoch milliseconds when the session began
 * @param endedAt   epoch milliseconds when the session ended, or 0 while it is still open
 */
public record LogSession(String id, String server, long startedAt, long endedAt) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("server", server);
        map.put("started", startedAt);
        map.put("ended", endedAt);
        return map;
    }

    public static LogSession fromMap(Map<?, ?> map) {
        if (map == null) return null;
        return new LogSession(
            str(map.get("id")),
            str(map.get("server")),
            num(map.get("started")),
            num(map.get("ended"))
        );
    }

    public LogSession withEnded(long ended) {
        return new LogSession(id, server, startedAt, ended);
    }

    public long durationMs(long now) {
        long end = endedAt > 0 ? endedAt : now;
        return Math.max(0, end - startedAt);
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
