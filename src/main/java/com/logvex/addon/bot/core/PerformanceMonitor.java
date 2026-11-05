package com.logvex.addon.bot.core;

/**
 * Tracks how much work the bot is doing, so a slow machine can be detected and the tick budget
 * reduced. It keeps a rolling average of the time spent per bot tick and counts the actions taken.
 *
 * <p>Only cheap arithmetic happens here: an allocation-free monitor that itself costs time to run
 * would defeat its purpose.
 */
public final class PerformanceMonitor {
    /** How many recent tick durations the average is taken over. */
    private static final int WINDOW = 40;

    private final long[] tickDurations = new long[WINDOW];
    private int tickIndex;
    private int tickSamples;

    private long actionsThisSecond;
    private long actionsTotal;
    private long windowStart;
    private long actionsLastSecond;
    private long ticksTotal;

    public void beginTick(long nowMs) {
        this.windowStart = nowMs;
    }

    /** Records one completed bot tick and its duration in nanoseconds. */
    public void endTick(long durationNanos) {
        tickDurations[tickIndex] = durationNanos;
        tickIndex = (tickIndex + 1) % WINDOW;
        if (tickSamples < WINDOW) tickSamples++;
        ticksTotal++;
    }

    /** Records one action (a mine, a place, a craft). */
    public void recordAction() {
        actionsThisSecond++;
        actionsTotal++;
    }

    /** Called once per second by the scheduler to roll the action counter. */
    public void rollSecond() {
        actionsLastSecond = actionsThisSecond;
        actionsThisSecond = 0;
    }

    /** Average bot-tick duration in microseconds. */
    public double averageTickMicros() {
        if (tickSamples == 0) return 0;
        long sum = 0;
        for (int i = 0; i < tickSamples; i++) sum += tickDurations[i];
        return sum / (double) tickSamples / 1000.0;
    }

    public long actionsPerSecond() {
        return actionsLastSecond;
    }

    public long actionsTotal() {
        return actionsTotal;
    }

    public long ticksTotal() {
        return ticksTotal;
    }

    /**
     * True when the average tick cost suggests the configured budget is too high for this machine.
     * The caller lowers the per-tick work when this is true.
     */
    public boolean isSlow(long budgetMicros) {
        return tickSamples >= WINDOW / 2 && averageTickMicros() > budgetMicros;
    }

    public void reset() {
        tickIndex = 0;
        tickSamples = 0;
        actionsThisSecond = 0;
        actionsTotal = 0;
        actionsLastSecond = 0;
        ticksTotal = 0;
        java.util.Arrays.fill(tickDurations, 0);
    }

    public String summary() {
        return String.format(java.util.Locale.ROOT,
            "%.0f us/tick, %d actions/s, %d total", averageTickMicros(), actionsPerSecond(), actionsTotal);
    }
}
