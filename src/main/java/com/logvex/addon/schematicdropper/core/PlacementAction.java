package com.logvex.addon.schematicdropper.core;

/**
 * Performs one real block placement and lets the engine poll for the server's acknowledgement.
 *
 * <p>Implementations must not modify the world or the inventory locally: they send the vanilla
 * interaction (select the item, aim, use it on a face) and let the server answer. The engine then
 * re-reads the world before it believes the placement happened.
 */
public interface PlacementAction {
    /**
     * Begins a placement. Must return immediately; the caller polls the handle once per tick.
     *
     * @return a handle, or {@code null} when the placement could not even be started
     */
    PendingPlacement begin(PlacementRequest request);

    /** Cancels any in-flight placement, e.g. when the run is stopped. */
    void cancelPending();
}
