package com.logvex.addon.bot.core;

/**
 * Decides how the bot recovers from a failure.
 *
 * <p>Recovery is intentionally conservative. A failure is retried a bounded number of times with a
 * growing backoff; once the budget is exhausted the objective is recorded as failed and the bot
 * stops rather than looping forever. Nothing here touches the world, so the policy can be tested in
 * isolation.
 */
public final class BotRecovery {
    /** How a failure should be handled. */
    public enum Decision {
        RETRY,
        SKIP,
        ABORT
    }

    private final int retryLimit;
    private final long backoffMs;
    private int attempts;
    private long lastAttemptAt;

    public BotRecovery(int retryLimit, long backoffMs) {
        this.retryLimit = Math.max(1, retryLimit);
        this.backoffMs = Math.max(0, backoffMs);
    }

    /** Decides what to do about a failure, and records the attempt. */
    public Decision onFailure(boolean recoverable, long nowMs) {
        if (!recoverable) return Decision.ABORT;
        attempts++;
        lastAttemptAt = nowMs;
        if (attempts > retryLimit) return Decision.ABORT;
        return Decision.RETRY;
    }

    /** True when enough time has passed to retry after the last attempt. */
    public boolean readyToRetry(long nowMs) {
        return nowMs - lastAttemptAt >= backoffMs;
    }

    public int attempts() {
        return attempts;
    }

    /** How long to wait before the next retry, growing with the attempt count. */
    public long currentBackoff() {
        return backoffMs * Math.max(1, attempts);
    }

    public void reset() {
        attempts = 0;
        lastAttemptAt = 0;
    }
}
