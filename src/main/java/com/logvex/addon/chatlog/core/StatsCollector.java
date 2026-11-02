package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Running statistics for the current session.
 *
 * <p>Only events that were actually observed are counted. The collector is fed one entry at a time
 * from the client thread and read from the render thread, so every accessor is synchronized.
 */
public final class StatsCollector {
    private final Map<MessageType, Long> byType = new EnumMap<>(MessageType.class);
    private final Map<String, Long> byPlayer = new LinkedHashMap<>();
    private final Map<String, Long> keywords = new LinkedHashMap<>();

    private long startedAt;
    private long endedAt;
    private long total;
    private long joinEvents;
    private long leaveEvents;
    private long deathEvents;
    private long keywordAlerts;

    public synchronized void start(long nowMs) {
        startedAt = nowMs;
        endedAt = 0;
        total = 0;
        joinEvents = 0;
        leaveEvents = 0;
        deathEvents = 0;
        keywordAlerts = 0;
        byType.clear();
        byPlayer.clear();
        keywords.clear();
    }

    public synchronized void stop(long nowMs) {
        endedAt = nowMs;
    }

    public synchronized void record(ChatEntry entry) {
        if (entry == null) return;
        total++;
        byType.merge(entry.type(), 1L, Long::sum);
        if (!entry.sender().isBlank()) byPlayer.merge(entry.sender(), 1L, Long::sum);
        switch (entry.type()) {
            case JOIN -> joinEvents++;
            case LEAVE -> leaveEvents++;
            case DEATH -> deathEvents++;
            default -> {
                // No dedicated counter.
            }
        }
    }

    public synchronized void recordKeyword(String pattern) {
        if (pattern == null || pattern.isBlank()) return;
        keywordAlerts++;
        keywords.merge(pattern, 1L, Long::sum);
    }

    public synchronized StatsSnapshot snapshot(long nowMs) {
        long duration = startedAt == 0 ? 0 : (endedAt > 0 ? endedAt : nowMs) - startedAt;
        double perMinute = duration <= 0 ? 0 : total / (duration / 60_000.0);

        List<Map.Entry<String, Long>> top = new ArrayList<>(byPlayer.entrySet());
        top.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
            .thenComparing(Map.Entry.comparingByKey()));
        List<String> topPlayers = new ArrayList<>();
        for (int i = 0; i < top.size() && i < 10; i++) {
            topPlayers.add(top.get(i).getKey() + " (" + top.get(i).getValue() + ")");
        }

        return new StatsSnapshot(
            startedAt,
            endedAt,
            Math.max(0, duration),
            total,
            perMinute,
            byPlayer.size(),
            topPlayers,
            new EnumMap<>(byType),
            joinEvents,
            leaveEvents,
            deathEvents,
            keywordAlerts,
            new LinkedHashMap<>(keywords)
        );
    }

    public synchronized long total() {
        return total;
    }

    public synchronized long startedAt() {
        return startedAt;
    }
}
