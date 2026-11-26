package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.chatlog.core.ChatDigest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DigestCardTest {

    private static ChatDigest.Summary sample() {
        return new ChatDigest.Summary(
            0L, 3_600_000L, 42, 5,
            Map.of(com.logvex.addon.chatlog.core.MessageType.PUBLIC_CHAT, 30L,
                   com.logvex.addon.chatlog.core.MessageType.JOIN, 6L),
            List.of(new ChatDigest.PlayerLine("Steve", 18, 2, 0, 1),
                    new ChatDigest.PlayerLine("Alex", 9, 1, 1, 0)),
            List.of("diamond", "help"),
            6, 3, 2);
    }

    @Test
    void rendersAsABoxedCard() {
        ReplyFormat.Embed card = ChatDigest.embed(sample(), 3_600_000L);
        assertNotNull(card);

        List<String> lines = ReplyFormat.render(card, 48);
        for (String line : lines) System.out.println(line);

        assertTrue(lines.get(0).startsWith("┌"), "card has no top border: " + lines.get(0));
        assertTrue(lines.get(lines.size() - 1).startsWith("└"), "card is not closed");
        assertTrue(lines.stream().anyMatch(l -> l.contains("42")), "the message count is missing");
        assertTrue(lines.stream().anyMatch(l -> l.contains("Steve")), "the leaderboard is missing");
        // Every row is framed, so nothing leaks out of the box as a bare sentence.
        for (String line : lines) {
            assertTrue(line.startsWith("┌") || line.startsWith("└") || line.startsWith("│"),
                "a row escaped the frame: " + line);
            assertEquals(48, line.length(), "a row is not the card width: " + line);
        }
    }

    @Test
    void anEmptyDigestHasNoCard() {
        assertNull(ChatDigest.embed(null, 0L));
        ChatDigest.Summary empty = new ChatDigest.Summary(0L, 0L, 0, 0, Map.of(), List.of(),
            List.of(), 0, 0, 0);
        assertNull(ChatDigest.embed(empty, 0L), "an empty digest should print nothing, not a frame");
    }
}
