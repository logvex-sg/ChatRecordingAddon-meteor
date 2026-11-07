package com.logvex.addon.world.core;

import java.util.Locale;

/**
 * Human-readable formatting for the world-intelligence views.
 *
 * <p>Minecraft-free and pure, so it can be unit tested. Every world view goes through here so
 * coordinates, distances and timestamps read the same everywhere.
 */
public final class WorldFormat {

    private WorldFormat() {
    }

    /** Formats a coordinate triple as {@code (x, y, z)}. */
    public static String coords(int x, int y, int z) {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    /** Formats a distance in blocks with one decimal place, e.g. {@code 12.4 blocks}. */
    public static String distance(double blocks) {
        if (Double.isNaN(blocks) || Double.isInfinite(blocks)) return "unknown distance";
        return String.format(Locale.ROOT, "%.1f %s", blocks, blocks == 1.0 ? "block" : "blocks");
    }

    /**
     * Formats an age as a short relative time, e.g. {@code just now}, {@code 3 minutes ago},
     * {@code 2 hours ago}, {@code 5 days ago}. A zero or negative age reads as "just now".
     */
    public static String relativeTime(long ageMillis) {
        if (ageMillis <= 0) return "just now";
        long seconds = ageMillis / 1000;
        if (seconds < 45) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " minute" + plural(minutes) + " ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hour" + plural(hours) + " ago";
        long days = hours / 24;
        if (days < 30) return days + " day" + plural(days) + " ago";
        long months = days / 30;
        return months + " month" + plural(months) + " ago";
    }

    /** A dimension id shortened for display: {@code minecraft:overworld} becomes {@code Overworld}. */
    public static String dimension(String id) {
        if (id == null || id.isBlank()) return "Unknown";
        String path = id;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        return titleCase(path);
    }

    /** A namespaced id shortened for display: {@code minecraft:diamond_ore} becomes {@code Diamond Ore}. */
    public static String itemName(String id) {
        if (id == null || id.isBlank()) return "Unknown";
        String path = id;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        return titleCase(path);
    }

    private static String titleCase(String value) {
        String[] words = value.split("[_/]");
        StringBuilder out = new StringBuilder(value.length());
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.length() == 0 ? value : out.toString();
    }

    private static String plural(long count) {
        return count == 1 ? "" : "s";
    }
}
