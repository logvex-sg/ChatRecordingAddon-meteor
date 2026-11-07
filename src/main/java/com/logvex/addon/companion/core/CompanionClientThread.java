package com.logvex.addon.companion.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Marshals a call onto the Minecraft client thread and waits for its result.
 *
 * <p>The companion server runs on its own HTTP threads, but every operation that touches the game
 * (reading a snapshot, toggling a module, sending chat) must happen on the client thread. This is the
 * single seam where that hop is made, so the rule lives in one place and the tests can substitute a
 * direct executor.
 *
 * <p>The wait is bounded. A request handler blocks (it is an HTTP worker, not the game thread), but a
 * frozen or shutting-down client must not hang a worker forever, so a timeout returns a failure the
 * app can show instead.
 */
@FunctionalInterface
public interface CompanionClientThread {

    /**
     * Runs {@code task} on the client thread and returns its result.
     *
     * <p>Returns {@link Object} rather than a type parameter because a Java functional interface
     * cannot declare a generic method; callers use {@link #invoke} for the checked cast.
     */
    Object call(Callable<?> task, long timeoutMs);

    /** The typed form of {@link #call}. */
    @SuppressWarnings("unchecked")
    default <T> T invoke(Callable<T> task, long timeoutMs) {
        return (T) call(task, timeoutMs);
    }

    /** Raised when the client thread did not answer in time. */
    class CompanionTimeoutException extends RuntimeException {
        public CompanionTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * A client-thread executor backed by a scheduler, used by the Minecraft layer.
     *
     * @param scheduler submits a runnable for execution on the client thread
     */
    static CompanionClientThread of(java.util.function.Consumer<Runnable> scheduler) {
        return (task, timeoutMs) -> {
            FutureTask<Object> future = new FutureTask<>(() -> task.call());
            scheduler.accept(future);
            try {
                return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new CompanionTimeoutException("The game did not answer in time.");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                throw new IllegalStateException(cause == null ? e.getMessage() : cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompanionTimeoutException("The request was interrupted.");
            }
        };
    }

    /** A pass-through executor that runs the task inline; used by unit tests. */
    static CompanionClientThread direct() {
        return (task, timeoutMs) -> {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        };
    }
}
