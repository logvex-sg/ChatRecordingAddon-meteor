package com.logvex.addon.schematicdropper.core;

/** What a single confirmed transfer should do. */
public enum DropAction {
    /** Throw exactly one item from the stack. */
    DROP_ONE,
    /** Throw the whole stack in one action. */
    DROP_STACK;

    @Override
    public String toString() {
        return switch (this) {
            case DROP_ONE -> "Drop One";
            case DROP_STACK -> "Drop Full Stack";
        };
    }
}
