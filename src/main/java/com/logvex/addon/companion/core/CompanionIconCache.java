package com.logvex.addon.companion.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bounded cache in front of an {@link CompanionIconSource}.
 *
 * <p>Icon PNGs never change while the game runs, so one fetch per item is enough, and the app asks
 * for the same handful of items on every inventory refresh. The cache is bounded because a large
 * modpack has thousands of items and a map of every icon ever requested would grow without limit.
 * The least recently used entry is dropped when it is full.
 *
 * <p>A miss on an item that has no icon is cached too, so an inventory full of the same unknown
 * item does not retry the lookup on every refresh. That negative entry is what makes the fallback
 * path cheap; without it the app would ask again every three seconds forever.
 */
public final class CompanionIconCache implements CompanionIconSource {
    /** Absent markers, so "no icon" can be cached distinctly from "not cached yet". */
    private static final byte[] MISSING = new byte[0];

    private final CompanionIconSource delegate;
    private final int capacity;
    private final Map<String, byte[]> entries;

    public CompanionIconCache(CompanionIconSource delegate, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.delegate = delegate;
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(capacity * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > CompanionIconCache.this.capacity;
            }
        };
    }

    @Override
    public synchronized byte[] icon(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        byte[] cached = entries.get(itemId);
        if (cached != null) return cached == MISSING ? null : cached;

        byte[] fetched = delegate.icon(itemId);
        entries.put(itemId, fetched == null ? MISSING : fetched);
        return fetched;
    }

    /** How many items are remembered, including the ones known to have no icon. */
    public synchronized int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }
}
