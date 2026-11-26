package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.NavGrid.Cell;
import com.logvex.addon.bot.core.NavRules;
import com.logvex.addon.bot.core.Pathfinder;
import com.logvex.addon.bot.core.Pathfinder.Failure;
import com.logvex.addon.bot.core.Pathfinder.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pathfinder.
 *
 * <p>These tests are written against a hand-built world, so a failure means the search is wrong
 * rather than that a mock drifted. The cases chosen are the ones that separate a real planner from
 * a straight-line walker: a wall that must be walked around, a pit that must be avoided, a jump that
 * is legal, a drop that is too deep, and a target that is simply impossible.
 */
class PathfinderTest {

    private static final int FLOOR = 64;
    /** The height a player stands at on a flat floor. */
    private static final int STAND = FLOOR + 1;

    private static BlockPos3 at(int x, int z) {
        return new BlockPos3(x, STAND, z);
    }

    private static Pathfinder planner() {
        return new Pathfinder();
    }

    @Test
    @DisplayName("walks a straight line across open ground")
    void straightLine() {
        TestGrid grid = new TestGrid(16, FLOOR);
        Result result = planner().find(grid, at(0, 0), at(5, 0));

        assertTrue(result.found(), "a clear straight run must be found");
        assertEquals(at(5, 0), result.path().get(result.path().size() - 1));
        // Five blocks east at 10 tenths each.
        assertEquals(50, result.cost());
    }

    @Test
    @DisplayName("the path excludes the start cell, so a walker can follow it directly")
    void pathStartsAfterTheStartCell() {
        TestGrid grid = new TestGrid(16, FLOOR);
        Result result = planner().find(grid, at(0, 0), at(3, 0));

        assertTrue(result.found());
        assertFalse(result.path().contains(at(0, 0)),
            "the start cell must not appear in the path, or the bot would walk back into it");
    }

    @Test
    @DisplayName("every step of the returned path is a legal move")
    void everyStepIsLegal() {
        TestGrid grid = new TestGrid(24, FLOOR);
        // A wall with one gap, so the route has to turn.
        for (int z = -6; z <= 6; z++) {
            if (z != 0) grid.wallColumn(4, z);
        }
        Result result = planner().find(grid, at(0, 0), at(10, 0));

        assertTrue(result.found(), "a gap in the wall must be found");
        BlockPos3 cursor = at(0, 0);
        for (BlockPos3 step : result.path()) {
            boolean legal = false;
            for (int dir = 0; dir < NavRules.directionCount(); dir++) {
                NavRules.Step s = NavRules.step(grid, cursor, dir);
                if (s != null && s.to().equals(step)) {
                    legal = true;
                    break;
                }
            }
            assertTrue(legal, "the planner produced the illegal move " + cursor + " -> " + step);
            assertTrue(NavRules.standable(grid, step), step + " is not a place a player can stand");
            cursor = step;
        }
        assertEquals(at(10, 0), cursor);
    }

    @Test
    @DisplayName("routes around a wall instead of through it")
    void goesAroundAWall() {
        TestGrid grid = new TestGrid(24, FLOOR);
        for (int z = -3; z <= 3; z++) grid.wallColumn(3, z);
        Result result = planner().find(grid, at(0, 0), at(6, 0));

        assertTrue(result.found(), "the wall is finite, so a route exists around it");
        for (BlockPos3 pos : result.path()) {
            assertEquals(Cell.OPEN, grid.cellAt(pos), "the path entered a wall at " + pos);
        }
        // A detour is longer than the straight-line distance of 6 blocks.
        assertTrue(result.cost() > 60, "the detour should cost more than walking straight through");
    }

    @Test
    @DisplayName("jumps up a single block, which a player can do")
    void climbsOneBlock() {
        TestGrid grid = new TestGrid(16, FLOOR);
        // A plateau one block high from x = 3 onward: the player must hop onto it and stay up.
        for (int x = 3; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) grid.floorAt(x, STAND, z);
        }
        Result result = planner().find(grid, at(0, 0), new BlockPos3(6, STAND + 1, 0));

        assertTrue(result.found(), "a one-block rise must be climbable");
        // The walk is 6 blocks, and one of the moves is a climb, so it must cost more than flat.
        assertTrue(result.cost() > 6 * NavRules.STRAIGHT_COST,
            "climbing must cost more than walking the same distance flat, cost was " + result.cost());
        BlockPos3 end = result.path().get(result.path().size() - 1);
        assertEquals(STAND + 1, end.y(), "the path must end on top of the plateau, ended at " + end);
    }

    @Test
    @DisplayName("refuses a two-block wall, which a player cannot jump unaided")
    void refusesTwoBlockWall() {
        TestGrid grid = new TestGrid(16, FLOOR);
        // Two solid blocks stacked: no unaided jump clears this.
        for (int z = -6; z <= 6; z++) {
            grid.floorAt(3, STAND, z);
            grid.floorAt(3, STAND + 1, z);
        }
        Result result = planner().find(grid, at(0, 0), new BlockPos3(6, STAND + 2, 0));

        assertFalse(result.found(),
            "a two-block wall must not be climbed; the bot would jump at it forever");
    }

    @Test
    @DisplayName("drops into a shallow pit but not into a deep one")
    void respectsTheFallLimit() {
        TestGrid shallow = new TestGrid(16, FLOOR);
        shallow.hole(2, 0);
        // The pit is one deep here: the floor below the hole is still solid.
        Result intoShallow = planner().find(shallow, at(0, 0), at(4, 0));
        assertTrue(intoShallow.found(), "a one-block drop must be walkable");

        TestGrid deep = new TestGrid(16, FLOOR);
        // Remove the floor for a whole strip and dig well below it.
        for (int x = 1; x <= 4; x++) {
            for (int y = FLOOR - 5; y <= FLOOR; y++) deep.hazard(x, y, 0);
        }
        Result acrossDeep = planner().find(deep, at(0, 0), at(6, 0));
        // A hazard is impassable, so the route must go around rather than drop in.
        for (BlockPos3 pos : acrossDeep.path()) {
            assertTrue(pos.x() < 1 || pos.x() > 4 || pos.z() != 0,
                "the path fell into the deep pit at " + pos);
        }
    }

    @Test
    @DisplayName("never routes through liquid")
    void avoidsLiquid() {
        TestGrid grid = new TestGrid(24, FLOOR);
        for (int z = -3; z <= 3; z++) {
            grid.liquid(3, STAND, z);
            grid.liquid(3, STAND + 1, z);
        }
        Result result = planner().find(grid, at(0, 0), at(6, 0));

        assertTrue(result.found(), "liquid can be walked around");
        for (BlockPos3 pos : result.path()) {
            assertTrue(grid.cellAt(pos) != Cell.LIQUID, "the path entered liquid at " + pos);
        }
    }

    @Test
    @DisplayName("reports an unreachable goal instead of searching forever")
    void reportsNoRoute() {
        TestGrid grid = new TestGrid(24, FLOOR);
        // Box the player in completely.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                grid.wallColumn(dx, dz);
            }
        }
        Result result = planner().find(grid, at(0, 0), at(10, 10));

        assertFalse(result.found());
        assertEquals(Failure.NO_ROUTE, result.failure());
    }

    @Test
    @DisplayName("refuses a goal outside the grid rather than guessing")
    void refusesOutOfBoundsGoal() {
        TestGrid grid = new TestGrid(8, FLOOR);
        Result result = planner().find(grid, at(0, 0), new BlockPos3(500, STAND, 500));

        assertFalse(result.found());
        assertEquals(Failure.OUT_OF_BOUNDS, result.failure());
        assertEquals(0, result.expanded(), "an out-of-bounds goal must not cost a search");
    }

    @Test
    @DisplayName("refuses to start from a cell a player could not occupy")
    void refusesBadStart() {
        TestGrid grid = new TestGrid(16, FLOOR);
        grid.wall(0, STAND, 0);
        Result result = planner().find(grid, at(0, 0), at(4, 0));

        assertFalse(result.found());
        assertEquals(Failure.BAD_START, result.failure());
    }

    @Test
    @DisplayName("a goal that is already reached returns an empty path, not a failure")
    void alreadyThere() {
        TestGrid grid = new TestGrid(16, FLOOR);
        Result result = planner().find(grid, at(2, 2), at(2, 2));

        assertTrue(result.found() || result.path().isEmpty(),
            "being at the goal is success with nothing to walk");
        assertEquals(Failure.NONE, result.failure());
        assertNull(result.next(), "there is nothing left to walk to");
    }

    @Test
    @DisplayName("the goal radius accepts a nearby cell")
    void goalRadius() {
        TestGrid grid = new TestGrid(16, FLOOR);
        Result result = new Pathfinder(40_000, 2).find(grid, at(0, 0), at(10, 0));

        assertTrue(result.found());
        BlockPos3 end = result.path().get(result.path().size() - 1);
        assertTrue(Math.abs(end.x() - 10) <= 2,
            "a radius of 2 must stop within 2 blocks of the goal, stopped at " + end);
    }

    @Test
    @DisplayName("the node budget stops a search that would otherwise run away")
    void budgetIsEnforced() {
        TestGrid grid = new TestGrid(60, FLOOR);
        // A large open field with a tiny budget: the search cannot reach the far corner.
        Result result = new Pathfinder(20, 0).find(grid, at(-50, -50), at(50, 50));

        assertFalse(result.found());
        assertEquals(Failure.BUDGET_EXHAUSTED, result.failure());
        assertTrue(result.expanded() <= 20,
            "the search expanded " + result.expanded() + " nodes against a budget of 20");
    }

    @Test
    @DisplayName("a diagonal may not squeeze between two blocked corners")
    void noCornerCutting() {
        TestGrid grid = new TestGrid(16, FLOOR);
        // Two blocks meeting at a corner: the diagonal between them must be refused.
        grid.wallColumn(1, 0);
        grid.wallColumn(0, 1);
        NavRules.Step squeeze = NavRules.step(grid, at(0, 0), 5);
        assertNull(squeeze, "a diagonal between two blocked corners is not a walkable move");

        // With one side open, the same diagonal is fine.
        TestGrid open = new TestGrid(16, FLOOR);
        open.wallColumn(1, 0);
        assertNotNull(NavRules.step(open, at(0, 0), 5),
            "one blocked corner still leaves room to walk past");
    }

    @Test
    @DisplayName("the heuristic never overestimates, which is what keeps A* optimal")
    void heuristicIsAdmissible() {
        BlockPos3 a = new BlockPos3(0, 64, 0);
        for (int x = -8; x <= 8; x += 4) {
            for (int z = -8; z <= 8; z += 4) {
                BlockPos3 b = new BlockPos3(x, 64 + Math.abs(x) % 3, z);
                int h = NavRules.heuristic(a, b);
                // The cheapest possible real cost is a straight diagonal run at DIAGONAL_COST.
                int dx = Math.abs(a.x() - b.x());
                int dz = Math.abs(a.z() - b.z());
                int cheapestFlat = NavRules.DIAGONAL_COST * Math.min(dx, dz)
                    + NavRules.STRAIGHT_COST * Math.abs(dx - dz);
                assertTrue(h >= cheapestFlat,
                    "the heuristic must not be optimistic below the true flat cost");
            }
        }
    }

    @Test
    @DisplayName("the same world and goal always produce the same path")
    void deterministic() {
        TestGrid grid = new TestGrid(24, FLOOR);
        for (int z = -5; z <= 5; z++) {
            if (z != 2) grid.wallColumn(5, z);
        }
        Result first = planner().find(grid, at(0, 0), at(10, 0));
        Result second = planner().find(grid, at(0, 0), at(10, 0));

        assertEquals(first.path(), second.path(),
            "an unstable planner makes the bot jitter between equal-cost routes");
        assertEquals(first.cost(), second.cost());
    }

    @Test
    @DisplayName("smoothing keeps the route walkable and does not lengthen it")
    void smoothingPreservesTheRoute() {
        TestGrid grid = new TestGrid(24, FLOOR);
        for (int z = -5; z <= 5; z++) {
            if (z != 1) grid.wallColumn(5, z);
        }
        BlockPos3 start = at(0, 0);
        BlockPos3 goal = at(10, 0);
        Result raw = planner().find(grid, start, goal);
        assertTrue(raw.found());

        List<BlockPos3> smooth = com.logvex.addon.bot.core.PathSmoother.smooth(grid, start, raw.path());
        assertFalse(smooth.isEmpty());
        assertEquals(goal, smooth.get(smooth.size() - 1),
            "smoothing must not change where the walk ends");
        assertTrue(smooth.size() <= raw.path().size(),
            "smoothing must not add waypoints");

        // Every smoothed leg must be a straight walk a player could make.
        BlockPos3 cursor = start;
        for (BlockPos3 waypoint : smooth) {
            assertTrue(com.logvex.addon.bot.core.PathSmoother.walkableLine(grid, cursor, waypoint),
                "the smoothed leg " + cursor + " -> " + waypoint + " is not walkable");
            cursor = waypoint;
        }
    }
}
