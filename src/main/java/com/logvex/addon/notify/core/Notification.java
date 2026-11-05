package com.logvex.addon.notify.core;

import com.logvex.addon.common.Messages;

import java.util.Objects;

/**
 * One immutable notification.
 *
 * <p>A notification is plain data so it can be produced on any thread, stored in a bounded ring,
 * rendered by the HUD and the GUI, and serialised for the history without dragging Minecraft types
 * along. {@code source} is the module or service that raised it, which is what the GUI groups by.
 */
public record Notification(
    long timestamp,
    NotificationType type,
    NotificationPriority priority,
    String source,
    String title,
    String message
) {
    public Notification {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(priority, "priority");
        source = source == null ? "" : source.trim();
        title = title == null ? "" : title.trim();
        message = message == null ? "" : message;
    }

    /** Convenience factory with a sensible default priority. */
    public static Notification of(NotificationType type, String source, String title, String message) {
        return new Notification(System.currentTimeMillis(), type,
            defaultPriority(type), source, title, message);
    }

    /** The priority a type uses when the caller does not care to pick one. */
    public static NotificationPriority defaultPriority(NotificationType type) {
        return switch (type) {
            case TASK_FAILED, CONNECTION, CONFIG_ERROR -> NotificationPriority.HIGH;
            case INVENTORY_FULL, MATERIAL_SHORTAGE, EQUIPMENT_WARNING -> NotificationPriority.HIGH;
            case PERFORMANCE -> NotificationPriority.NORMAL;
            case TASK_COMPLETED, WAYPOINT_REACHED, PLAYER_ACTIVITY -> NotificationPriority.NORMAL;
            case IMPORTANT_CHAT -> NotificationPriority.HIGH;
            case GENERAL -> NotificationPriority.LOW;
        };
    }

    /** A single-line rendering used by chat and the HUD, with a human-readable clock time. */
    public String line() {
        String body = title.isEmpty() ? message : (message.isEmpty() ? title : title + " " + message);
        return Messages.clock(timestamp) + " " + body;
    }

    /** True when the notification matches a case-insensitive keyword across its visible fields. */
    public boolean matches(String keyword) {
        if (keyword == null || keyword.isBlank()) return true;
        String needle = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        return source.toLowerCase(java.util.Locale.ROOT).contains(needle)
            || title.toLowerCase(java.util.Locale.ROOT).contains(needle)
            || message.toLowerCase(java.util.Locale.ROOT).contains(needle)
            || type.name().toLowerCase(java.util.Locale.ROOT).contains(needle);
    }
}
