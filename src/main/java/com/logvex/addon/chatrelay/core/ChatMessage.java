package com.logvex.addon.chatrelay.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One captured chat line.
 *
 * @param timestamp epoch milliseconds when the message was received
 * @param kind      direction/source of the message
 * @param sender    player name, or an empty string for system messages
 * @param content   the raw message text, already stripped of formatting
 */
public record ChatMessage(long timestamp, Kind kind, String sender, String content) {
    public enum Kind {
        CHAT,
        GAME,
        ACTION_BAR;

        @Override
        public String toString() {
            return switch (this) {
                case CHAT -> "chat";
                case GAME -> "game";
                case ACTION_BAR -> "actionbar";
            };
        }

        public static Kind fromString(String value) {
            if (value == null) return CHAT;
            return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "game" -> GAME;
                case "actionbar", "action_bar" -> ACTION_BAR;
                default -> CHAT;
            };
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ts", timestamp);
        map.put("kind", kind.toString());
        map.put("sender", sender == null ? "" : sender);
        map.put("content", content == null ? "" : content);
        return map;
    }

    public static ChatMessage fromMap(Map<?, ?> map) {
        long ts = asLong(map.get("ts"));
        Kind kind = Kind.fromString(asString(map.get("kind")));
        String sender = asString(map.get("sender"));
        String content = asString(map.get("content"));
        return new ChatMessage(ts, kind, sender == null ? "" : sender, content == null ? "" : content);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
