package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.ReportBuffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportBufferTest {

    private static ChatEntry entry(long ts, String content) {
        return new ChatEntry(ts, MessageType.PUBLIC_CHAT, "Notch", content, "srv", "s1");
    }

    @Test
    void failedDeliveryKeepsEveryMessage() {
        ReportBuffer buffer = new ReportBuffer(100);
        buffer.add(entry(1, "a"));
        buffer.add(entry(2, "b"));

        List<ChatEntry> batch = buffer.snapshot();
        assertEquals(2, batch.size());

        // A failed request simply does not call confirmDelivered.
        assertEquals(2, buffer.size());
    }

    @Test
    void successfulDeliveryClearsOnlyTheConfirmedBatch() {
        ReportBuffer buffer = new ReportBuffer(100);
        buffer.add(entry(1, "a"));
        buffer.add(entry(2, "b"));

        List<ChatEntry> batch = buffer.snapshot();
        // A new message arrives while the request is in flight.
        buffer.add(entry(3, "c"));

        buffer.confirmDelivered(batch.size());

        List<ChatEntry> remaining = buffer.snapshot();
        assertEquals(1, remaining.size());
        assertEquals("c", remaining.get(0).content());
    }

    @Test
    void bufferIsBoundedAndDropsOldest() {
        ReportBuffer buffer = new ReportBuffer(3);
        for (int i = 0; i < 10; i++) buffer.add(entry(i, "m" + i));

        assertEquals(3, buffer.size());
        List<ChatEntry> snapshot = buffer.snapshot();
        assertEquals("m7", snapshot.get(0).content());
        assertEquals("m9", snapshot.get(2).content());
    }

    @Test
    void confirmMoreThanPresentIsSafe() {
        ReportBuffer buffer = new ReportBuffer(10);
        buffer.add(entry(1, "a"));
        buffer.confirmDelivered(5);
        assertTrue(buffer.isEmpty());
    }

    @Test
    void nullEntriesAreIgnored() {
        ReportBuffer buffer = new ReportBuffer(10);
        buffer.add(null);
        assertTrue(buffer.isEmpty());
    }
}
