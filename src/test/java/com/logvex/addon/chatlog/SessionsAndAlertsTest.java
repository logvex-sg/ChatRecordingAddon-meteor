package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.AlertLog;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.DuplicateGrouper;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.PlayerSession;
import com.logvex.addon.chatlog.core.PlayerSessionTracker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionsAndAlertsTest {

    private static ChatEntry entry(long ts, MessageType type, String sender, String content) {
        return new ChatEntry(ts, type, sender, content, "server", "s1");
    }

    // ------------------------------------------------------------------ PlayerSessionTracker

    @Test
    void measuresTimeBetweenJoinAndLeave() {
        PlayerSessionTracker tracker = new PlayerSessionTracker();
        tracker.record(entry(1000, MessageType.JOIN, "Alex", "Alex joined the game"));
        tracker.record(entry(5000, MessageType.PUBLIC_CHAT, "Alex", "hi"));
        tracker.record(entry(9000, MessageType.LEAVE, "Alex", "Alex left the game"));

        PlayerSession session = tracker.get("Alex");
        assertNotNull(session);
        assertEquals(8000, session.totalOnlineMs());
        assertEquals(1, session.sessions());
        assertFalse(session.online());
        assertTrue(session.hasMeasuredTime());
        assertEquals(8000, session.onlineMs(50_000));
    }

    @Test
    void unknownSessionReportsNoMeasuredTime() {
        PlayerSessionTracker tracker = new PlayerSessionTracker();
        // A player already online when we connected never produced a join.
        tracker.record(entry(1000, MessageType.PUBLIC_CHAT, "Sam", "hello"));

        PlayerSession session = tracker.get("Sam");
        assertNull(session);
        // A chat-only player is not tracked at all, so there is nothing to misreport.
        assertEquals(0, tracker.size());
    }

    @Test
    void openSessionCountsLiveTime() {
        PlayerSessionTracker tracker = new PlayerSessionTracker();
        tracker.record(entry(1000, MessageType.JOIN, "Alex", "Alex joined the game"));

        PlayerSession session = tracker.get("Alex");
        assertTrue(session.online());
        assertFalse(session.hasMeasuredTime());
        assertEquals(0, session.totalOnlineMs());
        // onlineMs() includes the in-progress stretch, but hasMeasuredTime() stays false.
        assertEquals(4000, session.onlineMs(5000));
    }

    @Test
    void secondJoinWithoutLeaveDoesNotDoubleCount() {
        PlayerSessionTracker tracker = new PlayerSessionTracker();
        tracker.record(entry(1000, MessageType.JOIN, "Alex", "Alex joined the game"));
        tracker.record(entry(3000, MessageType.JOIN, "Alex", "Alex joined the game"));
        tracker.record(entry(6000, MessageType.LEAVE, "Alex", "Alex left the game"));

        PlayerSession session = tracker.get("Alex");
        assertEquals(3000, session.totalOnlineMs());
        assertEquals(1, session.sessions());
    }

    @Test
    void exclusionStopsTrackingAndForgetRemoves() {
        PlayerSessionTracker tracker = new PlayerSessionTracker();
        tracker.setExcluded(List.of("bob"));
        tracker.record(entry(1000, MessageType.JOIN, "Bob", "Bob joined the game"));
        tracker.record(entry(1000, MessageType.JOIN, "Alex", "Alex joined the game"));

        assertNull(tracker.get("Bob"));
        assertNotNull(tracker.get("Alex"));
        assertTrue(tracker.isExcluded("BOB"));
        assertTrue(tracker.forget("Alex"));
        assertNull(tracker.get("Alex"));
    }

    @Test
    void searchFiltersAndSortsByRecency() {
        PlayerSessionTracker tracker = new PlayerSessionTracker();
        tracker.record(entry(1000, MessageType.JOIN, "Alex", "x"));
        tracker.record(entry(2000, MessageType.JOIN, "Bob", "x"));

        List<PlayerSession> all = tracker.search("");
        assertEquals("Bob", all.get(0).name());
        assertEquals(1, tracker.search("ale").size());
    }

    @Test
    void playerSessionsAreCapped() {
        PlayerSessionTracker tracker = new PlayerSessionTracker();
        for (int i = 0; i < PlayerSessionTracker.MAX_PLAYERS + 50; i++) {
            tracker.record(entry(1000 + i, MessageType.JOIN, "P" + i, "x"));
        }
        assertEquals(PlayerSessionTracker.MAX_PLAYERS, tracker.size());
    }

    // ------------------------------------------------------------------ DuplicateGrouper

    @Test
    void groupsOnlyConsecutiveRepeats() {
        List<ChatEntry> feed = List.of(
            entry(5000, MessageType.PUBLIC_CHAT, "Alex", "spam"),
            entry(4000, MessageType.PUBLIC_CHAT, "Alex", "spam"),
            entry(3000, MessageType.PUBLIC_CHAT, "Alex", "other"),
            entry(2000, MessageType.PUBLIC_CHAT, "Alex", "spam")
        );
        List<DuplicateGrouper.Group> groups = DuplicateGrouper.group(feed);
        assertEquals(3, groups.size());
        assertEquals(2, groups.get(0).count());
        assertEquals(1, groups.get(1).count());
        assertEquals(1, groups.get(2).count());
        assertEquals("spam", groups.get(0).entry().content());
    }

    @Test
    void differentSendersAreNotMerged() {
        List<ChatEntry> feed = List.of(
            entry(2000, MessageType.PUBLIC_CHAT, "Alex", "hi"),
            entry(1000, MessageType.PUBLIC_CHAT, "Bob", "hi")
        );
        assertEquals(2, DuplicateGrouper.group(feed).size());
    }

    @Test
    void suffixIsEmptyForSingleMessages() {
        assertEquals("", DuplicateGrouper.suffix(1));
        assertEquals(" (x3)", DuplicateGrouper.suffix(3));
    }

    // ------------------------------------------------------------------ AlertLog

    @Test
    void alertLogIsBoundedAndNewestFirst() {
        AlertLog log = new AlertLog(3);
        log.record(AlertLog.Kind.KEYWORD, "a", "1");
        log.record(AlertLog.Kind.WEBHOOK, "b", "2");
        log.record(AlertLog.Kind.ERROR, "c", "3");
        log.record(AlertLog.Kind.KEYWORD, "d", "4");

        assertEquals(3, log.size());
        assertEquals(4, log.totalRecorded());
        List<AlertLog.Alert> recent = log.recent("", 10);
        assertEquals("d", recent.get(0).title());
        assertEquals(3, recent.size());
        // "a" was evicted by the bound, so only the newest keyword remains in the window.
        assertEquals(1, log.count(AlertLog.Kind.KEYWORD));
    }

    @Test
    void alertLogFiltersByKindIdAndDetail() {
        AlertLog log = new AlertLog();
        log.record(AlertLog.Kind.KEYWORD, "diamond", "Alex: found diamond");
        log.record(AlertLog.Kind.WEBHOOK, "Delivery failed", "connection refused");

        assertEquals(1, log.recent("webhook", 10).size());
        assertEquals(1, log.recent("diamond", 10).size());
        assertEquals(1, log.recent("refused", 10).size());
        assertEquals(0, log.recent("nothing", 10).size());
    }

    @Test
    void clearingResetsCounters() {
        AlertLog log = new AlertLog();
        log.record(AlertLog.Kind.ERROR, "x", "y");
        log.clear();
        assertEquals(0, log.size());
        assertEquals(0, log.totalRecorded());
        assertTrue(log.recent("", 10).isEmpty());
    }

    @Test
    void nullKindFallsBackToError() {
        AlertLog log = new AlertLog();
        log.record(null, "x", "y");
        assertEquals(1, log.count(AlertLog.Kind.ERROR));
    }
}
