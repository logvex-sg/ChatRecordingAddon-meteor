package com.logvex.addon.schematicdropper.core;

/**
 * Everything the planning layers need to know about a block, expressed without Minecraft types.
 *
 * <p>The Minecraft adapter implements this using the real block classes ({@code FallingBlock},
 * {@code StairsBlock}, {@code SlabBlock}, ...) so the ordering rules follow verified game
 * behaviour rather than a guess about the item name.
 */
public interface BlockTraits {
    boolean isAir(String blockId);

    boolean isFluid(String blockId);

    /** True when a block can be replaced by a placement without breaking it first. */
    boolean isReplaceable(String blockId);

    boolean isGravityAffected(String blockId);

    /** True when the block needs a solid neighbour to stay: torch, plant, rail, redstone, ... */
    boolean requiresSupport(String blockId);

    boolean isDirectional(String blockId);

    boolean isContainer(String blockId);

    boolean isRedstoneComponent(String blockId);

    /** Door, bed, tall plant, ... : occupies more than one cell or has a complex place rule. */
    boolean hasSpecialPlacement(String blockId);

    boolean isValid(String blockId);

    BlockCategory category(String blockId);

    String displayName(String blockId);

    /** Maximum stack size of the block's item, or 64 when unknown. */
    int stackLimit(String blockId);
}
