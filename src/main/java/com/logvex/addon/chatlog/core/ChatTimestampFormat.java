package com.logvex.addon.chatlog.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats the clock prefix shown in front of a chat line.
 *
 * <p>The log records a millisecond timestamp per message, but the chat HUD itself shows none, so a
 * line that scrolls past cannot be placed in time. This turns a timestamp into the short prefix the
 * user picks. Formatting is done with {@link DateTimeFormatter} against the system zone, and every
 * pattern is a fixed, known-safe shape rather than user-supplied, so an odd clock or locale cannot
 * produce a prefix that breaks the chat line.
 */
public final class ChatTimestampFormat {
    /** The prefixes the setting offers, in the order they are shown. */
    public enum Style {
        /** {@code 14:05} */
        CLOCK("HH:mm"),
        /** {@code 14:05:09} */
        CLOCK_SECONDS("HH:mm:ss"),
        /** {@code 2:05 PM} */
        CLOCK_12H("h:mm a");

        private final String pattern;

        Style(String pattern) {
            this.pattern = pattern;
        }

        public String pattern() {
            return pattern;
        }
    }

    private ChatTimestampFormat() {
    }

    /**
     * The prefix for {@code timestampMs}, including its trailing space, e.g. {@code "14:05 "}.
     *
     * <p>The trailing space is part of the prefix so a caller can concatenate it directly without
     * deciding on spacing, and so the result is never a bare separator.
     */
    public static String prefix(long timestampMs, Style style) {
        return prefix(timestampMs, style, ZoneId.systemDefault());
    }

    /** As {@link #prefix(long, Style)}, in an explicit zone. Used by tests. */
    public static String prefix(long timestampMs, Style style, ZoneId zone) {
        if (style == null) style = Style.CLOCK;
        if (zone == null) zone = ZoneId.systemDefault();
        String text = DateTimeFormatter.ofPattern(style.pattern())
            .withZone(zone)
            .format(Instant.ofEpochMilli(timestampMs));
        return text + " ";
    }

    /**
     * Places {@code prefix} in front of {@code line}.
     *
     * <p>A blank line is returned unchanged: prefixing nothing would produce a line that is only a
     * clock, which reads as a broken message rather than a timestamped one.
     */
    public static String apply(String prefix, String line) {
        if (line == null || line.isBlank()) return line == null ? "" : line;
        if (prefix == null || prefix.isEmpty()) return line;
        return prefix + line;
    }
}
