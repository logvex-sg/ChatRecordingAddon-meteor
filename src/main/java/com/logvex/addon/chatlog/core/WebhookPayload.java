package com.logvex.addon.chatlog.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds webhook request bodies.
 *
 * <p>Two kinds of notification are supported: a keyword alert (one matching line) and a periodic
 * summary (a batch of recorded lines). Both strictly disable mentions so captured chat can never
 * ping a role or {@code @everyone}, and neither ever embeds the webhook URL or any account data.
 */
public final class WebhookPayload {
    /** Discord rejects content longer than 2000 characters. */
    public static final int CONTENT_LIMIT = 2000;

    private WebhookPayload() {
    }

    public static String alert(String username, KeywordRule rule, ChatEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Keyword alert** `").append(rule.pattern()).append("`\n");
        sb.append("Server: `").append(entry.server().isBlank() ? "unknown" : entry.server()).append("`\n");
        sb.append("Type: ").append(entry.type()).append('\n');
        sb.append('\n').append(ReportFormatting.format(entry));
        return envelope(username, clamp(sb.toString()));
    }

    public static String summary(String username, String server, List<ChatEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Chat summary**\n");
        sb.append("Server: `").append(server == null || server.isBlank() ? "unknown" : server).append("`\n");
        sb.append("Messages: ").append(entries.size()).append("\n\n");

        for (ChatEntry entry : entries) {
            String line = ReportFormatting.format(entry);
            if (sb.length() + line.length() + 1 > CONTENT_LIMIT) {
                sb.append("\n... (truncated)");
                break;
            }
            sb.append(line).append('\n');
        }
        return envelope(username, clamp(sb.toString()));
    }

    public static String test(String username) {
        return envelope(username, "**Log Addon** webhook test succeeded.");
    }

    private static String envelope(String username, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username == null || username.isBlank() ? "Log Addon" : username);
        payload.put("content", content);
        Map<String, Object> mentions = new LinkedHashMap<>();
        mentions.put("parse", List.of());
        payload.put("allowed_mentions", mentions);
        return Json.toJson(payload);
    }

    private static String clamp(String content) {
        if (content.length() <= CONTENT_LIMIT) return content;
        return content.substring(0, CONTENT_LIMIT);
    }
}
