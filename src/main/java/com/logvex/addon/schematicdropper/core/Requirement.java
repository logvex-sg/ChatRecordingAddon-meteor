package com.logvex.addon.schematicdropper.core;

/**
 * A single schematic material requirement: one item and how many of it must be dropped.
 *
 * @param itemId      registry id of the item, e.g. {@code minecraft:cobblestone}
 * @param displayName human readable name used in the GUI and HUD
 * @param required    total quantity the schematic needs
 */
public record Requirement(String itemId, String displayName, int required) {
    public Requirement {
        if (required < 0) required = 0;
    }
}
