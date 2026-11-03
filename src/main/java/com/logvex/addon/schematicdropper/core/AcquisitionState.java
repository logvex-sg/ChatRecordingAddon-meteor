package com.logvex.addon.schematicdropper.core;

/** Explicit execution states for the automatic material acquisition workflow. */
public enum AcquisitionState {
    IDLE,
    SCANNING,
    ACQUIRING,
    VERIFYING,
    WAITING_FOR_SPACE,
    COMPLETED,
    PAUSED,
    ERROR;

    /** True while the workflow is actively working (not idle, finished, paused or errored). */
    public boolean isRunning() {
        return this != IDLE && this != COMPLETED && this != PAUSED && this != ERROR;
    }

    @Override
    public String toString() {
        return switch (this) {
            case IDLE -> "Idle";
            case SCANNING -> "Scanning";
            case ACQUIRING -> "Acquiring";
            case VERIFYING -> "Verifying";
            case WAITING_FOR_SPACE -> "Waiting for Space";
            case COMPLETED -> "Completed";
            case PAUSED -> "Paused";
            case ERROR -> "Error";
        };
    }
}
