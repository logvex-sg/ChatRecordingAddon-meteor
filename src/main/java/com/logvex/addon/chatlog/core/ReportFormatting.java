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

    /**
     * Renders a duration as {@code 1h 02m 03s}, {@code 2m 05s} or {@code 7s}.
     *
     * <p>Delegates to {@link com.logvex.addon.common.Messages#duration} so a duration reads the same
     * whether it came from the archive screen or a chat reply.
     */
    public static String duration(long millis) {
        return com.logvex.addon.common.Messages.duration(millis);
    }

    /** Byte size, matching {@link com.logvex.addon.common.Messages#bytes} (TB-aware). */
    public static String bytes(long bytes) {
        return com.logvex.addon.common.Messages.bytes(bytes);
    }
}
