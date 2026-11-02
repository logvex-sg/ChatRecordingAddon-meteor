package com.logvex.addon.chatrelay.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Archive index entry. Only this small object is kept in memory for every saved report; the message
 * bodies stay compressed on disk until a report is opened.
 */
public record ReportMetadata(
    String id,
    String serverAddress,
    long startTime,
    long endTime,
    long generatedAt,
    int messageCount,
    String deliveryStatus,
    long compressedSize,
    long uncompressedSize,
    String fileName
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("server", serverAddress);
        map.put("start", startTime);
        map.put("end", endTime);
        map.put("generated", generatedAt);
        map.put("count", messageCount);
        map.put("status", deliveryStatus);
        map.put("compressed", compressedSize);
        map.put("uncompressed", uncompressedSize);
        map.put("file", fileName);
        return map;
    }

    public static ReportMetadata fromMap(Map<?, ?> map) {
        return new ReportMetadata(
            asString(map.get("id")),
            asString(map.get("server")),
            asLong(map.get("start")),
            asLong(map.get("end")),
            asLong(map.get("generated")),
            (int) asLong(map.get("count")),
            asString(map.get("status")),
            asLong(map.get("compressed")),
            asLong(map.get("uncompressed")),
            asString(map.get("file"))
        );
    }

    public double compressionRatio() {
        return uncompressedSize <= 0 ? 0 : (double) compressedSize / uncompressedSize;
    }

    public ReportMetadata withStatus(String status) {
        return new ReportMetadata(id, serverAddress, startTime, endTime, generatedAt, messageCount,
            status, compressedSize, uncompressedSize, fileName);
    }

    public static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
