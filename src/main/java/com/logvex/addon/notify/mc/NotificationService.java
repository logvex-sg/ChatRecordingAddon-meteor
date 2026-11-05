package com.logvex.addon.notify.mc;

import com.logvex.addon.notify.core.Notification;
import com.logvex.addon.notify.core.NotificationCenter;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.notify.core.NotificationType;

/**
 * The process-wide notification service.
 *
 * <p>This is the seam every Log Addon feature talks to. It owns the {@link NotificationCenter} and
 * nothing else: no Minecraft types, no chat, no sound. Modules, the HUD and the GUI read from the
 * centre, and the chat/sound bridge lives in {@code NotificationModule} so that muting output never
 * loses history.
 */
public final class NotificationService {
    private static final NotificationService INSTANCE = new NotificationService();

    private final NotificationCenter center = new NotificationCenter();

    private NotificationService() {
    }

    public static NotificationService get() {
        return INSTANCE;
    }

    public NotificationCenter center() {
        return center;
    }

    /** Records a notification using the type's default priority. */
    public boolean notify(NotificationType type, String source, String title, String message) {
        return center.publish(Notification.of(type, source, title, message));
    }

    /** Records a notification with an explicit priority. */
    public boolean notify(NotificationType type, NotificationPriority priority,
                          String source, String title, String message) {
        return center.publish(new Notification(System.currentTimeMillis(), type, priority,
            source, title, message));
    }
}
