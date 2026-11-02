package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded buffer of messages waiting to be sent in the next webhook report.
 *
 * <p>The buffer exists to make delivery lossless: {@link #snapshot()} captures the batch that is
 * about to be sent, and only {@link #confirmDelivered(int)} removes it, after the request is known
 * to have succeeded. Messages that arrive while a request is in flight are appended at the back and
 * are never part of the confirmed batch.
 *
 * <p>All methods are safe to call from both the client thread (recording) and the webhook worker
 * thread (delivery confirmation).
 */
public final class ReportBuffer {
    private final int capacity;
    private final Deque<ChatEntry> entries = new ArrayDeque<>();

    public ReportBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Appends an entry, dropping the oldest when the buffer is full. */
    public synchronized void add(ChatEntry entry) {
        if (entry == null) return;
        entries.addLast(entry);
        while (entries.size() > capacity) entries.removeFirst();
    }

    /** An immutable copy of the current contents, in arrival order. */
    public synchronized List<ChatEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    /** Removes the first {@code count} entries, i.e. those that were confirmed delivered. */
    public synchronized void confirmDelivered(int count) {
        for (int i = 0; i < count && !entries.isEmpty(); i++) entries.removeFirst();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }
}
