package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.PlayerIdentity;
import com.logvex.addon.chatlog.core.PlayerIdentityTracker;
import com.logvex.addon.chatlog.core.Watchlist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the observed player-identity tracker and the watchlist.
 *
 * <p>The tracker must only ever report a rename that the same UUID actually produced, and must never
 * invent a change from two different accounts sharing a name.
 */
class PlayerIdentityTrackerTest {

    @Test
    @DisplayName("a first observation is not a rename")
    void firstObservationIsNotARename() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        assertNull(tracker.observe("uuid-1", "Alice", 1_000L));
        assertEquals(1, tracker.size());
        assertEquals("Alice", tracker.get("uuid-1").currentName());
        assertEquals(0, tracker.renameCount());
    }

    @Test
    @DisplayName("the same name observed again is not a rename and does not duplicate")
    void repeatObservationIsNotARename() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        tracker.observe("uuid-1", "Alice", 1_000L);
        assertNull(tracker.observe("uuid-1", "Alice", 2_000L));
        assertEquals(1, tracker.get("uuid-1").nameCount());
        assertEquals(2_000L, tracker.get("uuid-1").lastSeen());
    }

    @Test
    @DisplayName("a new name for the same UUID is a rename that keeps the old name")
    void renameKeepsHistory() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        tracker.observe("uuid-1", "Alice", 1_000L);
        PlayerIdentityTracker.Rename rename = tracker.observe("uuid-1", "Alicia", 2_000L);

        assertNotNull(rename);
        assertEquals("Alice", rename.from());
        assertEquals("Alicia", rename.to());
        assertEquals("uuid-1", rename.uuid());

        PlayerIdentity identity = tracker.get("uuid-1");
        assertEquals("Alicia", identity.currentName());
        assertEquals("Alice", identity.previousName());
        assertEquals(2, identity.nameCount());
        assertEquals(1, identity.changeCount());
    }

    @Test
    @DisplayName("two different accounts sharing a name are tracked separately, never merged")
    void differentAccountsAreNeverMerged() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        tracker.observe("uuid-1", "Alex", 1_000L);
        tracker.observe("uuid-2", "Alex", 1_000L);

        assertEquals(2, tracker.size());
        assertEquals(0, tracker.renameCount());
    }

    @Test
    @DisplayName("a blank uuid or name is ignored, so nothing is guessed")
    void blankInputIsIgnored() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        assertNull(tracker.observe(null, "Alice", 1_000L));
        assertNull(tracker.observe("", "Alice", 1_000L));
        assertNull(tracker.observe("uuid-1", "", 1_000L));
        assertNull(tracker.observe("uuid-1", "   ", 1_000L));
        assertEquals(0, tracker.size());
    }

    @Test
    @DisplayName("search matches the current and previous names")
    void searchMatchesAnyObservedName() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        tracker.observe("uuid-1", "Alice", 1_000L);
        tracker.observe("uuid-1", "Alicia", 2_000L);
        tracker.observe("uuid-2", "Bob", 3_000L);

        assertEquals(1, tracker.search("alice").size());
        assertEquals(1, tracker.search("alicia").size());
        assertEquals(2, tracker.search("").size());
        assertEquals(1, tracker.search("bob").size());
        assertEquals(0, tracker.search("carol").size());
    }

    @Test
    @DisplayName("renamed() lists only accounts seen under more than one name")
    void renamedFiltersSingleNameAccounts() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        tracker.observe("uuid-1", "Alice", 1_000L);
        tracker.observe("uuid-1", "Alicia", 2_000L);
        tracker.observe("uuid-2", "Bob", 3_000L);

        List<PlayerIdentity> renamed = tracker.renamed();
        assertEquals(1, renamed.size());
        assertEquals("Alicia", renamed.get(0).currentName());
    }

    @Test
    @DisplayName("the rename log is bounded and newest first")
    void renameLogIsBounded() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker(3);
        tracker.observe("uuid-1", "N0", 1L);
        for (int i = 1; i <= 5; i++) tracker.observe("uuid-1", "N" + i, i + 1L);

        List<PlayerIdentityTracker.Rename> renames = tracker.renames();
        assertEquals(3, renames.size());
        assertEquals("N5", renames.get(0).to());
        assertEquals("N3", renames.get(2).to());
    }

    @Test
    @DisplayName("forget removes one account without touching the others")
    void forgetRemovesOneAccount() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        tracker.observe("uuid-1", "Alice", 1_000L);
        tracker.observe("uuid-2", "Bob", 1_000L);

        assertTrue(tracker.forget("uuid-1"));
        assertFalse(tracker.forget("uuid-1"));
        assertEquals(1, tracker.size());
        assertNotNull(tracker.get("uuid-2"));
    }

    @Test
    @DisplayName("the watchlist is case-insensitive, bounded and de-duplicated")
    void watchlistBehaviour() {
        Watchlist watchlist = new Watchlist();
        assertTrue(watchlist.add("Alice"));
        assertFalse(watchlist.add("alice"));
        assertTrue(watchlist.contains("ALICE"));
        assertTrue(watchlist.remove("alice"));
        assertFalse(watchlist.contains("Alice"));

        watchlist.set(List.of("Bob", "Carol", "bob"));
        assertEquals(2, watchlist.size());
        assertTrue(watchlist.contains("bob"));
        assertTrue(watchlist.contains("carol"));

        watchlist.set(List.of("", "   "));
        assertTrue(watchlist.isEmpty());
    }
}
