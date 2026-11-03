package com.logvex.addon.schematicdropper.core;

/**
 * A snapshot of one writable player-inventory slot.
 *
 * @param slot      player-inventory index (0..35 hotbar+main)
 * @param itemId    registry id of the held stack, or {@code null} when the slot is empty
 * @param count     number of items currently in the slot
 * @param stackLimit maximum stack size of the held item, or 0 when empty
 */
public record SlotInfo(int slot, String itemId, int count, int stackLimit) {
    public boolean empty() {
        return itemId == null || itemId.isBlank() || count <= 0;
    }

    public boolean holds(String id) {
        return id != null && id.equals(itemId);
    }

    /** Free room for more items of {@code id} in this slot, or 0 when it cannot take any. */
    public int roomFor(String id, int limit) {
        if (empty()) return Math.max(0, limit);
        if (!holds(id)) return 0;
        return Math.max(0, stackLimit - count);
    }
}
