package com.logvex.addon.schematicdropper.core;

/** How the addon interacts with the player inventory. */
public enum InventoryMode {
    /** Click slots directly through the current screen handler (no screen is opened). */
    SILENT,
    /** Open the player inventory screen before clicking. */
    OPEN_INVENTORY;

    @Override
    public String toString() {
        return switch (this) {
            case SILENT -> "Silent (Screen Handler)";
            case OPEN_INVENTORY -> "Open Inventory";
        };
    }
}
