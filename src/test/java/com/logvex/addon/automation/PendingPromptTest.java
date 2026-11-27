package com.logvex.addon.automation;

import com.logvex.addon.automation.core.PendingPrompt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the prompt-retention rule behind the reported "auto-login does nothing" bug.
 *
 * <p>The module used to consume a prompt on the very next tick whether or not it could act. A prompt
 * arriving before the player existed, or during the send cooldown, was therefore lost and never
 * retried. These tests hold the replacement behaviour: a prompt is read without being consumed, so
 * a caller that cannot send yet still finds it on the next tick.
 */
class PendingPromptTest {
    private static final long TTL = 1_000;

    @Test
    void aPromptSurvivesBeingPeekedSoTheNextTickCanStillSend() {
        PendingPrompt pending = new PendingPrompt(TTL);
        pending.offer("Please /login", 0);

        assertEquals("Please /login", pending.peek(0));
        // The old code would have nothing left here, which is exactly the bug.
        assertEquals("Please /login", pending.peek(50));
        assertTrue(pending.waiting(50));
    }

    @Test
    void clearingAfterAnAnswerStopsASecondSend() {
        PendingPrompt pending = new PendingPrompt(TTL);
        pending.offer("Please /login", 0);
        pending.peek(0);
        pending.clear();

        assertNull(pending.peek(10));
        assertFalse(pending.waiting(10));
    }

    @Test
    void aPromptOlderThanTheWindowIsDroppedRatherThanSentLate() {
        PendingPrompt pending = new PendingPrompt(TTL);
        pending.offer("Please /login", 0);

        assertNull(pending.peek(TTL + 1));
        // Reading an expired prompt clears it, so it cannot come back on a later tick.
        assertNull(pending.peek(TTL + 2));
    }

    @Test
    void theWindowBoundaryItselfIsStillAnswerable() {
        PendingPrompt pending = new PendingPrompt(TTL);
        pending.offer("Please /login", 0);

        assertEquals("Please /login", pending.peek(TTL));
    }

    @Test
    void theNewestPromptWinsBecauseOnlyTheLatestIsWorthAnswering() {
        PendingPrompt pending = new PendingPrompt(TTL);
        pending.offer("Please /login", 0);
        pending.offer("You must log in", 100);

        assertEquals("You must log in", pending.peek(100));
    }

    @Test
    void blankPromptsAreIgnoredRatherThanOccupyingTheSlot() {
        PendingPrompt pending = new PendingPrompt(TTL);
        pending.offer("   ", 0);

        assertFalse(pending.waiting(0));
        assertNull(pending.peek(0));
    }

    @Test
    void aNullPromptDoesNotThrow() {
        PendingPrompt pending = new PendingPrompt(TTL);
        pending.offer(null, 0);

        assertNull(pending.peek(0));
    }

    @Test
    void theDefaultWindowCoversTypingAPasswordAfterACooldown() {
        // The default has to outlast a send cooldown (a few seconds) plus the time to open the GUI
        // and type a value. This guards against the constant being lowered to something useless.
        assertTrue(PendingPrompt.DEFAULT_TTL_MS >= 20_000,
            "the retention window must outlast a cooldown and a password being typed");
    }
}
