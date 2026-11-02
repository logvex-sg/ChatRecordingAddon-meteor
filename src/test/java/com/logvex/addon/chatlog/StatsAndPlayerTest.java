package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.PlayerTracker;
import com.logvex.addon.chatlog.core.StatsCollector;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsAndPlayerTest {

    private static ChatEntry entry(long ts, MessageType type, String sender, String content) {
        return new ChatEntry(ts, type, sender, content, "s", "s1");
    }

    @Test
    void statsCountByTypeAndPlayer() {
        StatsCollector collector = new StatsCollector();
        collector.start(1000);
        collector.record(entry(1100, MessageType.PUBLIC_CHAT, "Notch", "a"));
        collector.record(entry(1200, MessageType.PUBLIC_CHAT, "Notch", "b"));
        collector.record(entry(1300, MessageType.JOIN, "Alex", "Alex joined the game"));
        collector.record(entry(1400, MessageType.DEATH, "Notch", "Notch died"));

        StatsSnapshot snapshot = collector.snapshot(61_000);
        assertEquals(4, snapshot.totalMessages());
        assertEquals(2, snapshot.count(MessageType.PUBLIC_CHAT));
        assertEquals(1, snapshot.count(MessageType.JOIN));
        assertEquals(1, snapshot.count(MessageType.DEATH));
        assertEquals(2, snapshot.uniquePlayers());
        assertEquals(1, snapshot.joinEvents());
        assertEquals(1, snapshot.deathEvents());
    }

    @Test
    void rateIsComputedFromDuration() {
        StatsCollector collector = new StatsCollector();
        collector.start(1000);
        for (int i = 0; i < 60; i++) collector.record(entry(1000 + i, MessageType.PUBLIC_CHAT, "Notch", "m"));
        StatsSnapshot snapshot = collector.snapshot(61_000);
        assertEquals(60.0, snapshot.messagesPerMinute(), 0.001);
    }

    @Test
    void zeroDurationDoesNotDivideByZero() {
        StatsCollector collector = new StatsCollector();
        StatsSnapshot snapshot = collector.snapshot(0);
        assertEquals(0, snapshot.totalMessages());
        assertEquals(0.0, snapshot.messagesPerMinute(), 0.001);
    }

    @Test
    void keywordAlertsAreCounted() {
        StatsCollector collector = new StatsCollector();
        collector.start(0);
        collector.recordKeyword("wts");
        collector.recordKeyword("wts");
        collector.recordKeyword("base");
        StatsSnapshot snapshot = collector.snapshot(1000);
        assertEquals(3, snapshot.keywordAlerts());
        assertEquals(2L, snapshot.keywordCounts().get("wts"));
    }

    @Test
    void topPlayersAreOrderedByMessageCount() {
        StatsCollector collector = new StatsCollector();
        collector.start(0);
        for (int i = 0; i < 5; i++) collector.record(entry(i, MessageType.PUBLIC_CHAT, "Alex", "m"));
        for (int i = 0; i < 3; i++) collector.record(entry(i, MessageType.PUBLIC_CHAT, "Notch", "m"));
        StatsSnapshot snapshot = collector.snapshot(1000);
        assertTrue(snapshot.topPlayers().get(0).startsWith("Alex"));
    }

    @Test
    void playerTrackerAggregatesEvents() {
        PlayerTracker tracker = new PlayerTracker();
        tracker.record(entry(1000, MessageType.JOIN, "Notch", "Notch joined the game"));
        tracker.record(entry(2000, MessageType.PUBLIC_CHAT, "Notch", "hi"));
        tracker.record(entry(3000, MessageType.DEATH, "Notch", "Notch died"));
        tracker.record(entry(4000, MessageType.LEAVE, "Notch", "Notch left the game"));

        PlayerActivity activity = tracker.get("Notch");
        assertEquals(1, activity.messageCount());
        assertEquals(1, activity.joinCount());
        assertEquals(1, activity.leaveCount());
        assertEquals(1, activity.deathCount());
        assertEquals(1000, activity.firstSeen());
        assertEquals(4000, activity.lastSeen());
    }

    @Test
    void playerTrackerIgnoresBlankSenders() {
        PlayerTracker tracker = new PlayerTracker();
        tracker.record(entry(1000, MessageType.SYSTEM, "", "server notice"));
        assertEquals(0, tracker.size());
    }

    @Test
    void playerSearchFiltersAndSortsByRecency() {
        PlayerTracker tracker = new PlayerTracker();
        tracker.record(entry(1000, MessageType.PUBLIC_CHAT, "Alex", "a"));
        tracker.record(entry(5000, MessageType.PUBLIC_CHAT, "Alexa", "b"));
        tracker.record(entry(3000, MessageType.PUBLIC_CHAT, "Bob", "c"));

        List<PlayerActivity> alex = tracker.search("alex");
        assertEquals(2, alex.size());
        assertEquals("Alexa", alex.get(0).name());

        List<PlayerActivity> all = tracker.search("");
        assertEquals(3, all.size());
        assertEquals("Alexa", all.get(0).name());
    }

    @Test
    void snapshotIsEmptyBeforeAnyRecording() {
        assertTrue(StatsSnapshot.empty().isEmpty());
        assertFalse(new StatsCollector().snapshot(0).topPlayers().iterator().hasNext());
    }
}
