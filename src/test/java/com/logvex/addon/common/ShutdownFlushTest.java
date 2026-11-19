package com.logvex.addon.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shutdown flush, which runs from a JVM shutdown hook.
 *
 * <p>Two things must hold there: a queued write finishes before the JVM exits, and nothing throws,
 * because an exception aborts the rest of the hook and silently skips the other services' saves.
 */
class ShutdownFlushTest {

    @Test
    @DisplayName("a queued write finishes before the helper returns")
    void waitsForQueuedWork() {
        ExecutorService io = Executors.newSingleThreadExecutor();
        AtomicBoolean written = new AtomicBoolean();

        io.execute(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            written.set(true);
        });

        ShutdownFlush.await(io);

        assertTrue(written.get(), "the helper returned before the queued write ran");
        assertTrue(io.isShutdown());
    }

    @Test
    @DisplayName("the helper stops the executor")
    void stopsTheExecutor() {
        ExecutorService io = Executors.newSingleThreadExecutor();
        ShutdownFlush.await(io);
        assertTrue(io.isShutdown());
    }

    @Test
    @DisplayName("a null executor is ignored rather than fatal")
    void nullIsSafe() {
        assertDoesNotThrow(() -> ShutdownFlush.await(null));
    }

    @Test
    @DisplayName("an already stopped executor does not throw")
    void alreadyShutdownIsSafe() {
        ExecutorService io = Executors.newSingleThreadExecutor();
        io.shutdown();
        assertDoesNotThrow(() -> ShutdownFlush.await(io));
    }
}
