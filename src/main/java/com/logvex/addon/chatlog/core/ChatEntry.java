package com.logvex.addon.chatlog.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One recorded chat line.
 *
 * @param timestamp    epoch milliseconds when the message was received
 * @param type         classified message type
 * @param sender       player name, or an empty string when the message has no identifiable sender
 * @param content      the message text, stripped of formatting
 * @param server       server/session identifier the message was seen on
 * @param sessionId    identifier of the recording session
 */
public record ChatEntry(
    long timestamp,
    MessageType type,
    String sender,
    String content,
    String server,
    String sessionId
) {
    public ChatEntry {
        if (type == null) type = MessageType.OTHER;
        if (sender == null) sender = "";
        if (content == null) content = "";
        if (server == null) server = "";
        if (sessionId == null) sessionId = "";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ts", timestamp);
        map.put("type", type.id());
        map.put("sender", sender);
        map.put("content", content);
        map.put("server", server);
        map.put("session", sessionId);
        return map;
    }

    public static ChatEntry fromMap(Map<?, ?> map) {
        if (map == null) return null;
        Object content = map.get("content");
        if (content == null) return null;
        return new ChatEntry(
            asLong(map.get("ts")),
            MessageType.fromString(asString(map.get("type"))),
            asString(map.get("sender")),
            String.valueOf(content),
            asString(map.get("server")),
            asString(map.get("session"))
        );
    }

    /** True when the entry matches {@code needle} (already lower-cased) in sender or content. */
    public boolean matches(String needle) {
        if (needle == null || needle.isEmpty()) return true;
        return content.toLowerCase(java.util.Locale.ROOT).contains(needle)
            || sender.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
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
