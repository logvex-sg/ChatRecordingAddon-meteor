package com.logvex.addon.schematicdropper.core;

/** Classification of an operation failure, used to decide how (and whether) to recover. */
public enum FailureType {
    INVENTORY_CHANGED(true),
    INVENTORY_DESYNC(true),
    SCREEN_INVALID(true),
    SLOT_INVALID(true),
    SERVER_TIMEOUT(true),
    SERVER_REJECTION(true),
    TARGET_INVALID(true),
    MATERIAL_UNAVAILABLE(true),
    ROTATION_FAILED(true),
    /** Creative mode is not active on the client. Fatal for the acquisition workflow. */
    NOT_CREATIVE(false),
    /** The server refused creative inventory changes (no permission). Fatal. */
    NO_CREATIVE_PERMISSION(false),
    /** No room in the player inventory. Recoverable: the workflow waits for space. */
    INVENTORY_FULL(true),
    /** The material is not a known item id. Fatal. */
    UNKNOWN_ITEM(false),
    /** A quantity was zero, negative or otherwise unusable. Fatal. */
    INVALID_QUANTITY(false),
    /** The client is not in a state where a creative give can be sent. Recoverable. */
    NOT_READY(true),
    UNKNOWN_ERROR(false);

    private final boolean recoverable;

    FailureType(boolean recoverable) {
        this.recoverable = recoverable;
    }

    /** Recoverable failures never terminate the run; the engine waits and retries instead. */
    public boolean recoverable() {
        return recoverable;
    }

    @Override
    public String toString() {
        return switch (this) {
            case INVENTORY_CHANGED -> "Inventory Changed";
            case INVENTORY_DESYNC -> "Inventory Desync";
            case SCREEN_INVALID -> "Screen Invalid";
            case SLOT_INVALID -> "Slot Invalid";
            case SERVER_TIMEOUT -> "Server Timeout";
            case SERVER_REJECTION -> "Server Rejection";
            case TARGET_INVALID -> "Target Invalid";
            case MATERIAL_UNAVAILABLE -> "Material Unavailable";
            case ROTATION_FAILED -> "Rotation Failed";
            case NOT_CREATIVE -> "Not In Creative Mode";
            case NO_CREATIVE_PERMISSION -> "No Creative Permission";
            case INVENTORY_FULL -> "Inventory Full";
            case UNKNOWN_ITEM -> "Unknown Item";
            case INVALID_QUANTITY -> "Invalid Quantity";
            case NOT_READY -> "Not Ready";
            case UNKNOWN_ERROR -> "Unknown Error";
        };
    }
}
