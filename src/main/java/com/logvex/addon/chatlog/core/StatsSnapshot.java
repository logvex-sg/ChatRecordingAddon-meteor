package com.logvex.addon.chatlog.core;

import java.util.List;
import java.util.Map;

/**
 * Immutable view of the current session statistics. Produced by {@link StatsCollector#snapshot};
 * the HUD and GUI read only this so they can never observe a half-updated collector.
 */
public record StatsSnapshot(
    long startedAt,
    long endedAt,
    long durationMs,
    long totalMessages,
    double messagesPerMinute,
    int uniquePlayers,
    List<String> topPlayers,
    Map<MessageType, Long> byType,
    long joinEvents,
    long leaveEvents,
    long deathEvents,
    long keywordAlerts,
    Map<String, Long> keywordCounts
) {
    public static StatsSnapshot empty() {
        return new StatsSnapshot(0, 0, 0, 0, 0, 0, List.of(), Map.of(), 0, 0, 0, 0, Map.of());
    }

    public long count(MessageType type) {
        Long value = byType.get(type);
        return value == null ? 0 : value;
    }

    public boolean isEmpty() {
        return totalMessages == 0 && startedAt == 0;
    }
}
