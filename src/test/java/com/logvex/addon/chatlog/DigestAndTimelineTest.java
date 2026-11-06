package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.SessionTimeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigestAndTimelineTest {

    private static ChatEntry entry(long ts, MessageType type, String sender, String content) {
        return new ChatEntry(ts, type, sender, content, "s", "s1");
    }

    // ------------------------------------------------------------------ ChatDigest

    @Test
    void digestCountsTypesAndPlayers() {
        List<ChatEntry> entries = List.of(
            entry(1000, MessageType.PUBLIC_CHAT, "Notch", "hello"),
            entry(2000, MessageType.PUBLIC_CHAT, "Notch", "again"),
            entry(3000, MessageType.PUBLIC_CHAT, "Alex", "hi"),
            entry(4000, MessageType.JOIN, "Alex", "Alex joined the game"),
            entry(5000, MessageType.LEAVE, "Notch", "Notch left the game"),
            entry(6000, MessageType.DEATH, "Alex", "Alex died")
        );

        ChatDigest.Summary summary = ChatDigest.summarise(entries, 5);
        assertEquals(6, summary.totalMessages());
        assertEquals(2, summary.uniquePlayers());
        assertEquals(3, summary.count(MessageType.PUBLIC_CHAT));
        assertEquals(1, summary.joinEvents());
        assertEquals(1, summary.leaveEvents());
        assertEquals(1, summary.deathEvents());
        assertEquals(1000, summary.fromTimestamp());
        assertEquals(6000, summary.toTimestamp());
    }

    @Test
    void digestOrdersTopPlayersByMessageCount() {
        List<ChatEntry> entries = List.of(
            entry(1, MessageType.PUBLIC_CHAT, "Alex", "a"),
            entry(2, MessageType.PUBLIC_CHAT, "Notch", "b"),
            entry(3, MessageType.PUBLIC_CHAT, "Notch", "c"),
            entry(4, MessageType.PUBLIC_CHAT, "Notch", "d")
        );
        ChatDigest.Summary summary = ChatDigest.summarise(entries, 5);
        assertEquals("Notch", summary.topPlayers().get(0).name());
        assertEquals(3, summary.topPlayers().get(0).messages());
        assertEquals("Alex", summary.topPlayers().get(1).name());
    }

    @Test
    void digestRespectsTopPlayerLimit() {
        List<ChatEntry> entries = List.of(
            entry(1, MessageType.PUBLIC_CHAT, "A", "a"),
            entry(2, MessageType.PUBLIC_CHAT, "B", "b"),
            entry(3, MessageType.PUBLIC_CHAT, "C", "c")
        );
        assertEquals(2, ChatDigest.summarise(entries, 2).topPlayers().size());
    }

    @Test
    void emptyDigestReportsNothingRatherThanZeros() {
        ChatDigest.Summary summary = ChatDigest.summarise(List.of(), 5);
        assertTrue(summary.isEmpty());
        List<String> lines = ChatDigest.lines(summary, System.currentTimeMillis());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).toLowerCase().contains("no messages"));
    }

    @Test
    void digestLinesUseReadableUnitsAndPluralisation() {
        ChatDigest.Summary summary = ChatDigest.summarise(
            List.of(entry(1000, MessageType.PUBLIC_CHAT, "Notch", "x")), 5);
        String first = ChatDigest.lines(summary, System.currentTimeMillis()).get(0);
        assertTrue(first.contains("1 message"), first);
        assertFalse(first.contains("1 messages"), first);
        assertTrue(first.contains("1 player"), first);
    }

    @Test
    void digestHeadlineSummarisesInOneLine() {
        ChatDigest.Summary summary = ChatDigest.summarise(List.of(
            entry(1, MessageType.PUBLIC_CHAT, "Notch", "a"),
            entry(2, MessageType.JOIN, "Alex", "Alex joined the game")
        ), 5);
        String headline = ChatDigest.headline(summary);
        assertTrue(headline.contains("2 messages"), headline);
        assertTrue(headline.contains("1 joins"), headline);
    }

    @Test
    void digestCanFindAPlayerByName() {
        ChatDigest.Summary summary = ChatDigest.summarise(
            List.of(entry(1, MessageType.PUBLIC_CHAT, "Notch", "a")), 5);
        assertTrue(ChatDigest.mentions(summary, "not"));
        assertFalse(ChatDigest.mentions(summary, "herobrine"));
    }

    // ------------------------------------------------------------------ SessionTimeline

    @Test
    void timelineRecordsMilestonesFromEntriesOnly() {
        SessionTimeline timeline = new SessionTimeline();
        timeline.observe(entry(1000, MessageType.JOIN, "Alex", "Alex joined the game"));
        timeline.observe(entry(2000, MessageType.PUBLIC_CHAT, "Notch", "hello"));
        timeline.observe(entry(3000, MessageType.LEAVE, "Alex", "Alex left the game"));

        assertEquals(2, timeline.size());
        assertEquals(1, timeline.count(SessionTimeline.Kind.JOIN));
        assertEquals(1, timeline.count(SessionTimeline.Kind.LEAVE));
    }

    @Test
    void timelineIsBoundedAndDropsOldestFirst() {
        SessionTimeline timeline = new SessionTimeline(3);
        for (int i = 0; i < 10; i++) {
            timeline.add(i, SessionTimeline.Kind.MILESTONE, "p" + i, "");
        }
        assertEquals(3, timeline.size());
        // The three most recent remain: p7, p8, p9.
        assertEquals("p7", timeline.events().get(0).subject());
        assertEquals("p9", timeline.events().get(2).subject());
    }

    @Test
    void timelineRecentReturnsNewestFirst() {
        SessionTimeline timeline = new SessionTimeline();
        timeline.add(1, SessionTimeline.Kind.JOIN, "a", "");
        timeline.add(2, SessionTimeline.Kind.JOIN, "b", "");
        timeline.add(3, SessionTimeline.Kind.JOIN, "c", "");
        List<SessionTimeline.Event> recent = timeline.recent(2);
        assertEquals("c", recent.get(0).subject());
        assertEquals("b", recent.get(1).subject());
    }

    @Test
    void timelineSearchMatchesSubjectAndKind() {
        SessionTimeline timeline = new SessionTimeline();
        timeline.add(1, SessionTimeline.Kind.JOIN, "Alex", "");
        timeline.add(2, SessionTimeline.Kind.DEATH, "Notch", "");

        assertEquals(1, timeline.search("alex").size());
        assertEquals(1, timeline.search("death").size());
        assertEquals(2, timeline.search("").size());
        assertEquals(0, timeline.search("herobrine").size());
    }

    @Test
    void timelineEventLineReadsCleanly() {
        SessionTimeline.Event event = new SessionTimeline.Event(1, SessionTimeline.Kind.JOIN, "Alex", "");
        assertEquals("Join: Alex", event.line());
        SessionTimeline.Event plain = new SessionTimeline.Event(1, SessionTimeline.Kind.SESSION_START, "", "");
        assertEquals("Session start", plain.line());
    }
}
