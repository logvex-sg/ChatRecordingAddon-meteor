package com.logvex.addon.automation.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Watches a list of names and reports when one of them comes or goes.
 *
 * <p>Distinct from the chat log's watchlist, which only stores names: this turns a join or leave
 * line into a decision about whether to alert, and remembers enough to avoid repeating itself. A
 * server that announces a player twice, or a reconnect that produces a leave then a join, should not
 * produce two alerts.
 *
 * <p>Pure text processing, so the behaviour can be tested without a Minecraft runtime.
 */
public final class PresenceWatcher {

    /** What happened to a watched player. */
    public enum Change { JOINED, LEFT }

    /** A watched player's state change. */
    public record Alert(String player, Change change) {
    }

    /** Remembers whether each name was last seen online, so only real transitions alert. */
    private final Map<String, Boolean> online = new LinkedHashMap<>();

    /** How long an alert is suppressed after an identical one, to absorb duplicate announcements. */
    private final long dedupeMs;
    private final Map<String, Long> lastAlert = new LinkedHashMap<>();

    public PresenceWatcher(long dedupeMs) {
        this.dedupeMs = Math.max(0, dedupeMs);
    }

    /**
     * Records that {@code player} joined.
     *
     * @return an alert, or null when there is nothing new to say
     */
    public synchronized Alert joined(String player, long now) {
        return transition(player, true, Change.JOINED, now);
    }

    /** Records that {@code player} left. See {@link #joined}. */
    public synchronized Alert left(String player, long now) {
        return transition(player, false, Change.LEFT, now);
    }

    private Alert transition(String player, boolean isOnline, Change change, long now) {
        if (player == null || player.isBlank()) return null;
        String name = player.trim();
        String key = name.toLowerCase(Locale.ROOT) + "|" + change;

        Boolean was = online.get(name.toLowerCase(Locale.ROOT));
        online.put(name.toLowerCase(Locale.ROOT), isOnline);
        // Repeating the same state is not a transition: a server that re-announces a join, or a
        // second join line for one connection, must not produce a second alert.
        if (was != null && was == isOnline) return null;

        Long previous = lastAlert.get(key);
        if (previous != null && now - previous < dedupeMs) return null;
        lastAlert.put(key, now);
        return new Alert(name, change);
    }

    /** True when this player is currently believed to be online. */
    public synchronized boolean isOnline(String player) {
        return player != null && Boolean.TRUE.equals(online.get(player.trim().toLowerCase(Locale.ROOT)));
    }

    /** Players currently believed to be online. */
    public synchronized List<String> onlinePlayers() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : online.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) out.add(entry.getKey());
        }
        return out;
    }

    /** Forgets everything; used on a reconnect, where the old presence state is meaningless. */
    public synchronized void reset() {
        online.clear();
        lastAlert.clear();
    }

    public synchronized int tracked() {
        return online.size();
    }
}
