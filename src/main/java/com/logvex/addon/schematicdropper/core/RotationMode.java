package com.logvex.addon.schematicdropper.core;

/** How the head should be moved toward the intended drop direction. */
public enum RotationMode {
    INSTANT,
    SMOOTH;

    @Override
    public String toString() {
        return switch (this) {
            case INSTANT -> "Instant";
            case SMOOTH -> "Smooth";
        };
    }
}
