package com.logvex.addon.schematicdropper.core;

/**
 * Immutable acquisition configuration, rebuilt from the module's settings on every tick so a
 * setting change takes effect immediately.
 */
public record AcquisitionConfig(
    boolean enabled,
    boolean autoDrop,
    int operationDelayMs,
    int retryLimit,
    int retryBackoffMs,
    int syncTimeoutMs,
    int scanIntervalMs
) {
    public static AcquisitionConfig defaults() {
        return new AcquisitionConfig(true, true, 80, 3, 200, 1500, 500);
    }
}
