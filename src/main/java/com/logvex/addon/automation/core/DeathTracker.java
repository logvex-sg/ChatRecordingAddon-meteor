package com.logvex.addon.automation.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Remembers where the player died, so the spot can be found again.
 *
 * <p>Dying far from home means losing the coordinates, and the vanilla death screen is gone the
 * moment it is dismissed. This keeps a short history per world so {@code !deaths} can list recent
 * deaths and {@code !back} can return the most recent one.
 *
 * <p>Bounded on purpose: a player who dies repeatedly must not grow this without limit. Oldest
 * entries are dropped first.
 */
public final class DeathTracker {
    /** How many deaths to remember. Enough to cover a session, small enough to stay trivial. */
    public static final int MAX_ENTRIES = 20;

    /** One remembered death. */
    public record Death(String world, int x, int y, int z, long when, String cause) {
        /** {@code x, y, z} as coordinates are normally written. */
        public String coords() {
            return x + ", " + y + ", " + z;
        }
    }

    private final Deque<Death> deaths = new ArrayDeque<>();
    private final int limit;

    public DeathTracker() {
        this(MAX_ENTRIES);
    }

    public DeathTracker(int limit) {
        this.limit = Math.max(1, limit);
    }

    /** Records a death, dropping the oldest entry when the history is full. */
    public synchronized void record(String world, int x, int y, int z, long when, String cause) {
        deaths.addLast(new Death(world == null ? "" : world, x, y, z, when,
            cause == null ? "" : cause.trim()));
        while (deaths.size() > limit) deaths.removeFirst();
    }

    /** The most recent death, or null when the player has not died yet. */
    public synchronized Death last() {
        return deaths.peekLast();
    }

    /** The most recent death in {@code world}, or null when there is none. */
    public synchronized Death lastIn(String world) {
        String target = world == null ? "" : world;
        var it = deaths.descendingIterator();
        while (it.hasNext()) {
            Death death = it.next();
            if (death.world().equals(target)) return death;
        }
        return null;
    }

    /** Every remembered death, newest first. */
    public synchronized List<Death> recent() {
        List<Death> out = new ArrayList<>(deaths);
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized int size() {
        return deaths.size();
    }

    public synchronized void clear() {
        deaths.clear();
    }
}
