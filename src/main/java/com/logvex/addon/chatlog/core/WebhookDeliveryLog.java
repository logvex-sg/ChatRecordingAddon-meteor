package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded history of webhook delivery attempts.
 *
 * <p>The {@link WebhookQueue} only keeps counters; this records what actually happened to each
 * attempt so the delivery monitor can show a timeline and explain a failure. URLs are never stored
 * here — a webhook URL is a secret, so a record holds only a host label and the outcome.
 */
public final class WebhookDeliveryLog {
    public static final int DEFAULT_CAPACITY = 100;

    /** One delivery attempt. */
    public record Record(long timestamp, String host, boolean success, int attempt, String message) {
        public Record {
            host = host == null ? "" : host;
            message = message == null ? "" : message;
        }
    }

    private final Deque<Record> records = new ArrayDeque<>();
    private int capacity;

    public WebhookDeliveryLog() {
        this(DEFAULT_CAPACITY);
    }

    public WebhookDeliveryLog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** The maximum number of records kept. */
    public synchronized int capacity() {
        return capacity;
    }

    /** Changes the cap, dropping the oldest records when it shrinks. */
    public synchronized void setCapacity(int newCapacity) {
        this.capacity = Math.max(1, newCapacity);
        while (records.size() > capacity) records.removeLast();
    }

    public synchronized void add(long timestamp, String host, boolean success, int attempt, String message) {
        records.addFirst(new Record(timestamp, host, success, attempt, message));
        while (records.size() > capacity) records.removeLast();
    }

    /** Every record, newest first. */
    public synchronized List<Record> records() {
        return new ArrayList<>(records);
    }

    /** The most recent {@code limit} records, newest first. */
    public synchronized List<Record> recent(int limit) {
        List<Record> out = new ArrayList<>();
        for (Record record : records) {
            if (out.size() >= limit) break;
            out.add(record);
        }
        return out;
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized void clear() {
        records.clear();
    }

    /**
     * A secret-free host label for a webhook URL, e.g. {@code discord.com}. The path is dropped
     * because the path segment of a Discord webhook URL is the credential.
     */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (host != null && !host.isBlank()) return host;
        } catch (IllegalArgumentException ignored) {
            // Fall through to the manual parse below.
        }
        String stripped = url.trim();
        int scheme = stripped.indexOf("://");
        if (scheme >= 0) stripped = stripped.substring(scheme + 3);
        int slash = stripped.indexOf('/');
        if (slash >= 0) stripped = stripped.substring(0, slash);
        return stripped;
    }
}
