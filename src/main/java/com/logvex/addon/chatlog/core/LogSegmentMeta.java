package com.logvex.addon.chatlog.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Index entry for one stored log segment. Only these small records are held in memory; the entry
 * bodies stay gzip-compressed on disk until they are actually read.
 *
 * @param fileName       segment file name inside the log directory
 * @param sessionId      owning session id
 * @param server         server the entries came from
 * @param firstTimestamp timestamp of the oldest entry
 * @param lastTimestamp  timestamp of the newest entry
 * @param count          number of entries
 * @param compressedSize size of the stored file in bytes
 * @param uncompressedSize estimated size of the decoded entries in bytes
 */
public record LogSegmentMeta(
    String fileName,
    String sessionId,
    String server,
    long firstTimestamp,
    long lastTimestamp,
    int count,
    long compressedSize,
    long uncompressedSize
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("file", fileName);
        map.put("session", sessionId);
        map.put("server", server);
        map.put("first", firstTimestamp);
        map.put("last", lastTimestamp);
        map.put("count", count);
        map.put("compressed", compressedSize);
        map.put("uncompressed", uncompressedSize);
        return map;
    }

    public static LogSegmentMeta fromMap(Map<?, ?> map) {
        if (map == null) return null;
        return new LogSegmentMeta(
            str(map.get("file")),
            str(map.get("session")),
            str(map.get("server")),
            num(map.get("first")),
            num(map.get("last")),
            (int) num(map.get("count")),
            num(map.get("compressed")),
            num(map.get("uncompressed"))
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
