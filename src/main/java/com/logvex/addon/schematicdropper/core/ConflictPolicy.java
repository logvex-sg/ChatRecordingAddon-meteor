package com.logvex.addon.schematicdropper.core;

/**
 * What to do when the world holds a different block than the schematic expects.
 *
 * <p>No policy ever silently destroys a structure: {@link #SKIP} is the default and
 * {@link #REPLACE} only ever applies to blocks the world reports as replaceable.
 */
public enum ConflictPolicy {
    /** Leave the existing block alone and mark the position skipped. */
    SKIP,
    /** Report the conflict and pause the run so the user can decide. */
    PAUSE,
    /** Replace the block, but only when it is replaceable (air, grass, snow, ...). */
    REPLACE;

    @Override
    public String toString() {
        return switch (this) {
            case SKIP -> "Skip";
            case PAUSE -> "Pause";
            case REPLACE -> "Replace Replaceable";
        };
    }
}
