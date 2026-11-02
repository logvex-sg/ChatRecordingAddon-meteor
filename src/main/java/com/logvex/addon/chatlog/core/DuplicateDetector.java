package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Suppresses messages that arrive twice in quick succession.
 *
 * <p>Some servers, plugins and chat mods deliver the same line more than once. A duplicate is
 * defined as an entry with the same sender and content seen within {@link #windowMs()}. The window
 * is deliberately short so a player legitimately repeating themselves later is still recorded.
 */
public final class DuplicateDetector {
    private static final int MAX_TRACKED = 512;

    private final long windowMs;
    private final Deque<Stamp> recent = new ArrayDeque<>();
    private final Set<String> live = new HashSet<>();

    public DuplicateDetector(long windowMs) {
        this.windowMs = Math.max(0, windowMs);
    }

    public long windowMs() {
        return windowMs;
    }

    /**
     * @return true when this entry should be recorded, false when it duplicates a recent one
     */
    public synchronized boolean accept(ChatEntry entry) {
        if (entry == null) return false;
        long now = entry.timestamp();
        prune(now);

        String key = fingerprint(entry);
        if (live.contains(key)) return false;

        recent.addLast(new Stamp(key, now));
        live.add(key);
        while (recent.size() > MAX_TRACKED) {
            Stamp removed = recent.removeFirst();
            if (!containsKey(removed.key)) live.remove(removed.key);
        }
        return true;
    }

    public synchronized void reset() {
        recent.clear();
        live.clear();
    }

    private void prune(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst().at > windowMs) {
            Stamp removed = recent.removeFirst();
            if (!containsKey(removed.key)) live.remove(removed.key);
        }
    }

    private boolean containsKey(String key) {
        for (Stamp stamp : recent) {
            if (stamp.key.equals(key)) return true;
        }
        return false;
    }

    static String fingerprint(ChatEntry entry) {
        return entry.sender() + '\u0000' + entry.content();
    }

    private record Stamp(String key, long at) {
    }
}
