package com.logvex.addon.chatlog.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One rule in the chat filter.
 *
 * <p>A rule decides whether a matching message is shown or hidden. Rules are evaluated in order and
 * the first match wins, so a specific {@code ALLOW} can be placed above a broad {@code HIDE}. Every
 * condition is optional: a rule with only a sender matches all of that player's messages, and a rule
 * with no conditions at all matches everything (a catch-all, usually placed last).
 *
 * <p>Rules are stored as one string so they round-trip through the config without a bespoke
 * serialiser: {@code pattern;sender=;type=;action=;case=;whole=;enabled=}. Unknown keys are ignored,
 * so a rule written by a newer build still loads.
 */
public record ChatFilterRule(
    String pattern,
    String sender,
    MessageType type,
    Action action,
    boolean caseSensitive,
    boolean wholeWord,
    boolean enabled
) {
    public enum Action {
        /** Hide the message from chat. */
        HIDE,
        /** Force the message to stay visible, overriding an earlier broad rule. */
        ALLOW;

        public static Action parse(String value, Action fallback) {
            if (value == null) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "hide", "block", "mute" -> HIDE;
                case "allow", "show", "keep" -> ALLOW;
                default -> fallback;
            };
        }
    }

    public ChatFilterRule {
        if (pattern == null) pattern = "";
        if (sender == null) sender = "";
        if (action == null) action = Action.HIDE;
    }

    /** A rule that hides any message containing {@code pattern}. */
    public static ChatFilterRule hide(String pattern) {
        return new ChatFilterRule(pattern, "", null, Action.HIDE, false, false, true);
    }

    /** A rule that hides every message from {@code sender}. */
    public static ChatFilterRule hidePlayer(String sender) {
        return new ChatFilterRule("", sender, null, Action.HIDE, false, false, true);
    }

    /** True when the rule has no conditions and therefore matches everything. */
    public boolean isCatchAll() {
        return pattern.isBlank() && sender.isBlank() && type == null;
    }

    /** True when {@code entry} satisfies every condition this rule sets. */
    public boolean matches(ChatEntry entry) {
        if (entry == null) return false;
        if (type != null && entry.type() != type) return false;
        if (!sender.isBlank() && !entry.sender().equalsIgnoreCase(sender)) return false;
        if (!pattern.isBlank() && !contains(entry.content()) && !contains(entry.sender())) return false;
        return true;
    }

    /** Text containment honouring the case and whole-word options. */
    boolean contains(String text) {
        if (pattern.isEmpty() || text == null) return false;
        String haystack = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
        String needle = caseSensitive ? pattern : pattern.toLowerCase(Locale.ROOT);

        if (!wholeWord) return haystack.contains(needle);

        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return false;
            int end = at + needle.length();
            boolean leftOk = at == 0 || !isWordChar(haystack.charAt(at - 1));
            boolean rightOk = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pattern", pattern);
        map.put("sender", sender);
        map.put("type", type == null ? "" : type.id());
        map.put("action", action.name());
        map.put("case", caseSensitive);
        map.put("whole", wholeWord);
        map.put("enabled", enabled);
        return map;
    }

    public String encode() {
        return pattern + ";sender=" + sender + ";type=" + (type == null ? "" : type.id())
            + ";action=" + action.name() + ";case=" + caseSensitive + ";whole=" + wholeWord
            + ";enabled=" + enabled;
    }

    public static ChatFilterRule decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split(";");
        String pattern = parts[0].strip();

        String sender = "";
        MessageType type = null;
        Action action = Action.HIDE;
        boolean caseSensitive = false;
        boolean wholeWord = false;
        boolean enabled = true;

        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) continue;
            String key = parts[i].substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = parts[i].substring(eq + 1).strip();
            switch (key) {
                case "sender", "player" -> sender = value;
                case "type" -> type = value.isEmpty() ? null : MessageType.fromString(value);
                case "action" -> action = Action.parse(value, Action.HIDE);
                case "case" -> caseSensitive = Boolean.parseBoolean(value);
                case "whole" -> wholeWord = Boolean.parseBoolean(value);
                case "enabled" -> enabled = Boolean.parseBoolean(value);
                default -> {
                    // Unknown key from a newer build: ignore it.
                }
            }
        }

        if (pattern.isBlank() && sender.isBlank() && type == null) return null;
        return new ChatFilterRule(pattern, sender, type, action, caseSensitive, wholeWord, enabled);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
