package com.logvex.addon.companion.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-client token bucket, so one misbehaving app cannot starve the server.
 *
 * <p>The bucket refills at a fixed rate and is capped at the burst size, which is the classic
 * behaviour: a normal burst of requests from a GUI is allowed, a sustained flood is not. Buckets are
 * keyed by remote address and pruned when they have been idle long enough, so the map cannot grow
 * without bound.
 */
public final class CompanionRateLimiter {
    private static final long IDLE_EVICT_MS = 10 * 60 * 1000L;

    private final int perMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public CompanionRateLimiter(int perMinute) {
        this.perMinute = Math.max(1, perMinute);
    }

    /** True when the caller may proceed. */
    public boolean allow(String key) {
        String id = key == null || key.isBlank() ? "unknown" : key;
        long now = System.currentTimeMillis();
        Bucket bucket = buckets.computeIfAbsent(id, k -> new Bucket(now, perMinute));
        synchronized (bucket) {
            refill(bucket, now);
            if (bucket.tokens < 1.0) {
                bucket.lastSeen = now;
                return false;
            }
            bucket.tokens -= 1.0;
            bucket.lastSeen = now;
            return true;
        }
    }

    private void refill(Bucket bucket, long now) {
        long elapsed = Math.max(0, now - bucket.lastRefill);
        double ratePerMs = perMinute / 60000.0;
        bucket.tokens = Math.min(perMinute, bucket.tokens + elapsed * ratePerMs);
        bucket.lastRefill = now;
    }

    /** Drops idle buckets so a long-running server does not accumulate one entry per address ever seen. */
    public void prune() {
        long cutoff = System.currentTimeMillis() - IDLE_EVICT_MS;
        buckets.entrySet().removeIf(entry -> entry.getValue().lastSeen < cutoff);
    }

    public int trackedClients() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;
        private long lastSeen;

        private Bucket(long now, int capacity) {
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastSeen = now;
        }
    }
}
