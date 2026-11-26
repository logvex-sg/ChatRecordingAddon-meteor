package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.Pathfinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How long planning takes.
 *
 * <p>The planner runs on the game thread, so a slow search is a stutter the player feels. These
 * timings are deliberately generous — they are a guard against a change that makes planning
 * catastrophic, not a benchmark of the machine. A frame is 16 ms; planning must stay well inside
 * that even for the largest grid the port will build.
 */
class PathfinderPerformanceTest {

    private static final int FLOOR = 64;
    private static final int STAND = FLOOR + 1;

    @Test
    @DisplayName("planning across a large open field stays well inside a frame")
    void largeOpenFieldIsFast() {
        // The largest grid the port builds: 256 blocks across.
        TestGrid grid = new TestGrid(128, FLOOR);
        Pathfinder planner = new Pathfinder(60_000, 1);

        // Warm the JIT so the measurement reflects steady state, not first-call compilation.
        for (int i = 0; i < 3; i++) planner.find(grid, new BlockPos3(0, STAND, 0), new BlockPos3(40, STAND, 40));

        long start = System.nanoTime();
        Pathfinder.Result result = planner.find(grid,
            new BlockPos3(-100, STAND, -100), new BlockPos3(100, STAND, 100));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.found(), "a clear 200-block diagonal must be planned");
        assertTrue(millis < 250,
            "planning took " + millis + " ms, which would stall the game thread");
    }

    @Test
    @DisplayName("a search that hits the node budget still returns promptly")
    void budgetBoundedSearchIsFast() {
        TestGrid grid = new TestGrid(128, FLOOR);
        // A goal the search cannot reach forces it to exhaust the whole budget.
        for (int x = -140; x <= 140; x++) grid.wallColumn(x, 60);
        Pathfinder planner = new Pathfinder(60_000, 0);

        long start = System.nanoTime();
        Pathfinder.Result result = planner.find(grid,
            new BlockPos3(0, STAND, 0), new BlockPos3(0, STAND, 60));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.failure() == Pathfinder.Failure.NO_ROUTE
                || result.failure() == Pathfinder.Failure.BUDGET_EXHAUSTED,
            "a walled-off goal must fail, got " + result.failure());
        assertTrue(millis < 500,
            "an exhaustive search took " + millis + " ms, which would freeze the game");
    }
}
