package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.mc.LogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Live-listener registration must be idempotent.
 *
 * <p>A module that is activated twice without an intervening deactivation would otherwise be
 * registered twice and called twice per recorded entry, so every highlight was announced twice.
 * Registering the same listener reference again is therefore a no-op.
 */
class LogServiceListenerTest {

    /**
     * {@link LogService#get()} is the process-wide instance the addon uses, so each test starts
     * recording and restores the flag afterwards; leaving it on would change what other tests see.
     */
    private boolean recordingWas;

    @BeforeEach
    void startRecording() {
        recordingWas = LogService.get().isRecording();
        LogService.get().setRecording(true);
    }

    @AfterEach
    void restoreRecording() {
        LogService.get().setRecording(recordingWas);
    }

    private static ChatEntry entry(String content) {
        return new ChatEntry(System.nanoTime(), MessageType.PUBLIC_CHAT, "logvex", content,
            "test.example.org", "s1");
    }

    @Test
    @DisplayName("registering the same listener twice delivers each entry once")
    void sameListenerRegisteredTwiceDeliversOnce() {
        LogService service = LogService.get();
        AtomicInteger calls = new AtomicInteger();
        LogService.LiveListener listener = e -> calls.incrementAndGet();

        service.addListener(listener);
        service.addListener(listener);
        try {
            service.record(entry("idempotent-listener-probe-" + System.nanoTime()));
            assertEquals(1, calls.get(), "a doubly registered listener must not be called twice");
        } finally {
            service.removeListener(listener);
        }
    }

    @Test
    @DisplayName("two different listeners both receive the entry")
    void distinctListenersBothReceive() {
        LogService service = LogService.get();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        LogService.LiveListener a = e -> first.incrementAndGet();
        LogService.LiveListener b = e -> second.incrementAndGet();

        service.addListener(a);
        service.addListener(b);
        try {
            service.record(entry("distinct-listener-probe-" + System.nanoTime()));
            assertEquals(1, first.get());
            assertEquals(1, second.get());
        } finally {
            service.removeListener(a);
            service.removeListener(b);
        }
    }

    @Test
    @DisplayName("a removed listener is not called again")
    void removedListenerIsSilent() {
        LogService service = LogService.get();
        AtomicInteger calls = new AtomicInteger();
        LogService.LiveListener listener = e -> calls.incrementAndGet();

        service.addListener(listener);
        service.removeListener(listener);
        service.record(entry("removed-listener-probe-" + System.nanoTime()));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("a throwing listener does not stop recording or other listeners")
    void throwingListenerIsIsolated() {
        LogService service = LogService.get();
        AtomicInteger healthy = new AtomicInteger();
        LogService.LiveListener bad = e -> {
            throw new IllegalStateException("deliberate");
        };
        LogService.LiveListener good = e -> healthy.incrementAndGet();

        service.addListener(bad);
        service.addListener(good);
        try {
            service.record(entry("throwing-listener-probe-" + System.nanoTime()));
            assertEquals(1, healthy.get(), "one bad listener must not silence the others");
            assertNotNull(service.lastError(), "the failure is recorded rather than swallowed");
        } finally {
            service.removeListener(bad);
            service.removeListener(good);
        }
    }
}
