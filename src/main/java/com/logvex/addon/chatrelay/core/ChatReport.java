package com.logvex.addon.chatrelay.core;

import java.util.List;

/**
 * A generated chat report.
 *
 * @param id             unique report id
 * @param serverAddress  server the messages came from
 * @param startTime      timestamp of the oldest buffered message
 * @param endTime        timestamp of the newest buffered message
 * @param generatedAt    when the report was generated
 * @param deliveryStatus last known delivery status
 * @param messages       the captured messages
 */
public record ChatReport(
    String id,
    String serverAddress,
    long startTime,
    long endTime,
    long generatedAt,
    String deliveryStatus,
    List<ChatMessage> messages
) {
    public int messageCount() {
        return messages == null ? 0 : messages.size();
    }

    public ChatReport withDeliveryStatus(String status) {
        return new ChatReport(id, serverAddress, startTime, endTime, generatedAt, status, messages);
    }

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_LOCAL = "local";
}
