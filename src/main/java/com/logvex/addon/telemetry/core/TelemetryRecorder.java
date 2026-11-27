package com.logvex.addon.telemetry.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded ring of timestamped samples, one per metric, for the app's graphs.
 *
 * <p>Every value here was measured; nothing is interpolated or back-filled. A sample that could not
 * be taken is simply not appended, so a gap in a graph means the client did not have the value
 * (not in a world, no server, the frame counter was not running) rather than a fabricated zero.
 *
 * <p>Capacity is per series and fixed, because the graphs only ever show the recent window and an
 * unbounded series would grow for the length of a session. Minecraft-free, so the retention and
 * downsampling rules are tested directly.
 */
public final class TelemetryRecorder {
    /** Samples kept per series; at one sample a second this is a ten-minute window. */
    public static final int DEFAULT_CAPACITY = 600;

    private final Map<String, Series> series = new LinkedHashMap<>();
    private int capacity = DEFAULT_CAPACITY;

    /** One metric's recent samples, oldest first, held in a fixed ring. */
    public static final class Series {
        private final String name;
        private final long[] timestamps;
        private final double[] values;
        private int start;
        private int size;

        private Series(String name, int capacity) {
            this.name = name == null ? "" : name;
            this.timestamps = new long[Math.max(1, capacity)];
            this.values = new double[Math.max(1, capacity)];
        }

        public String name() {
            return name;
        }

        public int size() {
            return size;
        }

        private void append(long timestamp, double value) {
            if (size == timestamps.length) {
                // Full: overwrite the oldest slot and advance the start. No copy, no growth.
                timestamps[start] = timestamp;
                values[start] = value;
                start = (start + 1) % timestamps.length;
                return;
            }
            int index = (start + size) % timestamps.length;
            timestamps[index] = timestamp;
            values[index] = value;
            size++;
        }

        private void resize(int capacity) {
            int target = Math.max(1, capacity);
            if (target == timestamps.length) return;
            int keep = Math.min(size, target);
            long[] keptTimes = new long[keep];
            double[] keptValues = new double[keep];
            int from = (start + size - keep + timestamps.length) % timestamps.length;
            for (int i = 0; i < keep; i++) {
                int source = (from + i) % timestamps.length;
                keptTimes[i] = timestamps[source];
                keptValues[i] = values[source];
            }
            this.start = 0;
            this.size = keep;
            // The backing arrays are final, so copy the kept tail into the front of each.
            System.arraycopy(keptTimes, 0, timestamps, 0, keep);
            System.arraycopy(keptValues, 0, values, 0, keep);
        }

        /** The sample timestamps, oldest first. */
        public List<Long> timestamps() {
            List<Long> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) out.add(timestamps[(start + i) % timestamps.length]);
            return out;
        }

        /** The sample values, oldest first. */
        public List<Double> values() {
            List<Double> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) out.add(values[(start + i) % timestamps.length]);
            return out;
        }

        /** The newest value, or {@code Double.NaN} when the series is empty. */
        public double latest() {
            return size == 0 ? Double.NaN : values[(start + size - 1) % values.length];
        }

        /** The smallest recorded value, or {@code Double.NaN} when empty. */
        public double min() {
            if (size == 0) return Double.NaN;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < size; i++) best = Math.min(best, values[(start + i) % values.length]);
            return best;
        }

        /** The largest recorded value, or {@code Double.NaN} when empty. */
        public double max() {
            if (size == 0) return Double.NaN;
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < size; i++) best = Math.max(best, values[(start + i) % values.length]);
            return best;
        }

        /** The mean of the recorded values, or {@code Double.NaN} when empty. */
        public double average() {
            if (size == 0) return Double.NaN;
            double total = 0;
            for (int i = 0; i < size; i++) total += values[(start + i) % values.length];
            return total / size;
        }
    }

    public int capacity() {
        return capacity;
    }

    /** Resizes every series, keeping the most recent samples. */
    public void setCapacity(int capacity) {
        this.capacity = Math.max(1, capacity);
        for (Series s : series.values()) s.resize(this.capacity);
    }

    /** Records one sample into one series, creating it on first use. */
    public void record(String metric, long timestamp, double value) {
        if (metric == null || metric.isBlank()) return;
        if (Double.isNaN(value) || Double.isInfinite(value)) return; // a bad reading is not a sample
        series.computeIfAbsent(metric, name -> new Series(name, capacity)).append(timestamp, value);
    }

    /** The series for a metric, or null when it has no samples. */
    public Series series(String metric) {
        return series.get(metric);
    }

    /** The names of every metric that has at least one sample, in first-recorded order. */
    public List<String> metrics() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Series> entry : series.entrySet()) {
            if (entry.getValue().size() > 0) out.add(entry.getKey());
        }
        return out;
    }

    /** Drops every sample of every metric. */
    public void clear() {
        series.clear();
    }

    /**
     * Downsamples a series to at most {@code points} samples by averaging buckets.
     *
     * <p>Used when a graph is asked for a range wider than the retained window: the browser gets a
     * shape it can draw rather than hundreds of points it cannot. Each bucket keeps the mean time of
     * its samples, so the shape stays in the right place on the axis.
     */
    public static Series downsample(Series source, int points) {
        if (source == null || source.size() == 0 || points <= 0) {
            return new Series(source == null ? "" : source.name(), 1);
        }
        if (source.size() <= points) return source;

        Series out = new Series(source.name(), points);
        int bucketSize = (int) Math.ceil(source.size() / (double) points);
        List<Long> times = source.timestamps();
        List<Double> values = source.values();
        for (int bucket = 0; bucket < points; bucket++) {
            int from = bucket * bucketSize;
            int to = Math.min(values.size(), from + bucketSize);
            if (from >= to) break;
            long timeSum = 0;
            double valueSum = 0;
            for (int i = from; i < to; i++) {
                timeSum += times.get(i);
                valueSum += values.get(i);
            }
            out.append(timeSum / (to - from), valueSum / (to - from));
        }
        return out;
    }
}
