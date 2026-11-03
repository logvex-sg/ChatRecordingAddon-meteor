package com.logvex.addon.schematicdropper.core;

import java.util.List;

/**
 * Immutable view of the acquisition workflow, safe to read from the GUI and HUD.
 */
public record AcquisitionSnapshot(
    AcquisitionState state,
    String currentMaterial,
    int currentSlot,
    int currentAmount,
    int materialsTotal,
    int materialsComplete,
    long totalRequired,
    long totalAcquired,
    long totalRemaining,
    int failedOperations,
    double percent,
    List<MaterialAcquisition> materials
) {
    public static AcquisitionSnapshot idle() {
        return new AcquisitionSnapshot(AcquisitionState.IDLE, null, -1, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    public MaterialAcquisition current() {
        if (currentMaterial == null) return null;
        for (MaterialAcquisition m : materials) {
            if (m.itemId().equals(currentMaterial)) return m;
        }
        return null;
    }
}
