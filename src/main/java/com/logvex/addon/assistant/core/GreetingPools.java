package com.logvex.addon.assistant.core;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * Chooses a greeting that suits the time of day.
 *
 * <p>Four buckets, so the wording can be tuned per period without a separate setting per hour:
 * morning (05:00–11:59), afternoon (12:00–16:59), evening (17:00–21:59) and night (22:00–04:59).
 * The boundaries are local clock time, which is what a player experiences; a server in another
 * timezone is irrelevant to how a greeting reads.
 *
 * <p>Each bucket holds a small pool of variants and one is picked at random, so a busy chat does not
 * get the identical line every time. A blank bucket falls back to a neutral greeting rather than
 * sending nothing.
 */
public final class GreetingPools {
    /** One time-of-day bucket with its greeting alternatives. */
    public record Bucket(String id, String label, int fromHour, int toHour, String pool) {
        /** True when {@code hour} falls inside this bucket, wrapping past midnight when needed. */
        public boolean covers(int hour) {
            if (fromHour <= toHour) return hour >= fromHour && hour <= toHour;
            return hour >= fromHour || hour <= toHour;
        }
    }

    /** The neutral fallback, used when a bucket is blank or the pools are switched off. */
    public static final String NEUTRAL = "Hello {player}!";

    private GreetingPools() {
    }

    /** The four buckets, in the order they are documented. */
    public static List<Bucket> buckets() {
        return List.of(
            new Bucket("morning", "Morning (05:00-11:59)", 5, 11,
                "Good morning, {player}!|Morning, {player}.|Rise and shine, {player}!"),
            new Bucket("afternoon", "Afternoon (12:00-16:59)", 12, 16,
                "Good afternoon, {player}!|Afternoon, {player}.|Hello there, {player}!"),
            new Bucket("evening", "Evening (17:00-21:59)", 17, 21,
                "Good evening, {player}!|Evening, {player}.|Nice to see you, {player}."),
            new Bucket("night", "Night (22:00-04:59)", 22, 4,
                "Up late, {player}?|Good night, {player}.|Still awake, {player}?")
        );
    }

    /** The bucket that covers {@code hour} (0-23), or the first bucket when the hour is invalid. */
    public static Bucket bucketFor(int hour) {
        int normalised = ((hour % 24) + 24) % 24;
        for (Bucket bucket : buckets()) {
            if (bucket.covers(normalised)) return bucket;
        }
        return buckets().get(0);
    }

    /** The bucket for {@code time}. */
    public static Bucket bucketFor(LocalTime time) {
        return bucketFor(time == null ? 12 : time.getHour());
    }

    /**
     * The greeting to send now.
     *
     * <p>A blank pool in the chosen bucket falls back to {@link #NEUTRAL}, so enabling greetings
     * without filling in every period still produces something sensible.
     *
     * @param pools  the four configured pools, keyed by bucket id; null means "use the defaults"
     * @param random the randomness source, or null for the shared one
     */
    public static String greeting(java.util.Map<String, String> pools, LocalTime now,
                                  java.util.Random random) {
        Bucket bucket = bucketFor(now);
        String pool = pools == null ? bucket.pool() : pools.get(bucket.id());
        if (pool == null || pool.isBlank()) pool = bucket.pool();
        if (pool == null || pool.isBlank()) return NEUTRAL;
        String chosen = ResponseVariants.pick(pool, random);
        return chosen.isBlank() ? NEUTRAL : chosen;
    }

    /** The pool configured for {@code id}, or the built-in default. */
    public static String poolFor(java.util.Map<String, String> pools, String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (pools != null) {
            String configured = pools.get(key);
            if (configured != null && !configured.isBlank()) return configured;
        }
        for (Bucket bucket : buckets()) {
            if (bucket.id().equals(key)) return bucket.pool();
        }
        return "";
    }

    /** The bucket id that covers the given hour, as text. */
    public static String bucketIdFor(int hour) {
        return bucketFor(hour).id();
    }
}
