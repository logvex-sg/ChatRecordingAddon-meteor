package com.logvex.addon.schematicdropper.core;

/**
 * One creative inventory give: place {@code amount} of {@code itemId} into {@code slot}.
 *
 * @param itemId     registry id of the item to create
 * @param slot       player-inventory index (0..35 hotbar+main) the stack is placed into
 * @param amount     how many items the stack should hold; never exceeds the stack limit
 * @param stackLimit maximum stack size for the item, used for validation
 */
public record GivePlan(String itemId, int slot, int amount, int stackLimit) {
    public boolean valid() {
        return itemId != null && !itemId.isBlank() && slot >= 0 && amount > 0
            && stackLimit > 0 && amount <= stackLimit;
    }
}
