package com.logvex.addon.schematicdropper.core;

/**
 * Immutable engine configuration. Built from the module's settings on every tick so that
 * changing a setting takes effect immediately without restarting the run.
 */
public record DropConfig(
    boolean autoContinue,
    int operationDelayMs,
    int retryLimit,
    int retryBackoffMs,
    int rescanIntervalMs,
    boolean pauseOnMissing,
    boolean rotationEnabled,
    RotationMode rotationMode,
    float rotationSpeed,
    boolean restoreRotation,
    TargetMode targetMode,
    int targetUpdateMs,
    OrderingMode orderingMode,
    RecoveryMode recoveryMode,
    InventoryMode inventoryMode,
    DropAction dropAction,
    StackStrategy stackStrategy,
    int syncTimeoutMs,
    boolean validateTarget
) {
    public static DropConfig defaults() {
        return new DropConfig(
            true, 120, 3, 250, 1000, true,
            true, RotationMode.SMOOTH, 30f, true,
            TargetMode.PLAYER_POSITION, 100,
            OrderingMode.SCHEMATIC_ORDER, RecoveryMode.STRICT_ORDER,
            InventoryMode.SILENT, DropAction.DROP_ONE, StackStrategy.LARGEST_FIRST, 1500,
            true
        );
    }
}
