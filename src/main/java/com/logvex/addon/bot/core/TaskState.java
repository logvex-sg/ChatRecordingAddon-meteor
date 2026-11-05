package com.logvex.addon.bot.core;

/** Lifecycle of a single task. */
public enum TaskState {
    PENDING,
    READY,
    RUNNING,
    BLOCKED,
    DONE,
    FAILED,
    CANCELLED,
    SKIPPED;

    public boolean isFinished() {
        return this == DONE || this == FAILED || this == CANCELLED || this == SKIPPED;
    }

    @Override
    public String toString() {
        String lower = name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
