package com.logvex.addon.notify;

import com.logvex.addon.notify.core.Notification;
import com.logvex.addon.notify.core.NotificationCenter;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.notify.core.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationCenterTest {

    private static Notification note(long ts, NotificationType type, NotificationPriority priority,
                                     String source, String title) {
        return new Notification(ts, type, priority, source, title, "body");
    }

    @Test
    @DisplayName("a published notification is stored and delivered to listeners")
    void publishesAndDelivers() {
        NotificationCenter center = new NotificationCenter();
        List<Notification> seen = new ArrayList<>();
        center.addListener(seen::add);

        assertTrue(center.publish(note(1_000, NotificationType.TASK_COMPLETED,
            NotificationPriority.NORMAL, "auto-bot", "done")));

        assertEquals(1, center.size());
        assertEquals(1, seen.size());
        assertEquals("done", seen.get(0).title());
        assertEquals(1, center.accepted());
        assertEquals(0, center.suppressed());
    }

    @Test
    @DisplayName("history is bounded: it never grows past the limit")
    void historyIsBounded() {
        NotificationCenter center = new NotificationCenter();
        for (int i = 0; i < NotificationCenter.HISTORY_LIMIT + 50; i++) {
            // Distinct titles so the dedupe limiter does not swallow them; timestamps advance past
            // the burst window so the rate limiter does not either.
            center.publish(new Notification(10_000L * (i + 1), NotificationType.GENERAL,
                NotificationPriority.LOW, "src", "n" + i, ""));
        }
        assertEquals(NotificationCenter.HISTORY_LIMIT, center.size());
        // Newest first.
        assertEquals("n" + (NotificationCenter.HISTORY_LIMIT + 49), center.recent(1).get(0).title());
    }

    @Test
    @DisplayName("identical notifications inside the dedupe window are suppressed")
    void dedupesIdentical() {
        NotificationCenter center = new NotificationCenter();
        assertTrue(center.publish(note(1_000, NotificationType.CONNECTION,
            NotificationPriority.HIGH, "auto-bot", "disconnected")));
        // Same source/type/title, 500ms later: inside the dedupe window.
        assertFalse(center.publish(note(1_500, NotificationType.CONNECTION,
            NotificationPriority.HIGH, "auto-bot", "disconnected")));
        assertEquals(1, center.size());
        assertEquals(1, center.suppressed());

        // After the window it is allowed again.
        assertTrue(center.publish(note(9_000, NotificationType.CONNECTION,
            NotificationPriority.HIGH, "auto-bot", "disconnected")));
        assertEquals(2, center.size());
    }

    @Test
    @DisplayName("a burst of distinct notifications is rate limited")
    void limitsBurst() {
        NotificationCenter center = new NotificationCenter();
        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            if (center.publish(new Notification(1_000, NotificationType.GENERAL,
                NotificationPriority.LOW, "spam", "m" + i, ""))) {
                accepted++;
            }
        }
        // The burst cap is 25 per second; far fewer than 100 should pass.
        assertTrue(accepted <= 25, "accepted=" + accepted);
        assertTrue(center.suppressed() > 0);
    }

    @Test
    @DisplayName("the minimum priority filter drops lower-priority notifications")
    void filtersByPriority() {
        NotificationCenter center = new NotificationCenter();
        center.setMinPriority(NotificationPriority.HIGH);

        assertFalse(center.publish(note(1_000, NotificationType.GENERAL,
            NotificationPriority.LOW, "src", "quiet")));
        assertTrue(center.publish(note(2_000, NotificationType.TASK_FAILED,
            NotificationPriority.HIGH, "src", "loud")));
        assertEquals(1, center.size());
    }

    @Test
    @DisplayName("the type filter only allows selected types")
    void filtersByType() {
        NotificationCenter center = new NotificationCenter();
        center.setEnabledTypes(EnumSet.of(NotificationType.TASK_FAILED));

        assertFalse(center.publish(note(1_000, NotificationType.TASK_COMPLETED,
            NotificationPriority.NORMAL, "src", "ok")));
        assertTrue(center.publish(note(2_000, NotificationType.TASK_FAILED,
            NotificationPriority.HIGH, "src", "bad")));
        assertEquals(1, center.size());
    }

    @Test
    @DisplayName("muting a source drops everything from it")
    void mutesSource() {
        NotificationCenter center = new NotificationCenter();
        center.muteSource("auto-bot");
        assertTrue(center.isMuted("AUTO-BOT"));
        assertFalse(center.publish(note(1_000, NotificationType.GENERAL,
            NotificationPriority.NORMAL, "auto-bot", "x")));
        assertTrue(center.publish(note(2_000, NotificationType.GENERAL,
            NotificationPriority.NORMAL, "chat-logger", "x")));
        assertEquals(1, center.size());
    }

    @Test
    @DisplayName("search matches across the visible fields")
    void searches() {
        NotificationCenter center = new NotificationCenter();
        center.publish(note(1_000, NotificationType.TASK_COMPLETED,
            NotificationPriority.NORMAL, "auto-bot", "Gathered 64 oak logs"));
        center.publish(note(2_000, NotificationType.CONFIG_ERROR,
            NotificationPriority.HIGH, "chat-logger", "Bad config"));

        assertEquals(1, center.search("oak", 50).size());
        assertEquals(1, center.search("AUTO-BOT", 50).size());
        assertEquals(2, center.search("", 50).size());
        assertEquals(1, center.search("config_error", 50).size());
        assertEquals(0, center.search("missing", 50).size());
    }

    @Test
    @DisplayName("a throwing listener cannot break the producer or other listeners")
    void isolatesListenerFailures() {
        NotificationCenter center = new NotificationCenter();
        List<Notification> seen = new ArrayList<>();
        center.addListener(n -> { throw new IllegalStateException("boom"); });
        center.addListener(seen::add);

        assertTrue(center.publish(note(1_000, NotificationType.GENERAL,
            NotificationPriority.NORMAL, "src", "x")));
        assertEquals(1, seen.size());
    }

    @Test
    @DisplayName("recent() returns newest first and respects the limit")
    void recentOrdering() {
        NotificationCenter center = new NotificationCenter();
        for (int i = 0; i < 5; i++) {
            center.publish(new Notification(10_000L * (i + 1), NotificationType.GENERAL,
                NotificationPriority.LOW, "src", "n" + i, ""));
        }
        List<Notification> top = center.recent(2);
        assertEquals(2, top.size());
        assertEquals("n4", top.get(0).title());
        assertEquals("n3", top.get(1).title());
    }

    @Test
    @DisplayName("default priority is derived from the notification type")
    void defaultPriorities() {
        assertEquals(NotificationPriority.HIGH,
            Notification.defaultPriority(NotificationType.TASK_FAILED));
        assertEquals(NotificationPriority.HIGH,
            Notification.defaultPriority(NotificationType.INVENTORY_FULL));
        assertEquals(NotificationPriority.NORMAL,
            Notification.defaultPriority(NotificationType.TASK_COMPLETED));
    }

    @Test
    @DisplayName("priority ordering is respected")
    void priorityOrdering() {
        assertTrue(NotificationPriority.CRITICAL.atLeast(NotificationPriority.HIGH));
        assertFalse(NotificationPriority.LOW.atLeast(NotificationPriority.NORMAL));
        assertEquals(NotificationPriority.HIGH,
            NotificationPriority.parse("high", NotificationPriority.LOW));
        assertEquals(NotificationPriority.LOW,
            NotificationPriority.parse("nonsense", NotificationPriority.LOW));
    }
}
