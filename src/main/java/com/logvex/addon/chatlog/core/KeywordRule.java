package com.logvex.addon.chatlog.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One configurable keyword alert rule.
 *
 * <p>Rules are stored as a single string so they survive config round-trips without a bespoke
 * serializer: {@code pattern;key=value;key=value}. Unknown keys are ignored, so a rule written by a
 * newer build still loads.
 */
public record KeywordRule(
    String pattern,
    boolean caseSensitive,
    boolean wholeWord,
    boolean sound,
    boolean webhook,
    boolean enabled,
    int colorRgb
) {
    public static final int DEFAULT_COLOR = 0xFFD166;

    public KeywordRule {
        if (pattern == null) pattern = "";
        if (colorRgb == 0) colorRgb = DEFAULT_COLOR;
    }

    public static KeywordRule of(String pattern) {
        return new KeywordRule(pattern, false, false, true, false, true, DEFAULT_COLOR);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pattern", pattern);
        map.put("case", caseSensitive);
        map.put("whole", wholeWord);
        map.put("sound", sound);
        map.put("webhook", webhook);
        map.put("enabled", enabled);
        map.put("color", colorRgb);
        return map;
    }

    public static KeywordRule fromMap(Map<?, ?> map) {
        if (map == null) return null;
        String pattern = str(map.get("pattern"));
        if (pattern.isBlank()) return null;
        return new KeywordRule(
            pattern,
            bool(map.get("case"), false),
            bool(map.get("whole"), false),
            bool(map.get("sound"), true),
            bool(map.get("webhook"), false),
            bool(map.get("enabled"), true),
            (int) num(map.get("color"), DEFAULT_COLOR)
        );
    }

    public String encode() {
        return pattern + ";case=" + caseSensitive + ";whole=" + wholeWord
            + ";sound=" + sound + ";webhook=" + webhook + ";enabled=" + enabled
            + ";color=" + colorRgb;
    }

    public static KeywordRule decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split(";");
        String pattern = parts[0].strip();
        if (pattern.isEmpty()) return null;

        boolean caseSensitive = false;
        boolean wholeWord = false;
        boolean sound = true;
        boolean webhook = false;
        boolean enabled = true;
        int color = DEFAULT_COLOR;

        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) continue;
            String key = parts[i].substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = parts[i].substring(eq + 1).strip();
            switch (key) {
                case "case" -> caseSensitive = Boolean.parseBoolean(value);
                case "whole" -> wholeWord = Boolean.parseBoolean(value);
                case "sound" -> sound = Boolean.parseBoolean(value);
                case "webhook" -> webhook = Boolean.parseBoolean(value);
                case "enabled" -> enabled = Boolean.parseBoolean(value);
                case "color" -> {
                    try {
                        color = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        // Keep the default colour.
                    }
                }
                default -> {
                    // Unknown key from a newer build: ignore it.
                }
            }
        }

        return new KeywordRule(pattern, caseSensitive, wholeWord, sound, webhook, enabled, color);
    }

    /** The built-in example rules offered on first run. */
    public static java.util.List<KeywordRule> defaults() {
        return java.util.List.of(
            new KeywordRule("wts", false, true, true, false, true, 0x8AE9C1),
            new KeywordRule("wtb", false, true, true, false, true, 0x8AE9C1),
            new KeywordRule("base", false, false, false, false, true, 0xF2A65A),
            new KeywordRule("coords", false, false, false, false, true, 0xF2A65A),
            new KeywordRule("event", false, false, true, false, true, 0xC792EA),
            new KeywordRule("announcement", false, false, false, false, true, 0xC792EA)
        );
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static long num(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
