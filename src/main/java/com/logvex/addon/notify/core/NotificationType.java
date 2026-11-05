package com.logvex.addon.notify.core;

/**
 * The kind of event a notification reports.
 *
 * <p>These mirror the categories the spec asks the notification centre to cover. The type is what a
 * user filters on ("only show me task failures"), and what the GUI uses to pick an icon or colour,
 * so it must stay a closed set rather than a free-form string.
 */
public enum NotificationType {
    TASK_COMPLETED,
    TASK_FAILED,
    MATERIAL_SHORTAGE,
    INVENTORY_FULL,
    EQUIPMENT_WARNING,
    WAYPOINT_REACHED,
    IMPORTANT_CHAT,
    PLAYER_ACTIVITY,
    CONNECTION,
    PERFORMANCE,
    CONFIG_ERROR,
    /** Anything that does not fit the categories above. */
    GENERAL;

    /** Parses a name case-insensitively, returning {@code fallback} when it does not match. */
    public static NotificationType parse(String value, NotificationType fallback) {
        if (value == null) return fallback;
        for (NotificationType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) return type;
        }
        return fallback;
    }
}
