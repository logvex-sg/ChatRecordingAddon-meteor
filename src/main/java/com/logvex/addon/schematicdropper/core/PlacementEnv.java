package com.logvex.addon.schematicdropper.core;

import java.util.List;

/**
 * Everything the placement engine needs to read from the live world and player.
 *
 * <p>Implemented by a Minecraft adapter; the engine itself never touches Minecraft types, so its
 * state machine is unit testable with an in-memory world.
 */
public interface PlacementEnv {
    /** True when a player and a world exist and placements are safe to attempt. */
    boolean canRun();

    /** True when the player is in a state that can place blocks (not in a screen, not dead). */
    boolean canPlace();

    /** Reads a block from the world, or air when the position is unavailable. */
    BlockStateSpec stateAt(BlockPos3 pos);

    /** True when the chunk containing the position is loaded. */
    boolean isLoaded(BlockPos3 pos);

    /** True when the position can be replaced without breaking a structure. */
    boolean isReplaceable(BlockPos3 pos);

    /** The block state the world reports as replaceable; used to detect a failed placement. */
    boolean isAirAt(BlockPos3 pos);

    /** Total quantity of {@code itemId} held across the whole inventory. */
    int countOf(String itemId);

    /** True when the client is in Creative mode. */
    boolean isCreative();

    /** The player's current eye position, used for the range check. */
    double[] playerPosition();

    /** Squared distance from the player to a position, or {@code -1} when unavailable. */
    double distanceSquaredTo(BlockPos3 pos);
}
