package com.logvex.addon.schematicdropper.core;

/**
 * Why a placement failed, in terms the user can act on.
 *
 * <p>{@link #recoverable()} decides whether the engine retries or gives up on that block. A
 * non-recoverable failure is reported once and the block is marked failed rather than retried
 * forever.
 */
public enum PlacementFailure {
    /** The block is already correct; nothing to do. */
    NONE(true),
    /** The required item is not in the inventory. */
    NO_MATERIAL(true),
    /** The inventory is full and the held item cannot be selected. */
    INVENTORY_FULL(true),
    /** No solid neighbour to click against. */
    NO_SUPPORT(false),
    /** The block that should support this one is itself missing. */
    MISSING_SUPPORT(false),
    /** The chunk is not loaded. */
    CHUNK_UNLOADED(true),
    /** The player is not in range of the position. */
    OUT_OF_RANGE(true),
    /** The server rejected or never acknowledged the interaction. */
    SERVER_REJECTED(true),
    /** The world still does not match after the interaction. */
    VERIFY_FAILED(true),
    /** Another player changed the position while it was being built. */
    WORLD_CHANGED(true),
    /** The position cannot legally hold this block (e.g. a door needs the cell above). */
    INVALID_PLACEMENT(false),
    /** A generic, unclassified failure. */
    UNKNOWN(false);

    private final boolean recoverable;

    PlacementFailure(boolean recoverable) {
        this.recoverable = recoverable;
    }

    /** Recoverable failures are retried; the others are reported and the block is given up on. */
    public boolean recoverable() {
        return recoverable;
    }

    /** Maps a low-level failure type onto a placement failure. */
    public static PlacementFailure from(FailureType type) {
        if (type == null) return UNKNOWN;
        return switch (type) {
            case SCREEN_INVALID, SLOT_INVALID, INVENTORY_DESYNC -> INVENTORY_FULL;
            case SERVER_TIMEOUT -> SERVER_REJECTED;
            case SERVER_REJECTION -> SERVER_REJECTED;
            case INVENTORY_CHANGED -> WORLD_CHANGED;
            case TARGET_INVALID -> NO_SUPPORT;
            case MATERIAL_UNAVAILABLE -> NO_MATERIAL;
            case INVENTORY_FULL -> INVENTORY_FULL;
            case NOT_READY -> CHUNK_UNLOADED;
            default -> UNKNOWN;
        };
    }

    @Override
    public String toString() {
        return switch (this) {
            case NONE -> "No failure";
            case NO_MATERIAL -> "Missing material";
            case INVENTORY_FULL -> "Inventory full";
            case NO_SUPPORT -> "No support block";
            case MISSING_SUPPORT -> "Support not built yet";
            case CHUNK_UNLOADED -> "Chunk not loaded";
            case OUT_OF_RANGE -> "Out of range";
            case SERVER_REJECTED -> "Server rejected the placement";
            case VERIFY_FAILED -> "World did not change";
            case WORLD_CHANGED -> "World changed";
            case INVALID_PLACEMENT -> "Invalid placement";
            case UNKNOWN -> "Unknown error";
        };
    }
}
