package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.DuplicateDetector;
import com.logvex.addon.chatlog.core.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateDetectorTest {

    private static ChatEntry entry(long ts, String sender, String content) {
        return new ChatEntry(ts, MessageType.PUBLIC_CHAT, sender, content, "s", "s1");
    }

    @Test
    void identicalMessagesWithinWindowAreSuppressed() {
        DuplicateDetector detector = new DuplicateDetector(500);
        assertTrue(detector.accept(entry(1000, "Notch", "hello")));
        assertFalse(detector.accept(entry(1100, "Notch", "hello")));
    }

    @Test
    void identicalMessagesOutsideWindowAreKept() {
        DuplicateDetector detector = new DuplicateDetector(500);
        assertTrue(detector.accept(entry(1000, "Notch", "hello")));
        assertTrue(detector.accept(entry(2000, "Notch", "hello")));
    }

    @Test
    void differentSenderIsNotADuplicate() {
        DuplicateDetector detector = new DuplicateDetector(500);
        assertTrue(detector.accept(entry(1000, "Notch", "hello")));
        assertTrue(detector.accept(entry(1050, "Alex", "hello")));
    }

    @Test
    void differentContentIsNotADuplicate() {
        DuplicateDetector detector = new DuplicateDetector(500);
        assertTrue(detector.accept(entry(1000, "Notch", "hello")));
        assertTrue(detector.accept(entry(1050, "Notch", "world")));
    }

    @Test
    void zeroWindowOnlySuppressesExactTimestampDuplicates() {
        DuplicateDetector detector = new DuplicateDetector(0);
        assertTrue(detector.accept(entry(1000, "Notch", "hello")));
        assertTrue(detector.accept(entry(1001, "Notch", "hello")));
    }

    @Test
    void resetClearsHistory() {
        DuplicateDetector detector = new DuplicateDetector(1000);
        assertTrue(detector.accept(entry(1000, "Notch", "hello")));
        detector.reset();
        assertTrue(detector.accept(entry(1000, "Notch", "hello")));
    }

    @Test
    void interleavedDuplicatesAreHandled() {
        DuplicateDetector detector = new DuplicateDetector(1000);
        assertTrue(detector.accept(entry(1000, "Notch", "a")));
        assertTrue(detector.accept(entry(1001, "Notch", "b")));
        assertFalse(detector.accept(entry(1002, "Notch", "a")));
        assertFalse(detector.accept(entry(1003, "Notch", "b")));
        assertTrue(detector.accept(entry(1004, "Notch", "c")));
    }
}
