package com.logvex.addon.schematicdropper.core;

/**
 * Immutable placement engine configuration, rebuilt from the module's settings each tick so a
 * change takes effect without restarting the run.
 */
public record PlacementConfig(
    int operationsPerTick,
    int operationDelayMs,
    int retryLimit,
    int retryBackoffMs,
    int verifyTimeoutMs,
    int recheckIntervalMs,
    double maxRange,
    ConflictPolicy conflictPolicy,
    boolean verifyPlacements,
    boolean adaptiveThrottle,
    int minOperationsPerTick
) {
    public static PlacementConfig defaults() {
        return new PlacementConfig(
            1, 120, 3, 250, 1500, 1000, 5.0,
            ConflictPolicy.SKIP, true, true, 1
        );
    }
}
