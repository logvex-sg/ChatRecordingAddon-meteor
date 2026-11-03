package com.logvex.addon.common;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Shared, allocation-conscious formatting helpers used by every module.
 *
 * <p>Modules must not build their own prefixes, pluralisation or number formatting: doing so is
 * how an addon ends up with five different spellings of the same message. Everything a module
 * prints goes through here so the output stays consistent.
 */
public final class Messages {
    /** Prefix every addon message starts with. */
    public static final String PREFIX = "[Log] ";

    private static final DateTimeFormatter CLOCK =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private Messages() {
    }

    // ---------------------------------------------------------------- composition

    /** Prefixes {@code message} with {@link #PREFIX}. */
    public static String prefixed(String message) {
        return PREFIX + (message == null ? "" : message);
    }

    /** Formats and prefixes in one step, using {@link String#format}. */
    public static String format(String pattern, Object... args) {
        return prefixed(String.format(Locale.ROOT, pattern, args));
    }

    // ---------------------------------------------------------------- numbers

    /** Groups thousands with commas: {@code 12861} -> {@code 12,861}. */
    public static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** A fixed number of decimals with grouping, e.g. {@code 4.2}. */
    public static String decimal(double value, int decimals) {
        return String.format(Locale.ROOT, "%,." + Math.max(0, decimals) + "f", value);
    }

    /** A percentage with one decimal, e.g. {@code 42.7%}. */
    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    /** Stack breakdown: {@code 60 stacks + 21}. Returns the bare count when it fits one stack. */
    public static String stacks(int count, int stackSize) {
        if (stackSize <= 0 || count < stackSize) return number(count);
        int stacks = count / stackSize;
        int rest = count % stackSize;
        return rest == 0 ? number(stacks) + " stacks" : number(stacks) + " stacks + " + rest;
    }

    /** Byte size in binary units, e.g. {@code 4.2 KB}. */
    public static String bytes(long value) {
        if (value < 1024) return value + " B";
        double v = value;
        int unit = 0;
        while (v >= 1024.0 && unit < UNITS.length - 1) {
            v /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, UNITS[unit]);
    }

    /** Human readable duration, e.g. {@code 1m 05s} or {@code 3.4s}. */
    public static String duration(long millis) {
        if (millis < 0) millis = 0;
        long seconds = millis / 1000;
        if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes < 60) return minutes + "m " + (rest < 10 ? "0" : "") + rest + "s";
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m";
    }

    /** Pluralises a noun against a count: {@code 1 message} / {@code 2 messages}. */
    public static String plural(long count, String singular, String plural) {
        return number(count) + " " + (count == 1 ? singular : plural);
    }

    /** Pluralises a regular noun by appending an {@code s}. */
    public static String plural(long count, String singular) {
        return plural(count, singular, singular + "s");
    }

    // ---------------------------------------------------------------- players & ids

    /** Canonical player name: trimmed, or {@code unknown} when absent. */
    public static String player(String name) {
        if (name == null) return "unknown";
        String trimmed = name.trim();
        return trimmed.isEmpty() ? "unknown" : trimmed;
    }

    /** The path part of a namespaced id: {@code minecraft:oak_planks} -> {@code oak_planks}. */
    public static String idPath(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** A human readable name from a namespaced id: {@code oak_planks} -> {@code Oak Planks}. */
    public static String prettyId(String id) {
        String path = idPath(id);
        String[] words = path.split("_");
        StringBuilder sb = new StringBuilder(path.length());
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.length() == 0 ? path : sb.toString();
    }

    /** Truncates with an ellipsis so long names cannot break a fixed-width layout. */
    public static String truncate(String text, int max) {
        if (text == null) return "";
        if (max <= 0) return "";
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    // ---------------------------------------------------------------- timestamps

    /** Wall-clock time of day, {@code HH:mm:ss}. */
    public static String clock(long epochMillis) {
        return CLOCK.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Full local timestamp, {@code yyyy-MM-dd HH:mm:ss}. */
    public static String dateTime(long epochMillis) {
        return DATE_TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Relative age, e.g. {@code just now}, {@code 5m ago}, {@code 2h ago}, {@code 3d ago}. */
    public static String ago(long epochMillis, long nowMillis) {
        long delta = Math.max(0, nowMillis - epochMillis);
        if (delta < 10_000) return "just now";
        long seconds = delta / 1000;
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }
}
