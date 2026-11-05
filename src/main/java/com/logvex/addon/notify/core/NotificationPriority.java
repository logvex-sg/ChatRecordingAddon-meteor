package com.logvex.addon.notify.core;

/**
 * How loudly a notification should be surfaced.
 *
 * <p>Priority is a routing hint, not a description: the notification centre filters on it, the HUD
 * colours by it and the chat bridge decides whether a low-priority entry is worth printing at all.
 * It is deliberately a small, ordered set so a user can reason about "show me NORMAL and above".
 */
public enum NotificationPriority {
    /** Background bookkeeping the user rarely needs to see. */
    LOW(0),
    /** The default; normal status changes. */
    NORMAL(1),
    /** Something the user should notice soon. */
    HIGH(2),
    /** Something is broken or requires action now. */
    CRITICAL(3);

    private final int weight;

    NotificationPriority(int weight) {
        this.weight = weight;
    }

    /** Ordering weight; higher means more important. */
    public int weight() {
        return weight;
    }

    /** True when this priority is at least {@code other}. */
    public boolean atLeast(NotificationPriority other) {
        return other == null || weight >= other.weight;
    }

    /** Parses a name case-insensitively, returning {@code fallback} when it does not match. */
    public static NotificationPriority parse(String value, NotificationPriority fallback) {
        if (value == null) return fallback;
        for (NotificationPriority priority : values()) {
            if (priority.name().equalsIgnoreCase(value.trim())) return priority;
        }
        return fallback;
    }
}
