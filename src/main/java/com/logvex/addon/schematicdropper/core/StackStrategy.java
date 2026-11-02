package com.logvex.addon.schematicdropper.core;

/** How to choose between multiple inventory stacks of the same material. */
public enum StackStrategy {
    LARGEST_FIRST,
    SMALLEST_FIRST,
    HOTBAR_FIRST;

    @Override
    public String toString() {
        return switch (this) {
            case LARGEST_FIRST -> "Largest First";
            case SMALLEST_FIRST -> "Smallest First";
            case HOTBAR_FIRST -> "Hotbar First";
        };
    }
}
