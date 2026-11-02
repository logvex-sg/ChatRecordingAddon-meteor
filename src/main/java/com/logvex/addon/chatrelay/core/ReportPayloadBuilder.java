package com.logvex.addon.chatrelay.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds the JSON bodies for a report: the Discord webhook payload and the local archive summary.
 */
public final class ReportPayloadBuilder {
    /** Discord rejects content longer than 2000 characters. */
    public static final int DISCORD_CONTENT_LIMIT = 2000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private ReportPayloadBuilder() {
    }

    /** Builds a webhook payload. The webhook URL is never included in the body. */
    public static String discordPayload(ChatReport report, String username) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("username", username == null || username.isBlank() ? "LiveChatRelay" : username);
        payload.put("content", buildContent(report));
        // Strict mention policy: never let captured chat ping anyone.
        java.util.Map<String, Object> mentions = new java.util.LinkedHashMap<>();
        mentions.put("parse", List.of());
        payload.put("allowed_mentions", mentions);
        return Json.toJson(payload);
    }

    /** Human-readable report body, capped at {@link #DISCORD_CONTENT_LIMIT} characters. */
    public static String buildContent(ChatReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Chat report** `").append(report.id()).append("`\n");
        sb.append("Server: `").append(report.serverAddress().isBlank() ? "unknown" : report.serverAddress()).append("`\n");
        sb.append("Messages: ").append(report.messageCount()).append('\n');
        if (report.messageCount() > 0) {
            sb.append("Window: ").append(TIME.format(Instant.ofEpochMilli(report.startTime())))
                .append(" -> ").append(TIME.format(Instant.ofEpochMilli(report.endTime()))).append('\n');
        }
        sb.append('\n');

        for (ChatMessage message : report.messages()) {
            String line = format(message);
            if (sb.length() + line.length() + 1 > DISCORD_CONTENT_LIMIT) {
                sb.append("\n... (truncated)");
                break;
            }
            sb.append(line).append('\n');
        }

        if (sb.length() > DISCORD_CONTENT_LIMIT) {
            sb.setLength(DISCORD_CONTENT_LIMIT);
        }
        return sb.toString();
    }

    /** Compact JSON summary stored alongside the compressed body. */
    public static String archiveSummary(ChatReport report) {
        java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("id", report.id());
        summary.put("server", report.serverAddress());
        summary.put("start", report.startTime());
        summary.put("end", report.endTime());
        summary.put("generated", report.generatedAt());
        summary.put("count", report.messageCount());
        summary.put("status", report.deliveryStatus());
        return Json.toJson(summary);
    }

    public static String format(ChatMessage message) {
        String time = TIME.format(Instant.ofEpochMilli(message.timestamp()));
        String sender = message.sender().isBlank() ? message.kind().toString() : message.sender();
        return "[" + time + "] " + sender + ": " + message.content();
    }

    /** Builds a plain-text rendering of a report, used by the browser's export/copy action. */
    public static String plainText(ChatReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Report ").append(report.id()).append('\n');
        sb.append("Server: ").append(report.serverAddress()).append('\n');
        sb.append("Generated: ").append(TIME.format(Instant.ofEpochMilli(report.generatedAt()))).append('\n');
        sb.append("Messages: ").append(report.messageCount()).append("\n\n");
        for (ChatMessage message : report.messages()) sb.append(format(message)).append('\n');
        return sb.toString();
    }
}
