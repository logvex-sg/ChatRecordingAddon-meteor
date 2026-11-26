package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.PlayerIdentity;
import com.logvex.addon.chatlog.core.PlayerIdentityTracker;
import com.logvex.addon.chatlog.core.PlayerNote;
import com.logvex.addon.chatlog.core.PlayerSessionTracker;
import com.logvex.addon.chatlog.core.ReportLog;
import com.logvex.addon.chatlog.core.SessionHistory;
import com.logvex.addon.chatlog.core.SessionRecord;
import com.logvex.addon.chatlog.core.SessionTimeline;
import com.logvex.addon.chatlog.core.WebhookDeliveryLog;
import com.logvex.addon.world.core.CountIndex;
import com.logvex.addon.world.core.DiscoveryLog;
import com.logvex.addon.world.core.VisitedChunks;
import com.logvex.addon.world.core.WorldDatabase;
import com.logvex.addon.world.core.WorldIntelligence;
import com.logvex.addon.world.core.WorldProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the promise that nothing this addon stores is deleted without the player asking.
 *
 * <p>Every store here is something a player collected — logged chat, notes they typed, places they
 * found, names they watched. A default cap silently destroys that, and the destruction is invisible
 * until the data is needed. These tests fail if a default cap is ever reintroduced.
 */
class NoAutoWipeTest {

    private static SessionRecord session(int i) {
        return new SessionRecord("s" + i, "server", 1000L + i, 2000L + i, 10, 2, 1, 1, 0, 0);
    }

    @Test
    @DisplayName("session history keeps every session by default")
    void sessionHistoryIsUncappedByDefault() {
        SessionHistory history = new SessionHistory();
        assertEquals(0, history.capacity(), "0 means no cap");
        for (int i = 0; i < 2_000; i++) history.add(session(i));
        assertEquals(2_000, history.size());
    }

    @Test
    @DisplayName("session history still honours an explicit cap")
    void sessionHistoryCapsOnlyWhenAsked() {
        SessionHistory history = new SessionHistory();
        history.setCapacity(10);
        for (int i = 0; i < 50; i++) history.add(session(i));
        assertEquals(10, history.size());
        assertEquals("s40", history.all().get(0).id(), "the oldest are the ones dropped");
    }

    @Test
    @DisplayName("player notes are never dropped by default")
    void notesAreUncappedByDefault() {
        PlayerNote.Store store = new PlayerNote.Store();
        assertEquals(0, PlayerNote.Store.DEFAULT_CAPACITY);
        for (int i = 0; i < 3_000; i++) store.put("Player" + i, "note " + i, 1000L + i);
        assertEquals(3_000, store.size());
    }

    @Test
    @DisplayName("a capped note store still evicts the oldest")
    void notesCapWhenAsked() {
        PlayerNote.Store store = new PlayerNote.Store(5);
        for (int i = 0; i < 20; i++) store.put("Player" + i, "note " + i, 1000L + i);
        assertEquals(5, store.size());
    }

    @Test
    @DisplayName("the report log survives a reload when uncapped")
    void reportLogRoundTripsWhenUncapped() {
        ReportLog log = new ReportLog();
        for (int i = 0; i < 50; i++) {
            log.add(new ReportLog.Record(1000L + i, "Reporter" + i, "reason " + i, "report", true, ""));
        }
        List<String> encoded = log.encodeAll();

        ReportLog reloaded = new ReportLog();
        reloaded.loadAll(encoded);
        assertEquals(50, reloaded.size(), "an uncapped log must not lose records on load");
    }

    @Test
    @DisplayName("the delivery log keeps every attempt by default")
    void deliveryLogIsUncapped() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        for (int i = 0; i < 500; i++) log.add(1000L + i, "discord.com", true, 1, "ok");
        assertEquals(500, log.size());
    }

    @Test
    @DisplayName("the session timeline keeps every milestone and lists them all")
    void timelineIsUncappedAndListsEverything() {
        SessionTimeline timeline = new SessionTimeline();
        for (int i = 0; i < 1_000; i++) {
            timeline.add(1000L + i, SessionTimeline.Kind.DEATH, "subject" + i, "detail");
        }
        assertEquals(1_000, timeline.size());
        assertEquals(1_000, timeline.recent(0).size(), "0 means all, not none");
        assertEquals(1_000, timeline.search("").size(), "an empty filter must not hide everything");
    }

    @Test
    @DisplayName("player sessions are never dropped by default")
    void playerSessionsAreUncapped() {
        PlayerSessionTracker tracker = new PlayerSessionTracker();
        assertEquals(0, tracker.capacity());
        for (int i = 0; i < 500; i++) {
            tracker.record(new com.logvex.addon.chatlog.core.ChatEntry(
                1000L + i, com.logvex.addon.chatlog.core.MessageType.JOIN,
                "P" + i, "joined", "server", "session"));
        }
        assertEquals(500, tracker.size());
    }

    @Test
    @DisplayName("the identity index keeps every account by default")
    void identitiesAreUncapped() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        assertEquals(0, tracker.capacity());
        for (int i = 0; i < 3_000; i++) {
            tracker.observe("uuid-" + i, "Name" + i, 1000L + i);
        }
        assertEquals(3_000, tracker.size());
    }

    @Test
    @DisplayName("a capped identity index evicts the least recently seen")
    void identitiesCapWhenAsked() {
        PlayerIdentityTracker tracker = new PlayerIdentityTracker();
        tracker.setCapacity(100);
        for (int i = 0; i < 500; i++) tracker.observe("uuid-" + i, "Name" + i, 1000L + i);
        assertEquals(100, tracker.size());
    }

    @Test
    @DisplayName("world stores keep everything and are saved that way")
    void worldStoresAreUncapped() {
        WorldDatabase db = new WorldDatabase();
        for (int i = 0; i < 1_000; i++) {
            db.record("d", i * 100, 64, 0, "", com.logvex.addon.world.core.LocationCategory.OTHER, i + 1);
        }
        assertEquals(1_000, db.size());

        VisitedChunks chunks = new VisitedChunks();
        for (int i = 0; i < 1_000; i++) chunks.visit("d", i, 0, i + 1);
        assertEquals(1_000, chunks.size());

        CountIndex biomes = new CountIndex();
        for (int i = 0; i < 1_000; i++) biomes.observe("biome" + i, 0, 64, 0, i + 1);
        assertEquals(1_000, biomes.size());

        DiscoveryLog log = new DiscoveryLog();
        for (int i = 0; i < 1_000; i++) {
            log.add(DiscoveryLog.Kind.BIOME, "d", i, 64, 0, "b" + i, i + 1);
        }
        assertEquals(1_000, log.size());
    }

    @Test
    @DisplayName("one capacity setting reaches every profile and every store in it")
    void intelligenceCapacityFansOut() {
        WorldIntelligence intelligence = new WorldIntelligence();
        WorldProfile profile = intelligence.enterWorld("server", "world", "World", 1);
        for (int i = 0; i < 50; i++) {
            intelligence.recordLocation("d", i * 100, 64, 0, "place" + i,
                com.logvex.addon.world.core.LocationCategory.OTHER, i + 1);
        }
        assertEquals(50, profile.locations().size());

        intelligence.setCapacity(10);
        assertEquals(10, profile.locations().size(), "the cap applies to the live profile");
        assertEquals(10, profile.chunks().capacity(), "and to every store in it");

        // A profile created afterwards must inherit the same cap.
        WorldProfile later = intelligence.enterWorld("other", "world", "Other", 2);
        assertEquals(10, later.locations().capacity());
    }

    @Test
    @DisplayName("loading a large file does not truncate it when no cap is set")
    void loadingKeepsEverythingWhenUncapped() {
        DiscoveryLog source = new DiscoveryLog();
        for (int i = 0; i < 900; i++) {
            source.add(DiscoveryLog.Kind.LOCATION, "d", i, 64, 0, "p" + i, i + 1);
        }

        DiscoveryLog restored = new DiscoveryLog();
        restored.load(source.toMaps());
        assertEquals(900, restored.size(), "a reload must not silently trim the file");
    }

    @Test
    @DisplayName("an identity list round-trips without losing accounts")
    void identityLoadKeepsEverything() {
        PlayerIdentityTracker source = new PlayerIdentityTracker();
        for (int i = 0; i < 2_500; i++) source.observe("uuid-" + i, "Name" + i, 1000L + i);

        PlayerIdentityTracker restored = new PlayerIdentityTracker();
        List<PlayerIdentity> loaded = source.search("");
        restored.load(loaded);
        assertEquals(2_500, restored.size());
        assertTrue(restored.size() > PlayerIdentityTracker.MAX_ACCOUNTS,
            "the old cap must no longer apply by default");
    }
}
