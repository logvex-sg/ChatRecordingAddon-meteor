package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.SendPacer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A block such as {@code !cmds} is a dozen lines. Sending them in one tick is what a server's flood
 * filter kicks for, so these tests pin the pacing that replaced the burst.
 */
class SendPacerTest {

    private static SendPacer.Line line(String text) {
        return new SendPacer.Line("", "", text);
    }

    @Test
    @DisplayName("the first line is ready immediately")
    void firstLineIsImmediate() {
        SendPacer pacer = new SendPacer(1_000L, 3, 5_000L, 40);
        pacer.enqueue(line("a"));
        assertEquals(1, pacer.ready(0L).size());
        assertTrue(pacer.isEmpty());
    }

    @Test
    @DisplayName("the second line waits for the gap")
    void secondLineWaitsForGap() {
        SendPacer pacer = new SendPacer(1_000L, 10, 5_000L, 40);
        pacer.enqueue(List.of(line("a"), line("b")));

        assertEquals(1, pacer.ready(0L).size());
        assertTrue(pacer.ready(500L).isEmpty(), "half the gap must not release a line");
        assertEquals(1, pacer.ready(1_000L).size());
        assertTrue(pacer.isEmpty());
    }

    @Test
    @DisplayName("an eleven-line block cannot leave in one tick")
    void blockIsNotABurst() {
        SendPacer pacer = new SendPacer(1_000L, 3, 5_000L, 40);
        java.util.List<SendPacer.Line> block = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) block.add(line("line " + i));
        pacer.enqueue(block);

        // This is the bug: 11 sends in one tick. The pacer must release exactly one.
        assertEquals(1, pacer.ready(0L).size());
        assertEquals(10, pacer.pendingCount());
    }

    @Test
    @DisplayName("the burst limit caps lines inside one window")
    void burstLimitApplies() {
        SendPacer pacer = new SendPacer(0L, 2, 5_000L, 40);
        pacer.enqueue(List.of(line("a"), line("b"), line("c")));

        assertEquals(1, pacer.ready(0L).size());
        assertEquals(1, pacer.ready(10L).size());
        assertTrue(pacer.ready(20L).isEmpty(), "a third line inside the window must wait");
        assertEquals(1, pacer.ready(5_000L).size());
    }

    @Test
    @DisplayName("waitMs reports the remaining gap")
    void waitReportsRemainingGap() {
        SendPacer pacer = new SendPacer(1_000L, 10, 5_000L, 40);
        pacer.enqueue(List.of(line("a"), line("b")));
        pacer.ready(0L);

        assertEquals(1_000L, pacer.waitMs(0L));
        assertEquals(400L, pacer.waitMs(600L));
        assertEquals(0L, pacer.waitMs(1_500L));
    }

    @Test
    @DisplayName("a full queue drops the oldest line and counts it")
    void overflowDropsOldest() {
        SendPacer pacer = new SendPacer(1_000L, 10, 5_000L, 2);
        pacer.enqueue(List.of(line("a"), line("b"), line("c")));

        assertEquals(2, pacer.pendingCount());
        assertEquals(1, pacer.dropped());
        assertEquals("b", pacer.ready(0L).get(0).text());
    }

    @Test
    @DisplayName("a whisper keeps its recipient and verb")
    void whisperKeepsRecipient() {
        SendPacer pacer = new SendPacer(1_000L, 10, 5_000L, 40);
        pacer.enqueue(new SendPacer.Line("Alex", "msg", "hello"));

        SendPacer.Line sent = pacer.ready(0L).get(0);
        assertTrue(sent.isWhisper());
        assertEquals("Alex", sent.recipient());
        assertEquals("msg", sent.verb());
    }

    @Test
    @DisplayName("a blank line is never queued")
    void blankLinesAreSkipped() {
        SendPacer pacer = new SendPacer(1_000L, 10, 5_000L, 40);
        assertFalse(pacer.enqueue(line("   ")));
        assertTrue(pacer.isEmpty());
    }

    @Test
    @DisplayName("immediate pacing sends without delay")
    void immediateNeverWaits() {
        SendPacer pacer = SendPacer.immediate();
        pacer.enqueue(List.of(line("a"), line("b"), line("c")));

        assertEquals(1, pacer.ready(0L).size());
        assertEquals(1, pacer.ready(0L).size());
        assertEquals(1, pacer.ready(0L).size());
        assertTrue(pacer.isEmpty());
    }

    @Test
    @DisplayName("clear empties the backlog and resets the gap")
    void clearResets() {
        SendPacer pacer = new SendPacer(1_000L, 10, 5_000L, 40);
        pacer.enqueue(List.of(line("a"), line("b")));
        pacer.ready(0L);

        List<SendPacer.Line> dropped = pacer.clear();
        assertEquals(1, dropped.size());
        assertTrue(pacer.isEmpty());

        pacer.enqueue(line("c"));
        assertEquals(1, pacer.ready(0L).size(), "the gap must not survive a clear");
    }

    @Test
    @DisplayName("a null recipient means public chat, not a whisper")
    void publicLineIsNotWhisper() {
        SendPacer.Line line = new SendPacer.Line(null, null, "hi");
        assertFalse(line.isWhisper());
        assertEquals("", line.recipient());
    }
}