package com.logvex.addon.schematicdropper.core;

/**
 * Handle for one in-flight drop. The engine polls it each tick until it reports a terminal status.
 * Confirmation is derived from the server-updated inventory, never from the fact that a packet was
 * sent.
 */
public interface PendingDrop {
    enum Status {
        PENDING,
        CONFIRMED,
        REJECTED,
        TIMEOUT
    }

    Status poll();

    /** Items that actually left the inventory. Only meaningful once {@link #poll()} returned CONFIRMED. */
    int confirmedOut();

    /** Reason for a {@code REJECTED} or {@code TIMEOUT} result. */
    FailureType failure();
}
