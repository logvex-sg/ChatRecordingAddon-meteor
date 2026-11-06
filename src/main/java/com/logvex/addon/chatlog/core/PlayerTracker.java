package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates per-player activity and can rebuild it from stored logs.
 *
 * <p>Thread-safe: the client thread records live entries while the GUI thread reads snapshots.
 * Players on the exclude list are never tracked, so a user can keep a server's noisy bots or their
 * own alt out of the index without changing what gets recorded to disk.
 */
public final class PlayerTracker {
    private final Map<String, PlayerActivity> players = new LinkedHashMap<>();
    private final Set<String> excluded = new LinkedHashSet<>();

    public synchronized void record(ChatEntry entry) {
        if (entry == null || entry.sender().isBlank()) return;
        if (isExcluded(entry.sender())) return;
        players.computeIfAbsent(entry.sender(), PlayerActivity::new).observe(entry);
    }

    public synchronized void clear() {
        players.clear();
    }

    /** Replaces the exclude list. Names are matched case-insensitively. */
    public synchronized void setExcluded(Collection<String> names) {
        excluded.clear();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) excluded.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    public synchronized Set<String> excluded() {
        return Set.copyOf(excluded);
    }

    public synchronized boolean isExcluded(String name) {
        return name != null && excluded.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Drops one player from the index. Returns true when something was removed. */
    public synchronized boolean forget(String name) {
        if (name == null) return false;
        return players.remove(name) != null;
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
