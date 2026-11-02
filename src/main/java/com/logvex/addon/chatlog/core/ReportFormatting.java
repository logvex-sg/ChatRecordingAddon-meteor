package com.logvex.addon.chatlog.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Shared text formatting for log entries and exports. */
public final class ReportFormatting {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private ReportFormatting() {
    }

    /** One export/HUD line: {@code [yyyy-MM-dd HH:mm:ss] Type Name: content}. */
    public static String format(ChatEntry entry) {
        return "[" + TIME.format(Instant.ofEpochMilli(entry.timestamp())) + "] "
            + label(entry) + ": " + entry.content();
    }

    /** Short form used by the live HUD. */
    public static String formatShort(ChatEntry entry) {
        return "[" + SHORT_TIME.format(Instant.ofEpochMilli(entry.timestamp())) + "] "
            + label(entry) + ": " + entry.content();
    }

    private static String label(ChatEntry entry) {
        if (!entry.sender().isBlank()) return entry.sender();
        return entry.type().toString();
    }

    public static String time(long timestamp) {
        return TIME.format(Instant.ofEpochMilli(timestamp));
    }

    public static String shortTime(long timestamp) {
        return SHORT_TIME.format(Instant.ofEpochMilli(timestamp));
    }

    /** Renders a duration as {@code 1h 02m 03s}. */
    public static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format(java.util.Locale.ROOT, "%dh %02dm %02ds", h, m, s);
        if (m > 0) return String.format(java.util.Locale.ROOT, "%dm %02ds", m, s);
        return s + "s";
    }

    public static String bytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(java.util.Locale.ROOT, "%.2f MB", mb);
    }
}
