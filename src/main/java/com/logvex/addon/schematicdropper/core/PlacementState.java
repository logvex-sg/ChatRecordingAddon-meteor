package com.logvex.addon.schematicdropper.core;

/**
 * The lifecycle of one planned block.
 *
 * <p>{@link #COMPLETED} is only ever assigned after the world was re-read and found to match; the
 * engine never trusts that an interaction packet placed a block.
 */
public enum PlacementState {
    /** Not yet examined. */
    PENDING,
    /** The world already matches the schematic here, so nothing must be placed. */
    ALREADY_CORRECT,
    /** Waiting for its dependencies (support, direction, ...) to be placed first. */
    BLOCKED,
    /** Ready to be placed now. */
    READY,
    /** Being placed right now. */
    PLACING,
    /** Placed and verified against the world. */
    COMPLETED,
    /** The world holds a different block here and the conflict policy says to leave it. */
    SKIPPED,
    /** Placement was attempted and failed; kept for the error viewer. */
    FAILED,
    /** A dependency could not be satisfied; reported instead of retried forever. */
    UNRESOLVED;

    public boolean isTerminal() {
        return this == ALREADY_CORRECT || this == COMPLETED || this == SKIPPED || this == UNRESOLVED;
    }

    public boolean isDone() {
        return isTerminal() || this == FAILED;
    }

    @Override
    public String toString() {
        return switch (this) {
            case PENDING -> "Pending";
            case ALREADY_CORRECT -> "Already Correct";
            case BLOCKED -> "Blocked";
            case READY -> "Ready";
            case PLACING -> "Placing";
            case COMPLETED -> "Completed";
            case SKIPPED -> "Skipped";
            case FAILED -> "Failed";
            case UNRESOLVED -> "Unresolved";
        };
    }
}
