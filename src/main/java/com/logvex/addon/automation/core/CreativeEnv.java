package com.logvex.addon.automation.core;

/**
 * Read-only view of the inventory and registry the creative controller needs.
 *
 * <p>Keeping the reads on a small interface means the controller's decisions are testable with a
 * fake inventory, and the Minecraft adapter only has to answer questions rather than make choices.
 */
public interface CreativeEnv {
    /** False when there is no world or player, so no give can be attempted. */
    boolean canRun();

    /** Whether {@code itemId} is a real item in the registry. */
    boolean knownItem(String itemId);

    /** How many of {@code itemId} the player already holds, across the whole inventory. */
    int countOf(String itemId);

    /** How many inventory slots are empty. */
    int freeSlots();

    /** The stack limit for {@code itemId}, or 64 when unknown. */
    int stackLimit(String itemId);

    /** The first empty player-inventory slot index, or -1 when the inventory is full. */
    int firstEmptySlot();

    /** The first player-inventory slot index already holding {@code itemId}, or -1. */
    int firstSlotContaining(String itemId);

    /** How many items the given slot holds, or 0 when it is empty or out of range. */
    int countInSlot(int slot);

    /** The number of player-inventory slots (36 in vanilla). */
    int inventorySize();
}
