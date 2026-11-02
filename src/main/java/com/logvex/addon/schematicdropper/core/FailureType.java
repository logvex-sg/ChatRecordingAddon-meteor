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
            case UNKNOWN_ERROR -> "Unknown Error";
        };
    }
}
