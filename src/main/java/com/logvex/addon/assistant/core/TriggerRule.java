package com.logvex.addon.assistant.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One configurable keyword trigger for the assistant.
 *
 * <p>A rule watches incoming chat for a keyword or phrase and, when it matches, produces a response
 * from a template. Every part of the behaviour is a field here so a user can tune it without code:
 * how the keyword is matched, who may trigger it, how the reply is delivered, how often it may fire
 * and whether the match is written to memory.
 *
 * <p>Rules are stored as a single string so they round-trip through the config without a bespoke
 * serialiser: {@code pattern;key=value;key=value}. Unknown keys are ignored, so a rule written by a
 * newer build still loads.
 */
public record TriggerRule(
    String pattern,
    boolean caseSensitive,
    boolean wholeWord,
    String sender,
    String reply,
    ReplyMode mode,
    long cooldownMs,
    int sessionLimit,
    boolean sound,
    boolean remember,
    boolean enabled
) {
    /** How a matching rule delivers its reply. */
    public enum ReplyMode {
        /** Say the reply in public chat. */
        CHAT,
        /** Only raise a local notification, never speak. */
        NOTIFY,
        /** Record the match silently, speaking and notifying nothing. */
        SILENT;

        public static ReplyMode parse(String value, ReplyMode fallback) {
            if (value == null) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "chat", "say", "public" -> CHAT;
                case "notify", "alert", "local" -> NOTIFY;
                case "silent", "log", "none", "quiet" -> SILENT;
                default -> fallback;
            };
        }
    }

    public static final long DEFAULT_COOLDOWN_MS = 3_000L;

    public TriggerRule {
        if (pattern == null) pattern = "";
        if (sender == null) sender = "";
        if (reply == null) reply = "";
        if (mode == null) mode = ReplyMode.CHAT;
        if (cooldownMs < 0) cooldownMs = 0;
        if (sessionLimit < 0) sessionLimit = 0;
    }

    /** A rule that answers {@code pattern} with {@code reply} in chat. */
    public static TriggerRule of(String pattern, String reply) {
        return new TriggerRule(pattern, false, false, "", reply, ReplyMode.CHAT,
            DEFAULT_COOLDOWN_MS, 0, false, false, true);
    }

    /** True when this rule may fire for a message from {@code from}. */
    public boolean senderAllowed(String from) {
        if (sender.isBlank()) return true;
        if (from == null) return false;
        return sender.equalsIgnoreCase(from.trim());
    }

    /** The effective cooldown: never negative. */
    public long effectiveCooldownMs() {
        return Math.max(0, cooldownMs);
    }

    /** True when {@code count} prior replies are still below the session limit. */
    public boolean withinLimit(long count) {
        return sessionLimit <= 0 || count < sessionLimit;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pattern", pattern);
        map.put("case", caseSensitive);
        map.put("whole", wholeWord);
        map.put("sender", sender);
        map.put("reply", reply);
        map.put("mode", mode.name().toLowerCase(Locale.ROOT));
        map.put("cooldown", cooldownMs);
        map.put("limit", sessionLimit);
        map.put("sound", sound);
        map.put("remember", remember);
        map.put("enabled", enabled);
        return map;
    }

    public static TriggerRule fromMap(Map<?, ?> map) {
        if (map == null) return null;
        String pattern = str(map.get("pattern"));
        if (pattern.isBlank()) return null;
        return new TriggerRule(
            pattern,
            bool(map.get("case"), false),
            bool(map.get("whole"), false),
            str(map.get("sender")),
            str(map.get("reply")),
            ReplyMode.parse(str(map.get("mode")), ReplyMode.CHAT),
            num(map.get("cooldown"), DEFAULT_COOLDOWN_MS),
            (int) num(map.get("limit"), 0),
            bool(map.get("sound"), false),
            bool(map.get("remember"), false),
            bool(map.get("enabled"), true)
        );
    }

    public String encode() {
        return pattern + ";case=" + caseSensitive + ";whole=" + wholeWord
            + ";sender=" + sender + ";reply=" + reply + ";mode=" + mode.name().toLowerCase(Locale.ROOT)
            + ";cooldown=" + cooldownMs + ";limit=" + sessionLimit + ";sound=" + sound
            + ";remember=" + remember + ";enabled=" + enabled;
    }

    public static TriggerRule decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split(";");
        String pattern = parts[0].strip();
        if (pattern.isEmpty()) return null;

        boolean caseSensitive = false;
        boolean wholeWord = false;
        String sender = "";
        String reply = "";
        ReplyMode mode = ReplyMode.CHAT;
        long cooldown = DEFAULT_COOLDOWN_MS;
        int limit = 0;
        boolean sound = false;
        boolean remember = false;
        boolean enabled = true;

        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) continue;
            String key = parts[i].substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = parts[i].substring(eq + 1).strip();
            switch (key) {
                case "case" -> caseSensitive = Boolean.parseBoolean(value);
                case "whole" -> wholeWord = Boolean.parseBoolean(value);
                case "sender" -> sender = value;
                case "reply" -> reply = value;
                case "mode" -> mode = ReplyMode.parse(value, ReplyMode.CHAT);
                case "cooldown" -> cooldown = parseLong(value, DEFAULT_COOLDOWN_MS);
                case "limit" -> limit = (int) parseLong(value, 0);
                case "sound" -> sound = Boolean.parseBoolean(value);
                case "remember" -> remember = Boolean.parseBoolean(value);
                case "enabled" -> enabled = Boolean.parseBoolean(value);
                default -> {
                    // Unknown key from a newer build: ignore it.
                }
            }
        }

        return new TriggerRule(pattern, caseSensitive, wholeWord, sender, reply, mode,
            cooldown, limit, sound, remember, enabled);
    }

    /** A small set of ready-to-use rules offered on first run. */
    public static java.util.List<TriggerRule> defaults() {
        return java.util.List.of(
            new TriggerRule("hello bot", false, false, "", "Hello {player}!", ReplyMode.CHAT,
                DEFAULT_COOLDOWN_MS, 0, false, false, true),
            new TriggerRule("!ping", false, false, "", "pong", ReplyMode.CHAT,
                1_000L, 0, false, false, true)
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
        if (value instanceof Number n) return n.longValue();
        if (value == null) return fallback;
        return parseLong(String.valueOf(value), fallback);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
