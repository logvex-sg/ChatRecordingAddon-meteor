package com.logvex.addon.schematicdropper.core;

/**
 * The next confirmed transfer the engine intends to perform.
 *
 * @param itemId      material being dropped
 * @param slot        player-inventory index the item is taken from
 * @param action      drop one or the whole stack
 * @param expectedOut how many items are expected to leave the inventory
 * @param stackCount  count observed in the slot when the plan was made
 */
public record DropPlan(String itemId, int slot, DropAction action, int expectedOut, int stackCount) {
}
