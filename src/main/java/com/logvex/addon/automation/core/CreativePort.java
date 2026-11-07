package com.logvex.addon.automation.core;

/**
 * The creative inventory operations the automation modules may perform.
 *
 * <p>Every method sends a real, server-synchronised interaction and never fabricates a result: a
 * give is only reported confirmed once the server has acknowledged it, and a game-mode change is
 * only reported accepted once the client's game mode actually changed. Implementations must be safe
 * to call from the client tick and must not block.
 *
 * <p>The port exists so the modules that use it stay unit-testable with a fake, exactly like the
 * rest of the addon's Minecraft-free cores.
 */
public interface CreativePort {
    /** One creative give: create {@code amount} of {@code itemId} in player-inventory {@code slot}. */
    record Give(String itemId, int slot, int amount, int stackLimit) {
        public boolean valid() {
            return itemId != null && !itemId.isBlank() && slot >= 0 && amount > 0
                && stackLimit > 0 && amount <= stackLimit;
        }
    }

    /** The outcome of a give attempt. */
    enum GiveStatus {
        /** Sent, awaiting the server's acknowledgement. */
        PENDING,
        /** The server acknowledged the give and the slot really holds the item. */
        CONFIRMED,
        /** The server refused, or the local state cannot support the give. */
        REJECTED
    }

    /** Whether creative inventory access is available right now. */
    boolean creativeAvailable();

    /** Whether the client is currently in creative mode. */
    boolean inCreative();

    /**
     * Sends one give. Returns {@link GiveStatus#PENDING} when the acknowledgement is asynchronous;
     * the caller must then call {@link #pollGive()} until it reports a terminal status.
     */
    GiveStatus give(Give item);

    /** The status of the last give, or {@link GiveStatus#REJECTED} when none was started. */
    GiveStatus pollGive();

    /** A short reason for the last rejected give, or {@code null}. */
    String lastGiveFailure();

    /** Cancels any in-flight give. */
    void cancelGive();

    /** Resets all transient state, e.g. on a world change. */
    void reset();
}
