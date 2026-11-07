package com.logvex.addon.schematicdropper.core;

/**
 * Per-material acquisition numbers. {@code held} and {@code needed} are measured against the
 * live inventory; {@code acquired} counts only server-confirmed gives; {@code dropped} counts the
 * items this material has already handed to the drop workflow.
 */
public record MaterialAcquisition(
    String itemId,
    String displayName,
    int required,
    int held,
    int needed,
    int acquired,
    int dropped,
    boolean complete
) {
    public double fraction() {
        return required <= 0 ? 0 : Math.min(1.0, (double) acquired / required);
    }

    /** How much of this material has been dropped, relative to the requirement. */
    public DropStatus dropStatus() {
        if (dropped <= 0) return DropStatus.NONE;
        if (required > 0 && dropped >= required) return DropStatus.DROPPED;
        return DropStatus.PARTIAL;
    }

    /** The state of a material's hand-off to the drop workflow. */
    public enum DropStatus {
        NONE,
        PARTIAL,
        DROPPED;

        @Override
        public String toString() {
            return switch (this) {
                case NONE -> "-";
                case PARTIAL -> "Partial";
                case DROPPED -> "Dropped";
            };
        }
    }
}
