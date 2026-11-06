package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A bounded, chronological record of noteworthy events the addon itself raised.
 *
 * <p>Chat output is deliberately terse, and a message that scrolled past is easy to miss. This log
 * keeps the last {@link #capacity()} alerts (keyword hits, webhook delivery problems and internal
 * errors) so the user can review them on demand. It never grows without bound and never blocks.
 */
public final class AlertLog {
    public enum Kind {
        KEYWORD,
        WEBHOOK,
        ERROR;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromString(String value) {
            if (value == null) return ERROR;
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "keyword" -> KEYWORD;
                case "webhook" -> WEBHOOK;
                default -> ERROR;
            };
        }

        @Override
        public String toString() {
            return switch (this) {
                case KEYWORD -> "Keyword";
                case WEBHOOK -> "Webhook";
                case ERROR -> "Error";
            };
        }
    }

    public record Alert(long timestamp, Kind kind, String title, String detail) {
    }

    private static final int DEFAULT_CAPACITY = 500;

    private final int capacity;
    private final Deque<Alert> alerts = new ArrayDeque<>();
    private long total;

    public AlertLog() {
        this(DEFAULT_CAPACITY);
    }

    public AlertLog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public int capacity() {
        return capacity;
    }

    public synchronized void record(Kind kind, String title, String detail) {
        if (kind == null) kind = Kind.ERROR;
        Alert alert = new Alert(System.currentTimeMillis(), kind,
            title == null ? "" : title, detail == null ? "" : detail);
        alerts.addLast(alert);
        while (alerts.size() > capacity) alerts.removeFirst();
        total++;
    }

    /** The most recent alerts, newest first, optionally filtered by a case-insensitive substring. */
    public synchronized List<Alert> recent(String filter, int limit) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        List<Alert> out = new ArrayList<>();
        var it = alerts.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            Alert alert = it.next();
            if (needle.isEmpty()
                || alert.title().toLowerCase(Locale.ROOT).contains(needle)
                || alert.detail().toLowerCase(Locale.ROOT).contains(needle)
                || alert.kind().id().contains(needle)) {
                out.add(alert);
            }
        }
        return out;
    }

    public synchronized int size() {
        return alerts.size();
    }

    /** Total alerts ever raised, including those dropped by the bound. */
    public long totalRecorded() {
        return total;
    }

    public synchronized int count(Kind kind) {
        int count = 0;
        for (Alert alert : alerts) if (alert.kind() == kind) count++;
        return count;
    }

    public synchronized void clear() {
        alerts.clear();
        total = 0;
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("size", alerts.size());
        map.put("total", total);
        map.put("keyword", count(Kind.KEYWORD));
        map.put("webhook", count(Kind.WEBHOOK));
        map.put("error", count(Kind.ERROR));
        return map;
    }
}
