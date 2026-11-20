package com.logvex.addon.automation;

import com.logvex.addon.automation.core.PresenceWatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Watchlist presence transitions.
 *
 * <p>The point of this class is that a repeated announcement is not news. These tests pin that
 * down, because a server that sends a join line twice is common and the alert would otherwise fire
 * twice for one connection.
 */
class PresenceWatcherTest {

    @Nested
    @DisplayName("transitions")
    class Transitions {

        @Test
        @DisplayName("a first join alerts")
        void firstJoin() {
            PresenceWatcher watcher = new PresenceWatcher(0);
            PresenceWatcher.Alert alert = watcher.joined("Steve", 1_000);
            assertNotNull(alert);
            assertEquals(PresenceWatcher.Change.JOINED, alert.change());
            assertEquals("Steve", alert.player());
        }

        @Test
        @DisplayName("a repeated join does not alert again")
        void repeatedJoin() {
            PresenceWatcher watcher = new PresenceWatcher(0);
            assertNotNull(watcher.joined("Steve", 1_000));
            assertNull(watcher.joined("Steve", 5_000),
                "a server that announces a join twice must not raise two alerts");
        }

        @Test
        @DisplayName("leave after join alerts")
        void leaveAfterJoin() {
            PresenceWatcher watcher = new PresenceWatcher(0);
            watcher.joined("Steve", 1_000);
            PresenceWatcher.Alert alert = watcher.left("Steve", 2_000);
            assertNotNull(alert);
            assertEquals(PresenceWatcher.Change.LEFT, alert.change());
        }

        @Test
        @DisplayName("names are matched without regard to case")
        void caseInsensitive() {
            PresenceWatcher watcher = new PresenceWatcher(0);
            assertNotNull(watcher.joined("Steve", 1_000));
            assertNull(watcher.joined("steve", 1_100),
                "the same player in different case is still the same player");
        }

        @Test
        @DisplayName("a blank name is ignored")
        void blankName() {
            PresenceWatcher watcher = new PresenceWatcher(0);
            assertNull(watcher.joined("", 1_000));
            assertNull(watcher.joined(null, 1_000));
        }
    }

    @Nested
    @DisplayName("duplicate suppression")
    class Duplicates {

        @Test
        @DisplayName("an identical alert inside the window is dropped")
        void insideWindow() {
            PresenceWatcher watcher = new PresenceWatcher(3_000);
            assertNotNull(watcher.joined("Steve", 1_000));
            // The state changed away and back, but too fast: this is the duplicate announcement
            // case, not a real rejoin.
            watcher.left("Steve", 1_500);
            assertNull(watcher.joined("Steve", 2_000));
        }

        @Test
        @DisplayName("an alert after the window is kept")
        void afterWindow() {
            PresenceWatcher watcher = new PresenceWatcher(3_000);
            watcher.joined("Steve", 1_000);
            watcher.left("Steve", 5_000);
            assertNotNull(watcher.joined("Steve", 10_000),
                "a rejoin minutes later is real news");
        }
    }

    @Nested
    @DisplayName("presence state")
    class Presence {

        @Test
        @DisplayName("online tracks the last event")
        void online() {
            PresenceWatcher watcher = new PresenceWatcher(0);
            watcher.joined("Steve", 1_000);
            assertTrue(watcher.isOnline("Steve"));
            assertEquals(1, watcher.onlinePlayers().size());
            watcher.left("Steve", 2_000);
            assertTrue(!watcher.isOnline("Steve"));
            assertEquals(0, watcher.onlinePlayers().size());
        }

        @Test
        @DisplayName("reset forgets everyone")
        void reset() {
            PresenceWatcher watcher = new PresenceWatcher(0);
            watcher.joined("Steve", 1_000);
            watcher.reset();
            assertEquals(0, watcher.tracked());
            // After a reset the next join is a first join again, so it must alert.
            assertNotNull(watcher.joined("Steve", 2_000));
        }
    }
}
