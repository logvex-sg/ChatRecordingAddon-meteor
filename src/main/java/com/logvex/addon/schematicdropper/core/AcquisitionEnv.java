package com.logvex.addon.schematicdropper.core;

import java.util.List;

/**
 * Everything the acquisition engine needs to read from the live client. Implemented over
 * Minecraft; the engine itself never touches Minecraft types.
 */
public interface AcquisitionEnv {
    /** True when a player, a world and an interaction manager exist. */
    boolean canRun();

    /** True when the client is currently in creative mode. */
    boolean isCreative();

    /** True when the client can currently send a creative give (a screen handler is present). */
    boolean canInteract();

    /** Number of items of {@code itemId} already held across the whole player inventory. */
    int countOf(String itemId);

    /** A snapshot of every writable player-inventory slot (hotbar and main rows), ascending. */
    List<SlotInfo> writableSlots();

    /** Maximum stack size of {@code itemId}, or 0 when the id is unknown. */
    int stackLimitOf(String itemId);

    /** True when {@code itemId} exists in the item registry. */
    boolean isKnownItem(String itemId);
}
