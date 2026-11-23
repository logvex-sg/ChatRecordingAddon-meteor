package com.logvex.addon.bot.core;

/**
 * The world as the pathfinder sees it: a bounded box of cells, each one of a small set of
 * walkability kinds.
 *
 * <p>This exists so the planner can be written once and tested without a client. A real world is
 * far richer than the planner needs — it cares only about "can I stand here", "is there something
 * to stand on", "can I walk through this" and "is this lava" — so the Minecraft side reduces each
 * block to a {@link Cell} and the planner never touches a {@code BlockState}.
 *
 * <p>A grid is <b>bounded on purpose</b>. An unbounded planner over an infinite world is how a
 * pathfinder turns into a freeze; every implementation must answer for a fixed box and report
 * {@link #contains} honestly so the planner can refuse a goal outside it instead of searching
 * forever.
 */
public interface NavGrid {

    /** What the planner needs to know about a single cell. */
    enum Cell {
        /** Empty space a player can occupy. */
        OPEN,
        /** Something solid to stand on, with room to occupy the cell above it. */
        FLOOR,
        /** Blocking terrain a player could break, but not walk through. */
        OBSTACLE,
        /** Blocking terrain that must never be broken or walked through. */
        HAZARD,
        /** Water or lava: the planner treats liquid as impassable. */
        LIQUID,
        /** Outside the loaded/bounded area, so nothing can be known about it. */
        UNKNOWN
    }

    /** The lowest coordinate included in this grid, inclusive. */
    BlockPos3 min();

    /** The highest coordinate included in this grid, inclusive. */
    BlockPos3 max();

    /** The kind of the cell at {@code pos}. Must return {@link Cell#UNKNOWN} outside the bounds. */
    Cell cellAt(BlockPos3 pos);

    /** The dimension this grid describes, so a stale plan cannot be walked in another world. */
    String dimension();

    /** True when {@code pos} lies inside this grid's bounds. */
    default boolean contains(BlockPos3 pos) {
        BlockPos3 lo = min();
        BlockPos3 hi = max();
        return pos != null
            && pos.x() >= lo.x() && pos.x() <= hi.x()
            && pos.y() >= lo.y() && pos.y() <= hi.y()
            && pos.z() >= lo.z() && pos.z() <= hi.z();
    }

    /** The number of cells in the grid, used to size the search budget honestly. */
    default long volume() {
        BlockPos3 lo = min();
        BlockPos3 hi = max();
        long dx = (long) hi.x() - lo.x() + 1;
        long dy = (long) hi.y() - lo.y() + 1;
        long dz = (long) hi.z() - lo.z() + 1;
        return Math.max(0, dx) * Math.max(0, dy) * Math.max(0, dz);
    }
}
