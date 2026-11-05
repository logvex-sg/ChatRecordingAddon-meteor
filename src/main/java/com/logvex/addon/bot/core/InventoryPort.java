package com.logvex.addon.bot.core;

import java.util.List;

/**
 * The operations the bot may perform on the inventory. Every implementation must only issue the
 * vanilla interaction (select a slot, move a stack) and must never fabricate items: the server is
 * the authority and a client-only change is not an acquisition.
 */
public interface InventoryPort {
    /** A fresh snapshot of the inventory. */
    InventorySnapshot snapshot();

    /** The stack size limit for {@code itemId} (usually 64, 16 for some items). */
    int stackSize(String itemId);

    /** True when the item id exists in the registry. */
    boolean knownItem(String itemId);

    /** Selects {@code slot} as the active hotbar slot. Returns false when out of range. */
    boolean selectHotbar(int slot);

    /**
     * Moves {@code amount} of {@code itemId} from the inventory into the player's hand area or
     * drops it, according to the caller's plan. Implementations must verify the result.
     */
    boolean moveToHotbar(String itemId, int amount);

    /** Drops the whole currently held stack. */
    boolean dropHeld();

    /** Swaps the held item with {@code inventorySlot}. */
    boolean swapWithSlot(int inventorySlot);
}
