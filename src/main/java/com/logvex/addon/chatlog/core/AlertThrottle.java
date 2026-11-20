package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps a burst of alerts from flooding chat, sound and webhooks.
 *
 * <p>A mention alert fires once per matching chat line. On a busy server — or one where a watched
 * word is used constantly — that is a line of feedback and a sound for every message, which is
 * unusable. This groups a burst into one alert plus a count of how many were folded into it.
 *
 * <p>Two limits, because either alone is easy to defeat:
 * <ul>
 *   <li>a <em>cooldown</em> per key, so the same name cannot alert twice in a row;</li>
 *   <li>a <em>burst cap</em> across all keys, so a hundred different names cannot alert a hundred
 *       times in the same second.</li>
 * </ul>
 *
 * <p>Deliberately Minecraft-free and time is passed in, so the behaviour is unit-testable.
 */
public final class AlertThrottle {
    /** What to do with one alert attempt. */
    public enum Verdict {
        /** Fire it: nothing of this key alerted recently and the burst has room. */
        FIRE,
        /** Suppress it and count it; the next permitted alert reports how many were folded in. */
        FOLD,
        /** Suppress it silently: the burst cap is spent, so even a count would be noise. */
        DROP
    }

    private final long cooldownMs;
    private final int burstLimit;
    private final long burstWindowMs;

    private final Map<String, Long> lastFiredAt = new HashMap<>();
    private final Map<String, Long> folded = new HashMap<>();
    private final Deque<Long> burst = new ArrayDeque<>();

    /**
     * @param cooldownMs    minimum gap between two alerts for the same key; zero disables it
     * @param burstLimit    most alerts permitted in {@code burstWindowMs}; zero disables the cap
     * @param burstWindowMs width of the burst window
     */
    public AlertThrottle(long cooldownMs, int burstLimit, long burstWindowMs) {
        this.cooldownMs = Math.max(0, cooldownMs);
        this.burstLimit = Math.max(0, burstLimit);
        this.burstWindowMs = Math.max(1, burstWindowMs);
    }

    /** A sensible default: one alert per name every two seconds, at most six per ten seconds. */
    public static AlertThrottle defaults() {
        return new AlertThrottle(2_000, 6, 10_000);
    }

    /**
     * Decides what to do with one alert for {@code key} at {@code now}, and records the decision.
     *
     * <p>A suppressed alert is remembered against its key so that the next alert which is allowed
     * through can say "and 4 more".
     */
    public Verdict admit(String key, long now) {
        String normalised = normalise(key);
        while (!burst.isEmpty() && now - burst.peekFirst() > burstWindowMs) burst.removeFirst();

        if (burstLimit > 0 && burst.size() >= burstLimit) {
            // Over the cap: do not even accumulate a count, or the next report would be a huge
            // number that says more about the server's traffic than about anything the user missed.
            return Verdict.DROP;
        }

        Long last = lastFiredAt.get(normalised);
        if (cooldownMs > 0 && last != null && now - last < cooldownMs) {
            folded.merge(normalised, 1L, Long::sum);
            return Verdict.FOLD;
        }

        lastFiredAt.put(normalised, now);
        burst.addLast(now);
        return Verdict.FIRE;
    }

    /**
     * How many alerts were folded into the one that is about to fire, and clears the count.
     *
     * <p>Called once per alert that {@link #admit} returned {@link Verdict#FIRE} for, so a count is
     * never reported twice.
     */
    public long takeFolded(String key) {
        Long count = folded.remove(normalise(key));
        return count == null ? 0L : count;
    }

    /** How many alerts for {@code key} are currently held back, without clearing them. */
    public long foldedCount(String key) {
        return folded.getOrDefault(normalise(key), 0L);
    }

    /** Forgets all state; called when the watched names change or the module restarts. */
    public void reset() {
        lastFiredAt.clear();
        folded.clear();
        burst.clear();
    }

    /**
     * Drops state for keys that are no longer watched.
     *
     * <p>Only the per-key tables are pruned, and only when something was actually removed. The
     * burst window is left alone: it records how many alerts went out in total, and clearing it
     * would reset the cap on every call. Callers prune on every tick, so clearing here would mean
     * the cap never applied at all.
     */
    public void retain(java.util.Collection<String> keys) {
        if (keys == null) {
            reset();
            return;
        }
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (String key : keys) keep.add(normalise(key));
        lastFiredAt.keySet().retainAll(keep);
        folded.keySet().retainAll(keep);
    }

    private static String normalise(String key) {
        return key == null ? "" : key.strip().toLowerCase(Locale.ROOT);
    }
}
