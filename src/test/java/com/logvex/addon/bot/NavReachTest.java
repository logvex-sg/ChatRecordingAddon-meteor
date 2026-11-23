package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.NavReach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Staging a long trip into reachable legs.
 *
 * <p>The planner only searches a bounded box, so a distant goal has to be pulled back to the edge of
 * that box and re-planned. These tests pin the arithmetic, because an off-by-one here turns a long
 * walk into an immediate "unreachable" and looks like the bot refusing to move.
 */
class NavReachTest {

    private static final BlockPos3 START = new BlockPos3(0, 64, 0);

    @Test
    @DisplayName("a goal within reach is left exactly where it is")
    void nearGoalIsUntouched() {
        BlockPos3 goal = new BlockPos3(40, 64, 30);
        assertEquals(goal, NavReach.clamp(START, goal, 120));
        assertTrue(NavReach.inReach(START, goal, 120));
    }

    @Test
    @DisplayName("a goal at the limit is still reachable, not pulled back")
    void exactlyAtLimit() {
        BlockPos3 goal = new BlockPos3(120, 64, 0);
        assertEquals(goal, NavReach.clamp(START, goal, 120),
            "a goal exactly at the limit must not be moved, or the last block is never reached");
        assertTrue(NavReach.inReach(START, goal, 120));
    }

    @Test
    @DisplayName("a far goal is pulled back to the limit, keeping its heading")
    void farGoalIsPulledBack() {
        BlockPos3 goal = new BlockPos3(1000, 64, 0);
        BlockPos3 staged = NavReach.clamp(START, goal, 120);

        assertTrue(NavReach.inReach(START, staged, 120), "the staged goal must be reachable");
        assertEquals(120, staged.x(), "the staged goal should sit at the limit along the heading");
        assertEquals(0, staged.z(), "the heading must be preserved, not bent sideways");
    }

    @Test
    @DisplayName("a diagonal goal keeps its diagonal when pulled back")
    void diagonalKeepsItsDirection() {
        BlockPos3 goal = new BlockPos3(600, 64, 600);
        BlockPos3 staged = NavReach.clamp(START, goal, 100);

        assertTrue(NavReach.inReach(START, staged, 100));
        assertEquals(staged.x(), staged.z(),
            "an even diagonal must stay even, or the bot walks a different route than planned");
    }

    @Test
    @DisplayName("the staged goal keeps the requested height")
    void heightIsPreserved() {
        BlockPos3 goal = new BlockPos3(500, 120, 0);
        BlockPos3 staged = NavReach.clamp(START, goal, 64);
        assertEquals(120, staged.y(), "a staged goal must not drop to the player's feet");
    }

    @Test
    @DisplayName("clamping repeatedly always converges on the goal")
    void repeatedClampingConverges() {
        // Walking in stages must actually arrive: each leg should get strictly closer.
        BlockPos3 goal = new BlockPos3(1000, 64, 0);
        BlockPos3 cursor = START;
        int legs = 0;
        while (!cursor.equals(goal) && legs < 100) {
            BlockPos3 next = NavReach.clamp(cursor, goal, 120);
            assertFalse(next.equals(cursor), "a leg must make progress, otherwise the walk stalls");
            cursor = next;
            legs++;
        }
        assertEquals(goal, cursor, "staged walking must eventually reach the goal");
        assertEquals(9, legs, "1000 blocks at 120 per leg should take 9 legs");
    }

    @Test
    @DisplayName("a null goal or a zero limit is handled without throwing")
    void defensiveInputs() {
        assertEquals(null, NavReach.clamp(START, null, 100));
        BlockPos3 goal = new BlockPos3(500, 64, 0);
        // With no start there is nothing to measure from, so the goal is returned untouched and the
        // caller's own checks decide what to do with it.
        assertEquals(new BlockPos3(1, 1, 1), NavReach.clamp(null, new BlockPos3(1, 1, 1), 100));
        assertEquals(goal, NavReach.clamp(START, goal, 0),
            "a non-positive limit means 'no limit', not 'stay put'");
        assertFalse(NavReach.inReach(null, goal, 100));
    }
}
