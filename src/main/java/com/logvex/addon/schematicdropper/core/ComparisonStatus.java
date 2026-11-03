package com.logvex.addon.schematicdropper.core;

/**
 * What the world comparison found for one block, before any placement is attempted.
 *
 * <p>The four categories are the ones the user is shown: a block is either already right, missing,
 * wrong, or occupied by something that must not be silently replaced.
 */
public enum ComparisonStatus {
    /** The world already holds exactly the required state. */
    CORRECT,
    /** The world holds air (or a replaceable block) where a block is required. */
    MISSING,
    /** The world holds a different block of the same kind, e.g. a wrong stair direction. */
    INCORRECT,
    /** The world holds an unrelated block that the conflict policy protects. */
    CONFLICT,
    /** The chunk is not loaded, so the state cannot be trusted. */
    UNKNOWN;

    public boolean needsWork() {
        return this == MISSING || this == INCORRECT;
    }

    @Override
    public String toString() {
        return switch (this) {
            case CORRECT -> "Correct";
            case MISSING -> "Missing";
            case INCORRECT -> "Incorrect";
            case CONFLICT -> "Conflict";
            case UNKNOWN -> "Unknown";
        };
    }
}
