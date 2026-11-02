package com.logvex.addon.schematicdropper.core;

/** Where the drop should be aimed. */
public enum TargetMode {
    PLAYER_POSITION,
    LOOKED_AT_ENTITY;

    @Override
    public String toString() {
        return switch (this) {
            case PLAYER_POSITION -> "Player Position";
            case LOOKED_AT_ENTITY -> "Looked-at Entity";
        };
    }
}
