package com.logvex.addon.schematicdropper.core;

/**
 * Handle for one in-flight creative give. The engine polls it once per tick until it reports a
 * terminal status.
 *
 * <p>Confirmation must come from the server (an inventory correction that names the target slot),
 * never from the fact that a packet was sent: a client-only prediction proves nothing.
 */
public interface PendingGive {
    enum Status {
        PENDING,
        CONFIRMED,
        REJECTED,
        TIMEOUT
    }

    Status poll();

    /** Items the server actually placed. Only meaningful once {@link #poll()} returned CONFIRMED. */
    int confirmedIn();

    /** Reason for a {@code REJECTED} or {@code TIMEOUT} result. */
    FailureType failure();
}
