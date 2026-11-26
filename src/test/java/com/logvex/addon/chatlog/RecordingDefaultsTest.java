package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.mc.LogStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The recorder must record out of the box: recording on, every type, storage attached. */
class RecordingDefaultsTest {

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        LogService.get().setRecording(true);
    }

    @Test
    void recordsWithoutAnyModuleBeingTouched() {
        LogService service = LogService.get();
        boolean accepted = service.record(new ChatEntry(System.currentTimeMillis(),
            MessageType.PUBLIC_CHAT, "Steve", "hello", service.serverName(), ""));

        assertTrue(accepted, "a fresh install must record chat, not silently drop it");
        assertFalse(service.recent(1).isEmpty(), "the entry must be readable back");
    }

    @Test
    void bufferedEntriesReachDiskOnFlush() {
        LogService service = LogService.get();
        LogStorageService storage = new LogStorageService(dir);
        service.attach(storage);

        service.record(new ChatEntry(1_700_000_000_000L, MessageType.PUBLIC_CHAT, "Steve",
            "survives a quit", "play.example.com", "s1"));

        // What the shutdown hook now does. Without it the entry sits in the store's buffer.
        storage.flushBlocking();

        assertFalse(storage.segments().isEmpty(), "a flushed entry must become a segment on disk");
        assertEquals(1, storage.segments().get(0).count());
    }
}
