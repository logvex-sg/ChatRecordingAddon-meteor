package com.logvex.addon.schematicdropper.core;

/**
 * Sends a creative inventory give and lets the engine poll the server's acknowledgement.
 *
 * <p>Implementations must not mutate the inventory locally beyond what vanilla's own prediction
 * does, and must never fabricate a confirmation.
 */
public interface AcquisitionAction {
    /** Begins the give described by {@code plan}. Must return immediately. */
    PendingGive begin(GivePlan plan);

    /** Cancels any in-flight give, e.g. when the run is stopped. */
    void cancelPending();
}
