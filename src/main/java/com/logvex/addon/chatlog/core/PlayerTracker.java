package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Accumulates per-player activity and can rebuild it from stored logs.
 *
 * <p>Thread-safe: the client thread records live entries while the GUI thread reads snapshots.
 */
public final class PlayerTracker {
    private final Map<String, PlayerActivity> players = new LinkedHashMap<>();

    public synchronized void record(ChatEntry entry) {
        if (entry == null || entry.sender().isBlank()) return;
        players.computeIfAbsent(entry.sender(), PlayerActivity::new).observe(entry);
    }

    public synchronized void clear() {
        players.clear();
    }

    public synchronized PlayerActivity get(String name) {
        return players.get(name);
    }

    public synchronized int size() {
        return players.size();
    }

    /** Players whose name contains {@code filter}, most recently seen first. */
    public synchronized List<PlayerActivity> search(String filter) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        List<PlayerActivity> out = new ArrayList<>();
        for (PlayerActivity activity : players.values()) {
            if (needle.isEmpty() || activity.name().toLowerCase(Locale.ROOT).contains(needle)) out.add(activity);
        }
        out.sort(Comparator.comparingLong(PlayerActivity::lastSeen).reversed());
        return out;
    }

}
