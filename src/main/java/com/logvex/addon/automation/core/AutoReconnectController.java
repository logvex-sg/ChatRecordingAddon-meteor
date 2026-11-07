package com.logvex.addon.automation.core;

/**
 * Bounded backoff schedule for reconnecting after a disconnect.
 *
 * <p>The delay grows exponentially from {@code baseDelayMs} up to {@code maxDelayMs} and is capped
 * by {@code maxAttempts}; once the budget is spent the controller reports that it has given up so
 * the module can stop trying instead of hammering the server forever. It is a pure timer, so the
 * Minecraft layer only has to perform the reconnect and tell the controller whether it worked.
 */
public final class AutoReconnectController {
    private boolean enabled;
    private long baseDelayMs;
    private long maxDelayMs;
    private int maxAttempts;

    private int attempts;
    private long lastAttemptAt;
    private boolean waiting;
    private boolean gaveUp;

    public AutoReconnectController(long baseDelayMs, long maxDelayMs, int maxAttempts) {
        this.baseDelayMs = Math.max(250, baseDelayMs);
        this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setBaseDelayMs(long value) {
        this.baseDelayMs = Math.max(250, value);
        if (maxDelayMs < baseDelayMs) maxDelayMs = baseDelayMs;
    }

    public void setMaxDelayMs(long value) {
        this.maxDelayMs = Math.max(baseDelayMs, value);
    }

    public void setMaxAttempts(int value) {
        this.maxAttempts = Math.max(1, value);
    }

    /** Resets the schedule, e.g. after a successful join. */
    public void reset() {
        attempts = 0;
        lastAttemptAt = 0;
        waiting = false;
        gaveUp = false;
    }

    public int attempts() {
        return attempts;
    }

    public boolean gaveUp() {
        return gaveUp;
    }

    /** Called on a disconnect; starts a new attempt window. */
    public void onDisconnect(long now) {
        if (!enabled) return;
        waiting = true;
        lastAttemptAt = now;
    }

    /** The delay before attempt number {@code attempt} (1-based), bounded by the maximum. */
    public long delayFor(int attempt) {
        if (attempt <= 1) return baseDelayMs;
        long delay = baseDelayMs;
        for (int i = 1; i < attempt; i++) {
            delay = Math.min(maxDelayMs, delay * 2);
            if (delay >= maxDelayMs) break;
        }
        return delay;
    }

    /**
     * Whether it is time to try reconnecting now.
     *
     * @return true when an attempt should be made; false while waiting or once given up
     */
    public boolean shouldAttempt(long now) {
        if (!enabled || !waiting || gaveUp) return false;
        if (attempts >= maxAttempts) {
            gaveUp = true;
            waiting = false;
            return false;
        }
        long delay = delayFor(attempts + 1);
        return now - lastAttemptAt >= delay;
    }

    /** Records that an attempt was made, returning the attempt number it was. */
    public int markAttempt(long now) {
        attempts++;
        lastAttemptAt = now;
        return attempts;
    }

    /** Called when a reconnect succeeded. */
    public void onConnected() {
        reset();
    }

    public boolean waiting() {
        return waiting;
    }

    /** A short status line for the HUD and chat. */
    public String status() {
        if (!enabled) return "off";
        if (gaveUp) return "gave up after " + attempts + " attempt" + (attempts == 1 ? "" : "s");
        if (waiting) return "waiting, attempt " + (attempts + 1) + "/" + maxAttempts;
        return "idle (" + attempts + " attempts)";
    }
}
