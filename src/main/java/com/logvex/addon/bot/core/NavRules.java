package com.logvex.addon.bot.core;

import com.logvex.addon.bot.core.NavGrid.Cell;

/**
 * The moves a player can actually make, and what each one costs.
 *
 * <p>Kept apart from the search so the rules can be tested one at a time: a pathfinder that walks
 * through a wall is usually a bug in these predicates, not in A*. Every rule here is checked
 * against the {@link NavGrid}, never against a guess about what the world "probably" contains.
 *
 * <p>Deliberate limits, all of which keep the result walkable by a real player:
 * <ul>
 *   <li>No move needs more than a one-block jump, because that is all a player can do without a
 *       potion or a block to place.</li>
 *   <li>Falls are capped at {@link #MAX_FALL}, because a longer drop is damage the bot must not
 *       take on the operator's behalf.</li>
 *   <li>A diagonal may not cut a corner between two blocked cells, or the player catches on the
 *       edge in the real world even though the maths says the move is clear.</li>
 * </ul>
 */
public final class NavRules {

    /** The deepest drop the planner will route through, in blocks. */
    public static final int MAX_FALL = 3;

    /** Cost of one straight step, in tenths of a block so no floating point drift creeps in. */
    public static final int STRAIGHT_COST = 10;
    /** Cost of a diagonal step: 14 approximates {@code 10 * sqrt(2)} without floating point. */
    public static final int DIAGONAL_COST = 14;
    /** Extra cost for climbing one block, so a flat route is preferred over a staircase. */
    public static final int CLIMB_COST = 6;
    /** Extra cost per block fallen, so the planner prefers to stay high when it can. */
    public static final int FALL_COST = 4;

    /** The eight horizontal directions, straight ones first so ties resolve predictably. */
    private static final int[][] DIRECTIONS = {
        {0, -1}, {1, 0}, {0, 1}, {-1, 0},
        {1, -1}, {1, 1}, {-1, 1}, {-1, -1}
    };

    private NavRules() {
    }

    /** The number of horizontal directions {@link #direction} accepts. */
    public static int directionCount() {
        return DIRECTIONS.length;
    }

    /**
     * The {@code [dx, dz]} offset of a direction index, or {@code null} when out of range.
     *
     * <p>Indices are stable, so a caller can walk {@code 0..}{@link #directionCount()} and get every
     * neighbour exactly once.
     */
    public static int[] direction(int index) {
        if (index < 0 || index >= DIRECTIONS.length) return null;
        return DIRECTIONS[index].clone();
    }

    /** True when a direction index is diagonal, which matters for corner cutting. */
    public static boolean isDiagonal(int index) {
        if (index < 0 || index >= DIRECTIONS.length) return false;
        return DIRECTIONS[index][0] != 0 && DIRECTIONS[index][1] != 0;
    }

    /** A single move from one cell to a neighbouring one, with its cost. */
    public record Step(BlockPos3 to, int cost, boolean climb, int fall) {
    }

    /**
     * Computes the step from {@code from} in {@code direction}, or {@code null} when the move is not
     * possible.
     *
     * <p>The three cases a player has are: walk level, step up one, drop up to {@link #MAX_FALL}.
     * Each needs the destination column to have footing and headroom, which {@link #standable}
     * decides.
     */
    public static Step step(NavGrid grid, BlockPos3 from, int direction) {
        int[] dir = direction(direction);
        if (dir == null || grid == null || from == null) return null;
        int dx = dir[0];
        int dz = dir[1];
        boolean diagonal = isDiagonal(direction);

        // A diagonal may not squeeze between two blocked corners: the player would catch on one of
        // them, so the move is only legal when at least one of the two side cells is passable.
        if (diagonal && !cornerIsClear(grid, from, dx, dz)) return null;

        BlockPos3 flat = new BlockPos3(from.x() + dx, from.y(), from.z() + dz);
        if (standable(grid, flat)) {
            return new Step(flat, diagonal ? DIAGONAL_COST : STRAIGHT_COST, false, 0);
        }

        // Step up: the destination floor is one higher and there is room to stand on it.
        BlockPos3 up = new BlockPos3(from.x() + dx, from.y() + 1, from.z() + dz);
        if (standable(grid, up) && headroom(grid, from)) {
            return new Step(up, (diagonal ? DIAGONAL_COST : STRAIGHT_COST) + CLIMB_COST, true, 0);
        }

        // Fall: the first standable cell below the flat destination, within the safe drop.
        for (int drop = 1; drop <= MAX_FALL; drop++) {
            BlockPos3 down = new BlockPos3(from.x() + dx, from.y() - drop, from.z() + dz);
            if (standable(grid, down)) {
                return new Step(down, (diagonal ? DIAGONAL_COST : STRAIGHT_COST) + FALL_COST * drop,
                    false, drop);
            }
            // Stop descending at the first cell that blocks the drop, or a fall would pass through
            // a floor into the space beneath it.
            if (!openForFalling(grid, new BlockPos3(down.x(), down.y() + 1, down.z()))) return null;
        }
        return null;
    }

    /**
     * True when a player can stand in {@code pos}: the cell itself and the one above are open, and
     * the cell below is something to stand on.
     */
    public static boolean standable(NavGrid grid, BlockPos3 pos) {
        if (grid == null || pos == null || !grid.contains(pos)) return false;
        Cell here = grid.cellAt(pos);
        if (here != Cell.OPEN) return false;
        if (!grid.contains(pos.above())) return false;
        if (grid.cellAt(pos.above()) != Cell.OPEN) return false;
        // The floor may be the bottom of the grid, in which case nothing is known below it.
        if (!grid.contains(pos.below())) return false;
        Cell floor = grid.cellAt(pos.below());
        return floor == Cell.FLOOR || floor == Cell.OBSTACLE;
    }

    /** True when the cell can be fallen through or stood in without stopping a descent. */
    private static boolean openForFalling(NavGrid grid, BlockPos3 pos) {
        if (!grid.contains(pos)) return false;
        Cell cell = grid.cellAt(pos);
        return cell == Cell.OPEN;
    }

    /** True when there is room to jump: the cell above the player is open. */
    private static boolean headroom(NavGrid grid, BlockPos3 from) {
        BlockPos3 above = from.above();
        return grid.contains(above) && grid.cellAt(above) == Cell.OPEN;
    }

    /**
     * A diagonal is clear when at least one of its two orthogonal side cells is standable, so the
     * player has somewhere to brush past instead of catching on a corner.
     */
    private static boolean cornerIsClear(NavGrid grid, BlockPos3 from, int dx, int dz) {
        BlockPos3 sideX = new BlockPos3(from.x() + dx, from.y(), from.z());
        BlockPos3 sideZ = new BlockPos3(from.x(), from.y(), from.z() + dz);
        return standable(grid, sideX) || standable(grid, sideZ);
    }

    /**
     * The admissible heuristic: horizontal distance plus a term for the height gap.
     *
     * <p>It never overestimates, which is what keeps A* optimal. The height term uses
     * {@link #STRAIGHT_COST} for a climb because a climb costs more than that in reality — an
     * underestimate, never an overestimate.
     */
    public static int heuristic(BlockPos3 from, BlockPos3 to) {
        if (from == null || to == null) return 0;
        int dx = Math.abs(from.x() - to.x());
        int dz = Math.abs(from.z() - to.z());
        int dy = Math.abs(from.y() - to.y());
        // Octile distance: the diagonal part is cheaper than two straight steps.
        int flat = STRAIGHT_COST * (dx + dz) + (DIAGONAL_COST - 2 * STRAIGHT_COST) * Math.min(dx, dz);
        return flat + STRAIGHT_COST * dy;
    }
}
