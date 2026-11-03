package com.logvex.addon.schematicdropper.core;

/**
 * Coarse classification of a block, used by the dependency solver and the placement engine to
 * decide ordering and to explain why a placement needs a particular support.
 */
public enum BlockCategory {
    AIR,
    FLUID,
    /** Falls when unsupported: sand, gravel, concrete powder, anvil, scaffolding. */
    GRAVITY,
    /** Must be placed on a solid face: torch, flower, sapling, rail, carpet, redstone wire. */
    ATTACHED,
    SLAB,
    STAIRS,
    DOOR,
    TRAPDOOR,
    FENCE,
    FENCE_GATE,
    WALL,
    PANE,
    REDSTONE,
    CONTAINER,
    PISTON,
    SIMPLE,
    OTHER;

    public boolean needsSupport() {
        return this == ATTACHED || this == GRAVITY;
    }
}
