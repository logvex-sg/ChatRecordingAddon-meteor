package com.logvex.addon.schematicdropper.core;

/** How the run behaves when a required material is temporarily unavailable. */
public enum RecoveryMode {
    /** Never skip the current material; wait for it and resume its unfinished quantity. */
    STRICT_ORDER,
    /** Temporarily block unavailable materials, continue with the rest, revisit later. */
    BEST_EFFORT;

    @Override
    public String toString() {
        return switch (this) {
            case STRICT_ORDER -> "Strict Order";
            case BEST_EFFORT -> "Best Effort";
        };
    }
}
