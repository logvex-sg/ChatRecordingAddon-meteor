package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.HighlightAnnouncer;
import com.logvex.addon.chatlog.core.MessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A highlighted message must be announced once, not once per tick.
 *
 * <p>The bug these tests lock down: {@code ChatMonitorModule} re-scanned the last few recorded
 * entries on every client tick and logged each highlighted one. A single highlighted message was
 * therefore logged twenty times a second, forever, and the log filled with the same line. The
 * announcer is the guard that makes a repeated delivery a no-op.
 */
class HighlightAnnouncerTest {

    private static ChatEntry entry(long timestamp, String sender, String content) {
        return new ChatEntry(timestamp, MessageType.PUBLIC_CHAT, sender, content, "mc.example.org", "s1");
    }

    @Test
    @DisplayName("an entry is announced the first time and never again")
    void announcesOnce() {
        HighlightAnnouncer announcer = new HighlightAnnouncer();
        ChatEntry entry = entry(1_000, "logvex", "found diamonds");

        assertTrue(announcer.claim(entry), "the first sighting is announced");
        for (int tick = 0; tick < 20; tick++) {
            assertFalse(announcer.claim(entry),
                "re-reading the same entry on a later tick must not announce it again");
        }
    }

    @Test
    @DisplayName("a new message is still announced after an earlier one")
    void distinctEntriesAnnounced() {
        HighlightAnnouncer announcer = new HighlightAnnouncer();
        assertTrue(announcer.claim(entry(1_000, "logvex", "found diamonds")));
        assertTrue(announcer.claim(entry(1_001, "logvex", "found diamonds")));
        assertTrue(announcer.claim(entry(1_001, "Steve", "found diamonds")));
        assertTrue(announcer.claim(entry(1_001, "logvex", "found netherite")));
    }

    @Test
    @DisplayName("a repeat of the same text later in the session is a separate message")
    void sameTextLaterIsAnnounced() {
        HighlightAnnouncer announcer = new HighlightAnnouncer();
        assertTrue(announcer.claim(entry(1_000, "logvex", "gg")));
        assertTrue(announcer.claim(entry(60_000, "logvex", "gg")),
            "a player typing the same line a minute later is a new message");
    }

    @Test
    @DisplayName("a null entry is never announced")
    void nullIsIgnored() {
        assertFalse(new HighlightAnnouncer().claim(null));
    }

    @Test
    @DisplayName("memory stays bounded on a long session")
    void memoryIsBounded() {
        HighlightAnnouncer announcer = new HighlightAnnouncer();
        for (int i = 0; i < 5_000; i++) {
            assertTrue(announcer.claim(entry(i, "logvex", "line " + i)));
        }
        assertTrue(announcer.remembered() <= 64,
            "only recent announcements are kept, was " + announcer.remembered());
    }

    @Test
    @DisplayName("resetting forgets announcements, so a re-enable starts clean")
    void resetClears() {
        HighlightAnnouncer announcer = new HighlightAnnouncer();
        ChatEntry entry = entry(1_000, "logvex", "found diamonds");
        assertTrue(announcer.claim(entry));
        announcer.reset();
        assertEquals(0, announcer.remembered());
        assertTrue(announcer.claim(entry));
    }

    @Test
    @DisplayName("the monitor announces from the entry stream instead of polling on a tick")
    void monitorDoesNotPollOnTick() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/com/logvex/addon/chatlog/modules/ChatMonitorModule.java"));

        assertTrue(source.contains("LiveListener"),
            "the monitor must react to each recorded entry");
        assertFalse(source.contains("TickEvent"),
            "polling the recent buffer on a tick is what logged the same line twenty times a second");
        assertFalse(source.contains("service.recent(5)"),
            "the monitor must not re-scan the recent buffer to find messages to announce");
    }
}
