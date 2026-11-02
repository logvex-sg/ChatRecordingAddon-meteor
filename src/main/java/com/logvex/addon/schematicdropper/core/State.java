package com.logvex.addon.schematicdropper.core;

/**
 * Explicit execution states for the dropper. Transitions are driven only by
 * {@link DropEngine}; no other component may change the state directly.
 */
public enum State {
    IDLE,
    SCANNING,
    SELECTING,
    ROTATING,
    DROPPING,
    WAITING_FOR_CONFIRMATION,
    COMPLETED,
    PAUSED,
    WAITING_FOR_INVENTORY,
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
            case SELECTING -> "Selecting";
            case ROTATING -> "Rotating";
            case DROPPING -> "Dropping";
            case WAITING_FOR_CONFIRMATION -> "Waiting for Confirmation";
            case COMPLETED -> "Completed";
            case PAUSED -> "Paused";
            case WAITING_FOR_INVENTORY -> "Waiting for Inventory";
            case ERROR -> "Error";
        };
    }
}
