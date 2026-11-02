package com.logvex.addon.chatrelay.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded FIFO buffer of captured chat messages.
 *
 * <p>The module adds messages from the client thread and the sender drains them from a worker
 * thread, so every public method is synchronized.
 */
public final class ChatBuffer {
    private final int capacity;
    private final List<ChatMessage> messages = new ArrayList<>();

    public ChatBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(ChatMessage message) {
        if (message == null) return;
        messages.add(message);
        while (messages.size() > capacity) messages.remove(0);
    }

    /**
     * Returns everything currently buffered and removes it.
     *
     * <p>Only the sender calls this, and only after a report was delivered successfully, so a
     * failed webhook request can never lose messages.
     */
    public synchronized List<ChatMessage> drain() {
        if (messages.isEmpty()) return List.of();
        List<ChatMessage> copy = new ArrayList<>(messages);
        messages.clear();
        return copy;
    }

    /** Returns a copy of the buffered messages without removing them. */
    public synchronized List<ChatMessage> peek() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized boolean isEmpty() {
        return messages.isEmpty();
    }

    public synchronized void clear() {
        messages.clear();
    }

    public int capacity() {
        return capacity;
    }
}
