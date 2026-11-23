package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.NavGrid;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import static com.logvex.addon.framework.LogClientContext.mc;

/**
 * The live world, reduced to the handful of facts the pathfinder needs.
 *
 * <p>The grid is a fixed box around the player rather than the whole world: a search over loaded
 * chunks is unbounded work on the game thread, and a route longer than the box is one the bot should
 * not attempt anyway. Reading stops at the box edge and reports {@link Cell#UNKNOWN}, so the planner
 * refuses a goal outside it instead of guessing at terrain it never saw.
 *
 * <p>Every block is classified once and cached for the life of the grid. A path search touches the
 * same cells repeatedly, and re-reading block states for each visit is what makes naive planners
 * stutter.
 */
public final class MinecraftNavGrid implements NavGrid {

    /** The smallest horizontal reach, enough to route around a nearby obstacle. */
    private static final int MIN_HORIZONTAL = 32;
    /**
     * The largest horizontal reach. Beyond this the planner is the wrong tool: a cross-country trip
     * belongs to Baritone, and an unbounded A* over hundreds of blocks would burn the frame.
     */
    public static final int MAX_HORIZONTAL = 128;
    /** How far above the player the grid extends, enough to clear a hill or a build. */
    private static final int UP = 24;
    /** How far below the player the grid extends, enough to see a cave floor. */
    private static final int DOWN = 16;

    private final BlockPos3 min;
    private final BlockPos3 max;
    private final String dimension;
    private final java.util.Map<BlockPos3, Cell> cache = new java.util.HashMap<>();

    private MinecraftNavGrid(BlockPos3 center, String dimension, int horizontal) {
        this.min = new BlockPos3(center.x() - horizontal, center.y() - DOWN, center.z() - horizontal);
        this.max = new BlockPos3(center.x() + horizontal, center.y() + UP, center.z() + horizontal);
        this.dimension = dimension;
    }

    /**
     * Builds a grid centred on the player, reaching far enough to contain {@code goal}.
     *
     * <p>Sizing the box to the goal is what keeps a long trip working: a fixed box would refuse any
     * target past its edge, which would look like a regression to anyone used to the old walker
     * attempting every distance.
     *
     * @return the grid, or {@code null} when there is no world or player to read
     */
    public static MinecraftNavGrid aroundPlayer(BlockPos3 goal) {
        if (mc.player == null || mc.world == null) return null;
        BlockPos3 center = new BlockPos3(
            (int) Math.floor(mc.player.getX()),
            (int) Math.floor(mc.player.getY()),
            (int) Math.floor(mc.player.getZ()));
        int reach = MIN_HORIZONTAL;
        if (goal != null) {
            int needed = Math.max(Math.abs(goal.x() - center.x()), Math.abs(goal.z() - center.z())) + 8;
            reach = Math.min(MAX_HORIZONTAL, Math.max(MIN_HORIZONTAL, needed));
        }
        String dim = mc.world.getRegistryKey().getValue().toString();
        return new MinecraftNavGrid(center, dim, reach);
    }

    @Override
    public BlockPos3 min() {
        return min;
    }

    @Override
    public BlockPos3 max() {
        return max;
    }

    @Override
    public String dimension() {
        return dimension;
    }

    @Override
    public Cell cellAt(BlockPos3 pos) {
        if (!contains(pos)) return Cell.UNKNOWN;
        Cell cached = cache.get(pos);
        if (cached != null) return cached;
        Cell computed = classify(pos);
        cache.put(pos, computed);
        return computed;
    }

    private Cell classify(BlockPos3 pos) {
        if (mc.world == null) return Cell.UNKNOWN;
        BlockPos mcPos = new BlockPos(pos.x(), pos.y(), pos.z());
        if (!mc.world.isChunkLoaded(mcPos.getX() >> 4, mcPos.getZ() >> 4)) return Cell.UNKNOWN;

        BlockState state = mc.world.getBlockState(mcPos);
        // A non-empty fluid state covers water and lava, including a source under a plant.
        if (!state.getFluidState().isEmpty()) return Cell.LIQUID;
        if (state.isAir()) return Cell.OPEN;
        if (isDangerous(state)) return Cell.HAZARD;
        // A block the player could not walk through but could break is an obstacle; a solid one
        // that has room above it is a floor. The planner decides which by looking at the cell above.
        if (state.isReplaceable()) return Cell.OPEN;
        return hasRoomAbove(pos) ? Cell.FLOOR : Cell.OBSTACLE;
    }

    /**
     * Blocks the planner must never route into or break: they damage, trap or destroy.
     *
     * <p>Kept as an explicit list rather than a "looks scary" heuristic, so the reason a block is
     * avoided is reviewable. Fire and lava are liquid-tagged above; this covers the rest.
     */
    private static boolean isDangerous(BlockState state) {
        return state.isOf(Blocks.FIRE)
            || state.isOf(Blocks.SOUL_FIRE)
            || state.isOf(Blocks.MAGMA_BLOCK)
            || state.isOf(Blocks.CACTUS)
            || state.isOf(Blocks.SWEET_BERRY_BUSH)
            || state.isOf(Blocks.POWDER_SNOW)
            || state.isOf(Blocks.COBWEB);
    }

    /** True when the cell directly above {@code pos} is empty, so a player could stand on it. */
    private boolean hasRoomAbove(BlockPos3 pos) {
        BlockPos3 above = pos.above();
        if (!contains(above) || mc.world == null) return false;
        BlockPos mcPos = new BlockPos(above.x(), above.y(), above.z());
        if (!mc.world.isChunkLoaded(mcPos.getX() >> 4, mcPos.getZ() >> 4)) return false;
        BlockState state = mc.world.getBlockState(mcPos);
        return state.isAir() || state.isReplaceable();
    }
}
