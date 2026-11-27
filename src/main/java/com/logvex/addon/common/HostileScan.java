package com.logvex.addon.common;

import java.util.ArrayList;
import java.util.List;

/**
 * The single "is a monster close?" query, memoised per game tick.
 *
 * <p>Before this existed the same scan was written out four times — the bot's safety stop, the
 * dashboard snapshot, the HUD and the automation modules — and the bot ran it twice per tick (once
 * to decide whether to eat, once for the safety gate). Each copy allocated a box, asked the world
 * for a fresh list of every nearby entity, walked it and threw it away, so one tick could build the
 * same answer three times over. On the game thread that is frame budget spent on nothing.
 *
 * <p>Two properties make it cheap without changing any answer:
 *
 * <ul>
 *   <li><b>One scan answers every radius.</b> A scan records the distance to the <em>nearest</em>
 *       hostile. Nearest distance is monotonic in radius, so a wide scan answers a narrow question
 *       exactly: if nothing is inside 12 blocks, nothing is inside 8 either.
 *   <li><b>The answer is reused within a tick.</b> Entities move between ticks, not within one, so
 *       repeating the query in the same tick is pure waste. The cache is keyed on the world tick,
 *       so it expires exactly when the world advances; a paused client, whose tick number stops
 *       changing, still gets a correct answer because the key never matches a newer tick.
 * </ul>
 *
 * <p>Reuse is exact, not approximate. A cached answer is served only when the tick, the world and
 * the query centre all match and the earlier scan reached at least as far. A moving centre forces a
 * fresh scan rather than an estimate, because an estimate that errs low would let a hostile go
 * unreported, and this query is what pauses the bot when something is about to hit it.
 *
 * <p>The scanning itself is dependency-free: the Minecraft layer passes in a {@code Query} and this
 * class only remembers the result, which keeps the caching policy testable in plain JUnit.
 */
public final class HostileScan {

    /**
     * The result of a scan.
     *
     * @param distance distance to the nearest hostile, or {@link Double#POSITIVE_INFINITY} for none
     * @param count    how many hostiles were found; only used for display
     */
    public record Hit(double distance, int count) {
        public boolean isEmpty() {
            return count == 0;
        }
    }

    /** A scan that found nothing. An infinite distance makes every radius comparison false. */
    public static final Hit NONE = new Hit(Double.POSITIVE_INFINITY, 0);

    /**
     * The raw world query: the nearest hostile within {@code radius} of the given point.
     *
     * <p>An implementation may stop at the first hit if it only needs {@link #NONE} versus a hit;
     * callers compare {@link Hit#distance()} against their own radius and never trust a distance
     * from beyond the radius they asked for.
     */
    @FunctionalInterface
    public interface Query {
        Hit nearest(double x, double y, double z, double radius);
    }

    /** Upper bound on cached answers. One is the norm; this only guards a pathological tick. */
    private static final int MAX_ENTRIES = 8;

    /**
     * Extra reach added to a caller's radius before scanning.
     *
     * <p>Two reasons: the eye height and the hitbox centre differ slightly, so a mob just outside a
     * literal radius can still be the nearest one; and it lets one scan serve a later, larger radius
     * in the same tick instead of rescanning.
     */
    private static final double SLACK = 4.0;

    private record Entry(long tick, Object world, double x, double y, double z,
                         double scannedRadius, Hit hit) {
    }

    /** Newest last, so trimming drops the stalest entry first. */
    private final List<Entry> entries = new ArrayList<>(4);

    /**
     * The nearest hostile within {@code radius}, scanning at most once per tick per centre.
     *
     * @param tick   the world tick; the cache is valid only within one tick
     * @param world  the world the scan belongs to, so a dimension change never reuses an answer
     * @param radius how far to look, in blocks
     * @param query  the world scan, called only on a cache miss
     */
    public Hit nearest(long tick, Object world, double x, double y, double z, double radius, Query query) {
        if (query == null) return NONE;
        double want = Math.max(1, radius);

        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.tick() != tick || entry.world() != world) continue;
            if (entry.scannedRadius() < want) continue;
            if (entry.x() != x || entry.y() != y || entry.z() != z) continue;
            return entry.hit();
        }

        Hit hit = query.nearest(x, y, z, want + SLACK);
        if (hit == null) hit = NONE;
        remember(new Entry(tick, world, x, y, z, want + SLACK, hit));
        return hit;
    }

    /** True when a hostile is within {@code radius} of the point. */
    public boolean anyWithin(long tick, Object world, double x, double y, double z, double radius, Query query) {
        return nearest(tick, world, x, y, z, radius, query).distance() <= Math.max(1, radius);
    }

    /** Forgets every cached answer; called on a world change so nothing carries between worlds. */
    public void clear() {
        entries.clear();
    }

    /** How many answers are currently cached. Used by tests to prove one scan per tick. */
    public int size() {
        return entries.size();
    }

    private void remember(Entry entry) {
        entries.add(entry);
        if (entries.size() > MAX_ENTRIES) entries.remove(0);
    }
}
