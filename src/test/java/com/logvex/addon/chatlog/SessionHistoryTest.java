package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.LogSession;
import com.logvex.addon.chatlog.core.SessionHistory;
import com.logvex.addon.chatlog.core.SessionRecord;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the bounded session history and the session record it stores.
 */
class SessionHistoryTest {

    private static StatsSnapshot stats(long messages, int players, long joins, long leaves, long deaths, long alerts) {
        return new StatsSnapshot(0, 0, 60_000, messages, 1.0, players, List.of(),
            java.util.Map.of(), joins, leaves, deaths, alerts, java.util.Map.of());
    }

    @Test
    @DisplayName("a record is built from the session and its final statistics")
    void recordFromSessionAndStats() {
        LogSession session = new LogSession("s1", "play.example.com", 1_000L, 61_000L);
        SessionRecord record = SessionRecord.from(session, stats(120, 4, 3, 2, 1, 5));

        assertNotNull(record);
        assertEquals("s1", record.id());
        assertEquals("play.example.com", record.server());
        assertEquals(120, record.messages());
        assertEquals(4, record.uniquePlayers());
        assertEquals(3, record.joins());
        assertEquals(2, record.leaves());
        assertEquals(1, record.deaths());
        assertEquals(5, record.keywordAlerts());
        assertEquals(60_000L, record.durationMs(System.currentTimeMillis()));
    }

    @Test
    @DisplayName("a null session yields no record; a missing snapshot is treated as empty")
    void nullSessionYieldsNoRecord() {
        assertNull(SessionRecord.from(null, stats(1, 1, 0, 0, 0, 0)));

        LogSession session = new LogSession("s1", "srv", 1_000L, 2_000L);
        SessionRecord record = SessionRecord.from(session, null);
        assertNotNull(record);
        assertEquals(0, record.messages());
    }

    @Test
    @DisplayName("an open session uses now for its duration")
    void openSessionUsesNow() {
        SessionRecord record = new SessionRecord("s1", "srv", 1_000L, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(record.open());
        assertEquals(1_500L, record.durationMs(2_500L));
    }

    @Test
    @DisplayName("the history keeps the newest records and honours its cap")
    void historyIsBounded() {
        SessionHistory history = new SessionHistory(3);
        for (int i = 0; i < 5; i++) {
            history.add(new SessionRecord("s" + i, "srv", i, i + 1, i, 0, 0, 0, 0, 0));
        }
        assertEquals(3, history.size());
        assertEquals("s4", history.latest().id());
        assertEquals(5, history.totalRecorded());
    }

    @Test
    @DisplayName("a duplicate session id is not added twice")
    void duplicateSessionIdIgnored() {
        SessionHistory history = new SessionHistory(10);
        history.add(new SessionRecord("s1", "srv", 1, 2, 10, 0, 0, 0, 0, 0));
        history.add(new SessionRecord("s1", "srv", 1, 2, 10, 0, 0, 0, 0, 0));
        assertEquals(1, history.size());
    }

    @Test
    @DisplayName("search matches the server, the id and the date text")
    void searchMatchesFields() {
        SessionHistory history = new SessionHistory(10);
        history.add(new SessionRecord("s1", "alpha.example.com", 1_000L, 2_000L, 10, 0, 0, 0, 0, 0));
        history.add(new SessionRecord("s2", "beta.example.com", 3_000L, 4_000L, 20, 0, 0, 0, 0, 0));

        assertEquals(1, history.search("alpha").size());
        assertEquals(1, history.search("s2").size());
        assertEquals(2, history.search("").size());
        assertEquals(0, history.search("gamma").size());
    }

    @Test
    @DisplayName("totals sum across the stored sessions")
    void totalsSumAcrossSessions() {
        SessionHistory history = new SessionHistory(10);
        history.add(new SessionRecord("s1", "a", 0, 1_000L, 10, 0, 0, 0, 0, 0));
        history.add(new SessionRecord("s2", "b", 0, 2_000L, 20, 0, 0, 0, 0, 0));

        assertEquals(30, history.totalMessages());
        assertEquals(3_000L, history.totalDurationMs(9_999L));
    }

    @Test
    @DisplayName("lowering the cap drops the oldest records immediately")
    void loweringCapDropsOldest() {
        SessionHistory history = new SessionHistory(5);
        for (int i = 0; i < 5; i++) {
            history.add(new SessionRecord("s" + i, "srv", i, i + 1, i, 0, 0, 0, 0, 0));
        }
        history.setCapacity(2);
        assertEquals(2, history.size());
        assertEquals("s4", history.latest().id());
    }

    @Test
    @DisplayName("records round-trip through their map form")
    void recordRoundTrips() {
        SessionRecord record = new SessionRecord("s1", "srv", 1_000L, 2_000L, 42, 3, 4, 5, 6, 7);
        SessionRecord restored = SessionRecord.fromMap(record.toMap());
        assertEquals(record, restored);
    }

    @Test
    @DisplayName("load replaces the history and keeps order")
    void loadReplacesHistory() {
        SessionHistory history = new SessionHistory(10);
        history.add(new SessionRecord("old", "srv", 1, 2, 1, 0, 0, 0, 0, 0));

        history.load(List.of(
            new SessionRecord("a", "srv", 1, 2, 1, 0, 0, 0, 0, 0),
            new SessionRecord("b", "srv", 3, 4, 2, 0, 0, 0, 0, 0)));

        assertEquals(2, history.size());
        assertEquals("a", history.all().get(0).id());
        assertEquals("b", history.latest().id());
    }

    @Test
    @DisplayName("the history round-trips through its JSON file, and a corrupt file loads as empty")
    void storeRoundTripAndRecovery(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        SessionHistory original = new SessionHistory(10);
        original.add(new SessionRecord("s1", "alpha", 1_000L, 2_000L, 42, 3, 4, 5, 6, 7));
        original.add(new SessionRecord("s2", "beta", 3_000L, 4_000L, 11, 2, 0, 0, 0, 0));

        assertTrue(com.logvex.addon.chatlog.core.SessionHistoryStore.save(dir, original));

        SessionHistory restored = new SessionHistory(10);
        com.logvex.addon.chatlog.core.SessionHistoryStore.load(dir, restored);
        assertEquals(2, restored.size());
        assertEquals("s1", restored.all().get(0).id());
        assertEquals(42, restored.all().get(0).messages());
        assertEquals("beta", restored.latest().server());

        // A corrupt file must load as empty, never throw.
        java.nio.file.Files.writeString(
            com.logvex.addon.chatlog.core.SessionHistoryStore.fileIn(dir), "{ not json");
        SessionHistory recovered = new SessionHistory(10);
        com.logvex.addon.chatlog.core.SessionHistoryStore.load(dir, recovered);
        assertEquals(0, recovered.size());
    }
}
