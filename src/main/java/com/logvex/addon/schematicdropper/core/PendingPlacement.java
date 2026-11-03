package com.logvex.addon.schematicdropper.core;

/**
 * Handle for one in-flight placement.
 *
 * <p>Confirmation is derived from the world, never from the fact that an interaction packet was
 * sent: {@link #poll()} may only return {@link Status#CONFIRMED} once the block at the target
 * position actually matches. The engine re-reads the world itself as a second check.
 */
public interface PendingPlacement {
    enum Status {
        PENDING,
        CONFIRMED,
        REJECTED,
        TIMEOUT
    }

    Status poll();

    /** Reason for a {@link Status#REJECTED} or {@link Status#TIMEOUT} result. */
    PlacementFailure failure();
}
