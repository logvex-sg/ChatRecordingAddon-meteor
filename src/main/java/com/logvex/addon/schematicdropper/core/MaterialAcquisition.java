package com.logvex.addon.schematicdropper.core;

/**
 * Per-material acquisition numbers. {@code held} and {@code needed} are measured against the
 * live inventory; {@code acquired} counts only server-confirmed gives.
 */
public record MaterialAcquisition(
    String itemId,
    String displayName,
    int required,
    int held,
    int needed,
    int acquired,
    boolean complete
) {
    public double fraction() {
        return required <= 0 ? 0 : Math.min(1.0, (double) acquired / required);
    }
}
