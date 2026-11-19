package com.logvex.addon.common;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stops a service's writer without losing the flush queued just before it.
 *
 * <p>The services write on single-thread executors whose threads are daemons. A daemon thread does
 * not keep the JVM alive, so the old pattern — queue a save, then call {@code shutdown()} — could
 * return while the save was still queued, let the JVM exit, and silently lose the write. This
 * drains the queue first, then stops the executor.
 *
 * <p>It also never throws. It runs from a JVM shutdown hook, where an exception aborts the rest of
 * the hook: one service failing would stop every later service from flushing.
 */
public final class ShutdownFlush {
    /** Long enough for a local file write, short enough not to hang a closing client. */
    private static final long TIMEOUT_SECONDS = 5;

    private ShutdownFlush() {
    }

    /**
     * Waits for queued work to finish, then stops {@code executor}.
     *
     * @param executor the service's writer, or null when it was never created
     */
    public static void await(ExecutorService executor) {
        if (executor == null) return;
        try {
            executor.shutdown();
            if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // A stuck write must not hold the client open; drop it and let the JVM exit.
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Nothing useful can be done this late, and throwing here would abort the shutdown hook.
        }
    }
}
