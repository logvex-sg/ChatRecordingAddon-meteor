package com.logvex.addon.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared formatter is what stops the addon growing five spellings of the same number, so its
 * output is pinned here rather than being allowed to drift from screen to screen.
 */
class MessagesTest {

    @Test
    @DisplayName("durations zero-pad every field below the largest")
    void durationPadding() {
        assertEquals("0.4s", Messages.duration(400));
        assertEquals("7s", Messages.duration(7_000));
        assertEquals("59s", Messages.duration(59_999));
        assertEquals("1m 00s", Messages.duration(60_000));
        assertEquals("2m 05s", Messages.duration(125_000));
        assertEquals("59m 59s", Messages.duration(3_599_000));
        assertEquals("1h 00m 00s", Messages.duration(3_600_000));
        assertEquals("2h 03m 04s", Messages.duration(7_384_000));
    }

    @Test
    @DisplayName("a negative duration clamps to zero instead of printing a minus sign")
    void durationClampsNegative() {
        assertEquals("0.0s", Messages.duration(-500));
    }

    @Test
    @DisplayName("byte sizes climb units and keep one decimal")
    void byteSizes() {
        assertEquals("512 B", Messages.bytes(512));
        assertEquals("1.0 KB", Messages.bytes(1024));
        assertEquals("1.5 KB", Messages.bytes(1536));
        assertEquals("1.0 MB", Messages.bytes(1024 * 1024));
        assertEquals("2.5 GB", Messages.bytes((long) (2.5 * 1024 * 1024 * 1024)));
    }

    @Test
    @DisplayName("ReportFormatting agrees with Messages so screens cannot disagree")
    void reportFormattingDelegates() {
        long[] samples = {0, 512, 1536, 1024L * 1024, 7_384_000L, 125_000L};
        for (long sample : samples) {
            assertEquals(Messages.duration(sample),
                com.logvex.addon.chatlog.core.ReportFormatting.duration(sample),
                "duration disagreed for " + sample);
        }
        long[] sizes = {0, 512, 1536, 1024L * 1024, 3L * 1024 * 1024 * 1024};
        for (long size : sizes) {
            assertEquals(Messages.bytes(size),
                com.logvex.addon.chatlog.core.ReportFormatting.bytes(size),
                "byte size disagreed for " + size);
        }
    }

    @Test
    @DisplayName("counts are grouped and pluralise")
    void countsAndPlurals() {
        assertEquals("12,861", Messages.number(12_861));
        assertEquals("1 message", Messages.plural(1, "message"));
        assertEquals("2 messages", Messages.plural(2, "message"));
        assertEquals("0 messages", Messages.plural(0, "message"));
    }

    @Test
    @DisplayName("namespaced ids become readable labels")
    void ids() {
        assertEquals("oak_planks", Messages.idPath("minecraft:oak_planks"));
        assertEquals("Oak Planks", Messages.prettyId("minecraft:oak_planks"));
        assertEquals("Oak Planks", Messages.prettyId("oak_planks"));
    }

    @Test
    @DisplayName("truncation never exceeds the width it was given")
    void truncation() {
        assertEquals("abcdef", Messages.truncate("abcdef", 6));
        assertEquals("abcde\u2026", Messages.truncate("abcdefg", 6));
        assertEquals("", Messages.truncate("abcdef", 0));
    }
}
