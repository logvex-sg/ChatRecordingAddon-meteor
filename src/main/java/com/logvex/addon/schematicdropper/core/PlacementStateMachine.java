package com.logvex.addon.schematicdropper.core;

/**
 * Explicit execution states for the placement engine. Only the engine changes them.
 */
public enum PlacementStateMachine {
    IDLE,
    SCANNING,
    PLACING,
    VERIFYING,
    WAITING_FOR_SUPPORT,
    WAITING_FOR_MATERIAL,
    PAUSED,
    COMPLETED,
    ERROR;

    public boolean isRunning() {
        return this != IDLE && this != COMPLETED && this != PAUSED && this != ERROR;
    }

    public boolean isTerminal() {
        return this == COMPLETED;
    }

    @Override
    public String toString() {
        return switch (this) {
            case IDLE -> "Idle";
            case SCANNING -> "Scanning";
            case PLACING -> "Placing";
            case VERIFYING -> "Verifying";
            case WAITING_FOR_SUPPORT -> "Waiting for Support";
            case WAITING_FOR_MATERIAL -> "Waiting for Material";
            case PAUSED -> "Paused";
            case COMPLETED -> "Completed";
            case ERROR -> "Error";
        };
    }
}
