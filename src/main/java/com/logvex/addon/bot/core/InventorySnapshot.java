package com.logvex.addon.bot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of the player inventory, taken on the client thread and read by the planner
 * and the GUI. Immutability matters: the live inventory can change on any tick, so the planner must
 * work from a stable picture rather than re-reading slots while it reasons.
 */
public final class InventorySnapshot {
    /** Slot index (as exposed by the player inventory) to stack. */
    private final Map<Integer, ItemStackView> slots;
    private final int selectedSlot;

    public InventorySnapshot(Map<Integer, ItemStackView> slots, int selectedSlot) {
        this.slots = new LinkedHashMap<>(slots);
        this.selectedSlot = selectedSlot;
    }

    public static InventorySnapshot empty() {
        return new InventorySnapshot(Map.of(), -1);
    }

    public Map<Integer, ItemStackView> slots() {
        return new LinkedHashMap<>(slots);
    }

    public ItemStackView slot(int index) {
        return slots.getOrDefault(index, ItemStackView.blank());
    }

    public int selectedSlot() {
        return selectedSlot;
    }

    /** Total count of one item across every slot. */
    public int countOf(String itemId) {
        if (itemId == null) return 0;
        int total = 0;
        for (ItemStackView stack : slots.values()) {
            if (stack.isOf(itemId)) total += stack.count();
        }
        return total;
    }

    /** Total count of every item, keyed by registry id. */
    public Map<String, Integer> totals() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemStackView stack : slots.values()) {
            if (stack.empty()) continue;
            out.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        return out;
    }

    /** The slot indices that hold {@code itemId}, in ascending order. */
    public List<Integer> slotsOf(String itemId) {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, ItemStackView> entry : slots.entrySet()) {
            if (entry.getValue().isOf(itemId)) out.add(entry.getKey());
        }
        out.sort(Integer::compareTo);
        return out;
    }

    /** True when there is at least one completely empty slot. */
    public boolean hasEmptySlot() {
        for (ItemStackView stack : slots.values()) {
            if (stack.empty()) return true;
        }
        return false;
    }

    /** How many slots are empty. */
    public int emptySlots() {
        int count = 0;
        for (ItemStackView stack : slots.values()) if (stack.empty()) count++;
        return count;
    }

    /** How many slots are occupied. */
    public int usedSlots() {
        return slots.size() - emptySlots();
    }

    /** How many more items of {@code itemId} fit given the current stacks and empty slots. */
    public int capacityFor(String itemId, int stackSize) {
        if (stackSize <= 0) return 0;
        int room = 0;
        for (ItemStackView stack : slots.values()) {
            if (stack.isOf(itemId)) room += Math.max(0, stackSize - stack.count());
            else if (stack.empty()) room += stackSize;
        }
        return room;
    }

    /**
     * The tool with the most durability left that matches any of {@code itemIds}, or empty.
     * Ties break toward the lowest slot index so selection is deterministic.
     */
    public ItemStackView bestTool(List<String> itemIds, int minimumDurability) {
        ItemStackView best = null;
        int bestSlot = Integer.MAX_VALUE;
        for (Map.Entry<Integer, ItemStackView> entry : slots.entrySet()) {
            ItemStackView stack = entry.getValue();
            if (stack.empty() || !itemIds.contains(stack.itemId())) continue;
            int left = stack.durabilityLeft();
            if (left >= 0 && left <= minimumDurability) continue;
            if (best == null || left > best.durabilityLeft()
                || (left == best.durabilityLeft() && entry.getKey() < bestSlot)) {
                best = stack;
                bestSlot = entry.getKey();
            }
        }
        return best;
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }
}
