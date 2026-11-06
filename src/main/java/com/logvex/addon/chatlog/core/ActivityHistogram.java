package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Counts chat activity into fixed-length time buckets so it can be drawn as a graph.
 *
 * <p>A bucket holds the number of messages recorded in one {@code bucketMillis} window. Buckets are
 * created lazily as time advances and the oldest are dropped once the window is full, so the memory
 * cost is fixed at {@link #capacity()} counters no matter how long a session runs. Only counts are
 * stored — no message text — so the histogram is cheap to keep and safe to display.
 *
 * <p>Thread-safe: the client thread records while the HUD thread reads a snapshot.
 */
public final class ActivityHistogram {
    /** One bucket: the time it starts and the number of messages in it. */
    public record Bucket(long start, long count) {
    }

    private final long bucketMillis;
    private final int capacity;
    private final Deque<Bucket> buckets = new ArrayDeque<>();

    /**
     * @param bucketMillis width of one bucket, e.g. 60_000 for per-minute
     * @param capacity     how many buckets to keep; the visible time span is
     *                     {@code bucketMillis * capacity}
     */
    public ActivityHistogram(long bucketMillis, int capacity) {
        this.bucketMillis = Math.max(1, bucketMillis);
        this.capacity = Math.max(1, capacity);
    }

    public long bucketMillis() {
        return bucketMillis;
    }

    public int capacity() {
        return capacity;
    }

    /** Records one message at {@code nowMs}, advancing (and trimming) the buckets as needed. */
    public synchronized void record(long nowMs) {
        long start = nowMs - Math.floorMod(nowMs, bucketMillis);
        Bucket last = buckets.peekLast();
        if (last == null || last.start() < start) {
            // A new bucket begins. If time jumped several buckets (a pause, a lag spike), the empty
            // buckets in between are materialised so the graph shows the gap honestly instead of
            // compressing it away.
            long next = last == null ? start : last.start() + bucketMillis;
            while (next <= start) {
                buckets.addLast(new Bucket(next, 0));
                next += bucketMillis;
            }
        }
        Bucket current = buckets.peekLast();
        if (current != null && current.start() == start) {
            buckets.removeLast();
            buckets.addLast(new Bucket(start, current.count() + 1));
        }
        while (buckets.size() > capacity) buckets.removeFirst();
    }

    /** The most recent {@code count} buckets, oldest first, including empty ones. */
    public synchronized List<Bucket> recent(int count) {
        int want = Math.min(Math.max(1, count), capacity);
        List<Bucket> all = new ArrayList<>(buckets);
        if (all.size() <= want) return all;
        return new ArrayList<>(all.subList(all.size() - want, all.size()));
    }

    /** The largest count in the recent window, used to scale a graph. */
    public synchronized long peak(int count) {
        long peak = 0;
        for (Bucket bucket : recent(count)) peak = Math.max(peak, bucket.count());
        return peak;
    }

    public synchronized long total(int count) {
        long total = 0;
        for (Bucket bucket : recent(count)) total += bucket.count();
        return total;
    }

    public synchronized boolean isEmpty() {
        return buckets.isEmpty();
    }

    public synchronized void clear() {
        buckets.clear();
    }
}
