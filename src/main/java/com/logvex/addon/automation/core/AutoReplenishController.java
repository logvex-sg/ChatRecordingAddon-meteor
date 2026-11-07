package com.logvex.addon.automation.core;

import java.util.List;
import java.util.Map;

/**
 * Decides which hotbar slot should hold which item, so the hotbar stays stocked with the tools and
 * blocks the player uses most.
 *
 * <p>Replenishment only ever moves items that are already in the inventory into the hotbar, or
 * restocks a hotbar slot from the same item elsewhere in the inventory. It never invents items, so
 * it is safe in Survival: the worst case is that nothing is moved. The controller returns a list of
 * slot assignments and lets the Minecraft layer perform them, which keeps the decision testable.
 */
public final class AutoReplenishController {
    /** One hotbar slot that should be filled with {@code itemId} from {@code sourceSlot}. */
    public record Assignment(int hotbarSlot, String itemId, int sourceSlot) {
    }

    private boolean enabled;
    private boolean refillFromInventory = true;
    private int minCount;

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setRefillFromInventory(boolean value) {
        this.refillFromInventory = value;
    }

    public boolean refillFromInventory() {
        return refillFromInventory;
    }

    /** How many of an item a hotbar slot should hold before it is considered stocked. */
    public void setMinCount(int value) {
        this.minCount = Math.max(0, value);
    }

    public int minCount() {
        return minCount;
    }

    /**
     * Computes the hotbar assignments needed to stock the desired items.
     *
     * @param desired     hotbar slot to item id the user wants held there
     * @param hotbar      the current hotbar contents (slot to item id, null/absent when empty)
     * @param inventory   the current main-inventory contents (slot to item id)
     * @return the assignments to apply, in slot order
     */
    public List<Assignment> plan(Map<Integer, String> desired,
                                 Map<Integer, String> hotbar,
                                 Map<Integer, String> inventory) {
        List<Assignment> out = new java.util.ArrayList<>();
        if (!enabled || desired == null || desired.isEmpty()) return out;

        for (Map.Entry<Integer, String> entry : desired.entrySet()) {
            int slot = entry.getKey();
            String itemId = entry.getValue();
            if (itemId == null || itemId.isBlank()) continue;
            if (slot < 0 || slot > 8) continue;

            String held = hotbar == null ? null : hotbar.get(slot);
            if (itemId.equals(held)) continue;

            if (!refillFromInventory || inventory == null) continue;
            int source = findSource(itemId, inventory, hotbar);
            if (source < 0) continue;
            out.add(new Assignment(slot, itemId, source));
        }
        return out;
    }

    private static int findSource(String itemId, Map<Integer, String> inventory, Map<Integer, String> hotbar) {
        for (Map.Entry<Integer, String> entry : inventory.entrySet()) {
            if (!itemId.equals(entry.getValue())) continue;
            int slot = entry.getKey();
            // Do not take from a hotbar slot that another assignment may need.
            if (hotbar != null && hotbar.containsKey(slot) && itemId.equals(hotbar.get(slot))) continue;
            return slot;
        }
        return -1;
    }

    public String status() {
        return enabled ? (refillFromInventory ? "on (inventory refill)" : "on (hotbar only)") : "off";
    }
}
