package com.logvex.addon.stats;

/**
 * Measures horizontal walking speed from successive player positions.
 *
 * <p>Speed is not something the client is told, so it has to be measured: the distance moved
 * horizontally since the previous sample, divided by the time between samples. A sample that arrives
 * too soon, too late or with no movement is skipped rather than smoothed, so a teleport or a pause
 * does not show up as a wild speed.
 *
 * <p>Kept free of Minecraft types so the arithmetic is unit-testable.
 */
public final class SpeedTracker {
    /** Samples closer together than this are ignored: the divisor would be too small to trust. */
    public static final long MIN_INTERVAL_MS = 40L;

    /** A gap this long means the measurement was interrupted, so the old position is discarded. */
    public static final long MAX_INTERVAL_MS = 2_000L;

    private boolean primed;
    private double lastX;
    private double lastZ;
    private long lastAt;
    private double blocksPerSecond;

    /** Feeds one position sample and returns the current speed estimate. */
    public double sample(double x, double z, long now) {
        if (!primed) {
            primed = true;
            lastX = x;
            lastZ = z;
            lastAt = now;
            return blocksPerSecond;
        }
        long elapsed = now - lastAt;
        if (elapsed < MIN_INTERVAL_MS) return blocksPerSecond;
        if (elapsed > MAX_INTERVAL_MS) {
            lastX = x;
            lastZ = z;
            lastAt = now;
            blocksPerSecond = 0.0;
            return blocksPerSecond;
        }
        double dx = x - lastX;
        double dz = z - lastZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        blocksPerSecond = distance / (elapsed / 1000.0);
        lastX = x;
        lastZ = z;
        lastAt = now;
        return blocksPerSecond;
    }

    /** The last measured speed, in blocks per second. */
    public double blocksPerSecond() {
        return blocksPerSecond;
    }

    /** Drops the reference position, so the next sample starts a fresh measurement. */
    public void reset() {
        primed = false;
        blocksPerSecond = 0.0;
    }
}
