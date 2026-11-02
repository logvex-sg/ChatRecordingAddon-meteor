package com.logvex.addon.schematicdropper.core;

/**
 * One inventory stack that can be used to satisfy a requirement.
 *
 * @param slot  player inventory index (0..35 hotbar+main, 36..39 armour, 40 offhand)
 * @param count number of items currently in that slot
 */
public record StackEntry(int slot, int count) {
    public boolean isEmpty() {
        return count <= 0;
    }
}
