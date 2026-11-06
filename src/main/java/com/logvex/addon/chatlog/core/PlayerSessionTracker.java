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
 * Per-player session durations, derived only from observed join and leave events.
 *
 * <p>Only join/leave entries are observed, so the cost of feeding every recorded line is a cheap
 * type check. The map is bounded: once {@link #MAX_PLAYERS} players are tracked, the least recently
 * seen is dropped, so a long-running client on a busy server cannot grow without limit.
 */
public final class PlayerSessionTracker {
    public static final int MAX_PLAYERS = 2000;

    private final Map<String, PlayerSession> players = new LinkedHashMap<>();
    private final Set<String> excluded = new LinkedHashSet<>();

    public synchronized void record(ChatEntry entry) {
        if (entry == null) return;
        MessageType type = entry.type();
        if (type != MessageType.JOIN && type != MessageType.LEAVE) return;
        if (entry.sender().isBlank() || isExcluded(entry.sender())) return;

        PlayerSession session = players.get(entry.sender());
        if (session == null) {
            session = new PlayerSession(entry.sender());
            players.put(entry.sender(), session);
            evictIfNeeded();
        }
        session.observe(entry);
    }

    public synchronized void clear() {
        players.clear();
    }

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

    public synchronized boolean forget(String name) {
        return name != null && players.remove(name) != null;
    }

    public synchronized PlayerSession get(String name) {
        return players.get(name);
    }

    public synchronized int size() {
        return players.size();
    }

    public synchronized int onlineCount() {
        int count = 0;
        for (PlayerSession session : players.values()) if (session.online()) count++;
        return count;
    }

    /** Players whose name contains {@code filter}, most recently seen first. */
    public synchronized List<PlayerSession> search(String filter) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        List<PlayerSession> out = new ArrayList<>();
        for (PlayerSession session : players.values()) {
            if (needle.isEmpty() || session.name().toLowerCase(Locale.ROOT).contains(needle)) out.add(session);
        }
        out.sort(Comparator.comparingLong(PlayerSession::lastSeen).reversed());
        return out;
    }

    /** Players with the longest measured online time first. */
    public synchronized List<PlayerSession> byOnlineTime() {
        List<PlayerSession> out = new ArrayList<>(players.values());
        out.sort(Comparator.comparingLong(PlayerSession::totalOnlineMs).reversed());
        return out;
    }

    private void evictIfNeeded() {
        while (players.size() > MAX_PLAYERS) {
            String oldest = null;
            long oldestSeen = Long.MAX_VALUE;
            for (Map.Entry<String, PlayerSession> entry : players.entrySet()) {
                if (entry.getValue().lastSeen() < oldestSeen) {
                    oldestSeen = entry.getValue().lastSeen();
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) return;
            players.remove(oldest);
        }
    }
}
