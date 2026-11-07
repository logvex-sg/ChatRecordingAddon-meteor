package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A bounded, chronological history of recording sessions.
 *
 * <p>Each session that ends is folded into a compact {@link SessionRecord}, so the history can be
 * reviewed without re-reading the log archive. The list is capped: the oldest record is dropped
 * once the limit is reached, so memory use stays fixed no matter how many sessions are played.
 *
 * <p>Thread-safe: a session is closed on the client thread while the GUI thread reads snapshots.
 */
public final class SessionHistory {
    /** How many sessions to keep by default. */
    public static final int DEFAULT_CAPACITY = 500;

    private volatile int capacity;
    private final Deque<SessionRecord> records = new ArrayDeque<>();
    private long totalSessions;

    public SessionHistory() {
        this(DEFAULT_CAPACITY);
    }

    public SessionHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public int capacity() {
        return capacity;
    }

    /** Changes the cap, dropping the oldest records if the new cap is smaller. */
    public synchronized void setCapacity(int capacity) {
        this.capacity = Math.max(1, capacity);
        while (records.size() > this.capacity) records.removeFirst();
    }

    /** Adds a finished session. A null or already-present record is ignored. */
    public synchronized void add(SessionRecord record) {
        if (record == null || record.id().isBlank()) return;
        for (SessionRecord existing : records) {
            if (existing.id().equals(record.id())) return;
        }
        records.addLast(record);
        while (records.size() > capacity) records.removeFirst();
        totalSessions++;
    }

    /** Replaces the history with {@code loaded}, preserving order and honouring the cap. */
    public synchronized void load(List<SessionRecord> loaded) {
        records.clear();
        if (loaded == null) return;
        for (SessionRecord record : loaded) {
            if (record == null || record.id().isBlank()) continue;
            records.addLast(record);
        }
        while (records.size() > capacity) records.removeFirst();
    }

    /** Every record, oldest first. */
    public synchronized List<SessionRecord> all() {
        return new ArrayList<>(records);
    }

    /** Records newest first, optionally filtered by server or date text. */
    public synchronized List<SessionRecord> search(String filter) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        List<SessionRecord> out = new ArrayList<>();
        for (SessionRecord record : records) {
            if (needle.isEmpty() || matches(record, needle)) out.add(record);
        }
        out.sort(Comparator.comparingLong(SessionRecord::startedAt).reversed());
        return out;
    }

    public synchronized int size() {
        return records.size();
    }

    /** Total sessions ever recorded, including those dropped by the cap. */
    public synchronized long totalRecorded() {
        return totalSessions;
    }

    /** Sum of messages across the stored sessions. */
    public synchronized long totalMessages() {
        long total = 0;
        for (SessionRecord record : records) total += record.messages();
        return total;
    }

    /** Total time across the stored sessions, using {@code now} for any still-open one. */
    public synchronized long totalDurationMs(long now) {
        long total = 0;
        for (SessionRecord record : records) total += record.durationMs(now);
        return total;
    }

    /** The most recent record, or null when the history is empty. */
    public synchronized SessionRecord latest() {
        return records.peekLast();
    }

    public synchronized void clear() {
        records.clear();
        totalSessions = 0;
    }

    /** The stored records as plain maps, oldest first, for persistence. */
    public synchronized List<Map<String, Object>> toMaps() {
        List<Map<String, Object>> out = new ArrayList<>(records.size());
        for (SessionRecord record : records) out.add(record.toMap());
        return out;
    }

    private static boolean matches(SessionRecord record, String needle) {
        return record.server().toLowerCase(Locale.ROOT).contains(needle)
            || record.id().toLowerCase(Locale.ROOT).contains(needle)
            || com.logvex.addon.common.Messages.dateTime(record.startedAt()).toLowerCase(Locale.ROOT).contains(needle);
    }
}
