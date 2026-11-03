package com.logvex.addon.schematicdropper.core;

import java.util.Map;

/**
 * Bridges the acquisition workflow to the existing ordered dropping workflow.
 *
 * <p>Creative can only place as many items as the inventory holds, so a requirement larger than
 * the free capacity is satisfied in batches: acquire what fits, drain it into the world through
 * the drop engine, then continue. A no-op delegate (see {@link #none()}) keeps the acquisition
 * workflow single-batch and is what the unit tests use.
 *
 * <p>The engine accounts for drained items separately from what is still in the inventory, so a
 * drain can never make it re-acquire material it has already produced.
 */
public interface DrainDelegate {
    /**
     * Begins dropping the held materials through the existing drop workflow.
     *
     * @return {@code false} when there is nothing the drop workflow could remove, so the engine
     *         can wait for space instead of starting a pointless drain
     */
    boolean beginDrain();

    /** True while a drain is still in progress. */
    boolean draining();

    /** Stops a drain early. */
    void abortDrain();

    /**
     * Per-material quantities the drop engine confirmed during the last completed drain. Only
     * meaningful once {@link #draining()} has returned {@code false} after a drain.
     */
    Map<String, Integer> lastDrain();

    /** A delegate that never drains: the engine waits for space instead. */
    static DrainDelegate none() {
        return new DrainDelegate() {
            @Override
            public boolean beginDrain() {
                return false;
            }

            @Override
            public boolean draining() {
                return false;
            }

            @Override
            public void abortDrain() {
            }

            @Override
            public Map<String, Integer> lastDrain() {
                return Map.of();
            }
        };
    }
}
