package com.logvex.addon.bot.core;

/** The result of advancing a task by one step. */
public enum TaskOutcome {
    /** The task needs more ticks. */
    CONTINUE,
    /** The task finished successfully. */
    DONE,
    /** The task cannot continue and should be retried later. */
    RETRY,
    /** The task cannot succeed; a genuine, non-recoverable failure. */
    FAILED,
    /** The task was cancelled by the user. */
    CANCELLED;

    @Override
    public String toString() {
        String lower = name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
