package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatTimestampFormat;
import com.logvex.addon.chatlog.core.RepeatGuard;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTimestampsAndRepeatTest {

    // ------------------------------------------------------------------ ChatTimestampFormat

    /** 2026-01-02T14:05:09Z in a fixed zone, so the assertions do not depend on the test machine. */
    private static final long SAMPLE = 1767362709000L;
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void clockStyleIsHoursAndMinutes() {
        assertEquals("14:05 ", ChatTimestampFormat.prefix(SAMPLE, ChatTimestampFormat.Style.CLOCK, UTC));
    }

    @Test
    void secondsStyleIncludesSeconds() {
        assertEquals("14:05:09 ", ChatTimestampFormat.prefix(SAMPLE, ChatTimestampFormat.Style.CLOCK_SECONDS, UTC));
    }

    @Test
    void twelveHourStyleUsesMeridiem() {
        assertEquals("2:05 PM ", ChatTimestampFormat.prefix(SAMPLE, ChatTimestampFormat.Style.CLOCK_12H, UTC));
    }

    @Test
    void prefixAlwaysEndsInOneSpace() {
        for (ChatTimestampFormat.Style style : ChatTimestampFormat.Style.values()) {
            String prefix = ChatTimestampFormat.prefix(SAMPLE, style, UTC);
            assertTrue(prefix.endsWith(" "), style + " should end in a space");
            assertFalse(prefix.endsWith("  "), style + " should not end in two spaces");
        }
    }

    @Test
    void nullStyleFallsBackToClock() {
        assertEquals(ChatTimestampFormat.prefix(SAMPLE, ChatTimestampFormat.Style.CLOCK, UTC),
            ChatTimestampFormat.prefix(SAMPLE, null, UTC));
    }

    @Test
    void applyJoinsPrefixAndLine() {
        assertEquals("14:05 hello", ChatTimestampFormat.apply("14:05 ", "hello"));
    }

    @Test
    void applyLeavesBlankLineAlone() {
        assertEquals("", ChatTimestampFormat.apply("14:05 ", ""));
        assertEquals("   ", ChatTimestampFormat.apply("14:05 ", "   "));
    }

    @Test
    void applyWithEmptyPrefixReturnsLineUnchanged() {
        assertEquals("hello", ChatTimestampFormat.apply("", "hello"));
        assertEquals("hello", ChatTimestampFormat.apply(null, "hello"));
    }

    @Test
    void applyHandlesNullLine() {
        assertEquals("", ChatTimestampFormat.apply("14:05 ", null));
    }

    // ------------------------------------------------------------------ RepeatGuard

    @Test
    void firstLineIsAlwaysAccepted() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept("hi", 1000, 500));
        assertEquals(1, guard.run());
    }

    @Test
    void identicalLineInsideWindowIsRejected() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept("hi", 1000, 500));
        assertFalse(guard.accept("hi", 1200, 500));
        assertEquals(2, guard.run());
    }

    @Test
    void identicalLineOutsideWindowIsAccepted() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept("hi", 1000, 500));
        assertTrue(guard.accept("hi", 1600, 500));
        assertEquals(1, guard.run());
    }

    @Test
    void differentLineResetsTheRun() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept("a", 1000, 10_000));
        assertFalse(guard.accept("a", 1100, 10_000));
        assertTrue(guard.accept("b", 1200, 10_000));
        assertEquals(1, guard.run());
        assertTrue(guard.accept("a", 1300, 10_000), "a after b is a new statement");
    }

    @Test
    void longSpamBurstKeepsCollapsing() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept("spam", 0, 1000));
        // Each repeat arrives inside the window of the previous one, so the burst stays collapsed
        // even though the first line is now far behind.
        for (int i = 1; i <= 20; i++) {
            assertFalse(guard.accept("spam", i * 900L, 1000));
        }
        assertEquals(21, guard.run());
    }

    @Test
    void zeroWindowStillCollapsesSimultaneousRepeats() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept("x", 500, 0));
        assertFalse(guard.accept("x", 500, 0));
        assertTrue(guard.accept("x", 501, 0));
    }

    @Test
    void blankKeyIsNeverTreatedAsRepeat() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept("", 1000, 10_000));
        assertTrue(guard.accept("", 1001, 10_000));
    }

    @Test
    void nullKeyIsTreatedAsBlank() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept(null, 1000, 10_000));
        assertTrue(guard.accept(null, 1001, 10_000));
    }

    @Test
    void negativeWindowIsClampedToZero() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept("x", 1000, -500));
        assertTrue(guard.accept("x", 1001, -500));
    }

    @Test
    void resetForgetsTheRun() {
        RepeatGuard guard = new RepeatGuard();
        assertTrue(guard.accept("x", 1000, 10_000));
        guard.reset();
        assertEquals(0, guard.run());
        assertEquals("", guard.current());
        assertTrue(guard.accept("x", 1001, 10_000));
    }

    @Test
    void currentReturnsTheTrackedKey() {
        RepeatGuard guard = new RepeatGuard();
        guard.accept("abc", 1000, 10_000);
        assertEquals("abc", guard.current());
    }
}
