package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.ChatLogCorruptedException;
import com.logvex.addon.chatlog.core.LogCodec;
import com.logvex.addon.chatlog.core.MessageType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogCodecTest {

    private static ChatEntry entry(long ts, String sender, String content) {
        return new ChatEntry(ts, MessageType.PUBLIC_CHAT, sender, content, "mc.example.com", "s1");
    }

    @Test
    void roundTripPreservesEveryField() throws IOException, ChatLogCorruptedException {
        List<ChatEntry> entries = List.of(
            entry(1_700_000_000_000L, "Notch", "hello world"),
            entry(1_700_000_001_000L, "Alex", "unicode \u00e9\u00e8\u00ea and emoji \u2764"),
            entry(1_700_000_002_000L, "", "server line")
        );

        byte[] encoded = LogCodec.encode(entries, 6);
        List<ChatEntry> decoded = LogCodec.decode(encoded);

        assertEquals(entries, decoded);
    }

    @Test
    void gzipActuallyShrinksRepetitiveData() throws IOException {
        List<ChatEntry> entries = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            entries.add(entry(1_700_000_000_000L + i, "Notch", "the same repeated sentence over and over"));
        }

        byte[] compressed = LogCodec.encode(entries, 9);
        long raw = 0;
        for (ChatEntry e : entries) raw += e.content().length() + 32L;

        assertTrue(compressed.length < raw / 4,
            "compressed " + compressed.length + " should be far below raw " + raw);
    }

    @Test
    void corruptionIsDetected() throws IOException {
        byte[] encoded = LogCodec.encode(List.of(entry(1L, "A", "hello")), 6);
        encoded[encoded.length - 3] ^= 0x5A;

        assertThrows(ChatLogCorruptedException.class, () -> LogCodec.decode(encoded));
    }

    @Test
    void badMagicIsRejected() {
        byte[] junk = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertThrows(ChatLogCorruptedException.class, () -> LogCodec.decode(junk));
    }

    @Test
    void truncatedSegmentIsRejected() throws IOException {
        byte[] encoded = LogCodec.encode(List.of(entry(1L, "A", "hello")), 6);
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length / 2);
        assertThrows(ChatLogCorruptedException.class, () -> LogCodec.decode(truncated));
    }

    @Test
    void emptySegmentRoundTripsToEmptyList() throws IOException, ChatLogCorruptedException {
        byte[] encoded = LogCodec.encode(List.of(), 6);
        assertTrue(LogCodec.decode(encoded).isEmpty());
    }
}
