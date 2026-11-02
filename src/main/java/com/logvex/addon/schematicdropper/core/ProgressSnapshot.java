package com.logvex.addon.schematicdropper.core;

import java.util.List;

/**
 * Immutable view of the whole run. Produced by {@link ProgressTracker#snapshot()}; the GUI and
 * HUD read only this so they can never observe a half-updated tracker.
 */
public record ProgressSnapshot(
    State state,
    long totalRequired,
    long confirmedDropped,
    long remaining,
    int completedTypes,
    int totalTypes,
    int missingTypes,
    String currentMaterial,
    int currentStackSlot,
    int currentStackCount,
    int failedOperations,
    double percent,
    List<MaterialProgress> materials
) {
    public static ProgressSnapshot idle() {
        return new ProgressSnapshot(State.IDLE, 0, 0, 0, 0, 0, 0, null, -1, 0, 0, 0, List.of());
    }
}
