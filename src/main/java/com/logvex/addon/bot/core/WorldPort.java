package com.logvex.addon.bot.core;

import java.util.List;

/**
 * Read access to the world, filtered to a bounded area. Implementations must never scan the whole
 * world: the bot asks only for what is near the player and within configured limits.
 *
 * <p>All positions are Minecraft-free {@link BlockPos3} values, so the planner can be tested without
 * a running client.
 */
public interface WorldPort {
    /** True when a world and a player exist and the bot may act. */
    boolean canRun();

    /** The dimension id the player is in, e.g. {@code minecraft:overworld}. */
    String dimension();

    /** The player's block position, or {@code null} when there is no player. */
    BlockPos3 playerPos();

    /** The block id at {@code pos}, or {@code null} when unloaded/unknown. */
    String blockAt(BlockPos3 pos);

    /** True when the chunk containing {@code pos} is loaded. */
    boolean isLoaded(BlockPos3 pos);

    /**
     * Every loaded block whose id is in {@code blockIds} within {@code radius} of the player.
     * Implementations must cap the number of results so a huge radius cannot exhaust memory.
     */
    List<BlockPos3> findBlocks(List<String> blockIds, int radius);

    /** Positions of the nearest containers (chests, barrels, shulkers) within {@code radius}. */
    List<BlockPos3> findContainers(int radius);

    /** Block ids of the nearby entities' types, used for threat assessment. */
    List<String> nearbyEntityTypes(int radius);

    /** True when the block at {@code pos} can be replaced (air, grass, water, snow). */
    boolean isReplaceable(BlockPos3 pos);

    /** The light level at {@code pos}, or {@code -1} when unknown. */
    int lightAt(BlockPos3 pos);
}
