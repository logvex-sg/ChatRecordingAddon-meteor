package com.logvex.addon.schematicdropper.core;

/** Issues a confirmed inventory drop and lets the engine poll for the server's acknowledgement. */
public interface ActionPort {
    /**
     * Makes the inventory ready for a click (opens the inventory screen in {@code OPEN_INVENTORY}
     * mode). Returns {@code false} while the caller should wait.
     */
    boolean prepare();

    /**
     * Begins the drop described by {@code plan}. Must return immediately; the caller polls the
     * handle once per tick. Implementations must not mutate the inventory locally.
     */
    PendingDrop begin(DropPlan plan);

    /** Cancels any in-flight transaction, e.g. when the run is stopped. */
    void cancelPending();
}
