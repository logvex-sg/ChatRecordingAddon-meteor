package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.NavGrid;
import com.logvex.addon.bot.core.NavGrid.Cell;
import com.logvex.addon.bot.core.NavRules;
import com.logvex.addon.bot.core.PathSmoother;
import com.logvex.addon.bot.core.Pathfinder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A flat, buildable {@link NavGrid} for tests: a box of air with a solid floor at a chosen height,
 * into which a test carves walls, pits and hazards.
 *
 * <p>It models exactly what the planner is allowed to see, so a test failure means the planner is
 * wrong rather than that the fake is unrealistic.
 */
final class TestGrid implements NavGrid {

    private final BlockPos3 min;
    private final BlockPos3 max;
    private final int floorY;
    private final Map<BlockPos3, Cell> overrides = new HashMap<>();

    TestGrid(int radius, int floorY) {
        this.min = new BlockPos3(-radius, floorY, -radius);
        this.max = new BlockPos3(radius, floorY + 6, radius);
        this.floorY = floorY;
    }

    /** Blocks a cell entirely, as a wall would. */
    TestGrid wall(int x, int y, int z) {
        overrides.put(new BlockPos3(x, y, z), Cell.OBSTACLE);
        return this;
    }

    /** Blocks a whole column from the floor upward, which is how a wall is really built. */
    TestGrid wallColumn(int x, int z) {
        for (int y = floorY; y <= max.y(); y++) overrides.put(new BlockPos3(x, y, z), Cell.OBSTACLE);
        return this;
    }

    TestGrid hazard(int x, int y, int z) {
        overrides.put(new BlockPos3(x, y, z), Cell.HAZARD);
        return this;
    }

    TestGrid liquid(int x, int y, int z) {
        overrides.put(new BlockPos3(x, y, z), Cell.LIQUID);
        return this;
    }

    /** Adds a solid block at {@code y} that can be stood on. */
    TestGrid floorAt(int x, int y, int z) {
        overrides.put(new BlockPos3(x, y, z), Cell.FLOOR);
        return this;
    }

    /** Removes the floor, leaving a pit. */
    TestGrid hole(int x, int z) {
        for (int y = floorY; y <= max.y(); y++) overrides.put(new BlockPos3(x, y, z), Cell.OPEN);
        return this;
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
    public Cell cellAt(BlockPos3 pos) {
        if (!contains(pos)) return Cell.UNKNOWN;
        Cell override = overrides.get(pos);
        if (override != null) return override;
        if (pos.y() < floorY) return Cell.FLOOR;
        if (pos.y() == floorY) return Cell.FLOOR;
        return Cell.OPEN;
    }

    @Override
    public String dimension() {
        return "minecraft:overworld";
    }
}
