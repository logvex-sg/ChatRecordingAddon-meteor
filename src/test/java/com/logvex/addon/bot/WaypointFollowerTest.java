package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.WaypointFollower;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walking a planned path.
 *
 * <p>The follower is the half of navigation that turns waypoints into hands: which way to face, when
 * to jump, and when to admit the walk is not working. Testing it apart from the game keeps the
 * steering honest without needing a client.
 */
class WaypointFollowerTest {

    private static BlockPos3 at(int x, int y, int z) {
        return new BlockPos3(x, y, z);
    }

    @Test
    @DisplayName("a path is walked in order and finishes")
    void walksInOrder() {
        WaypointFollower f = new WaypointFollower(List.of(at(1, 64, 0), at(2, 64, 0)));
        assertEquals(at(1, 64, 0), f.current());
        assertFalse(f.done());

        // Standing at the centre of the first waypoint means it is reached.
        assertTrue(f.advanceIfArrived(1.5, 0.5));
        assertEquals(at(2, 64, 0), f.current());
        assertFalse(f.done());

        f.advanceIfArrived(2.5, 0.5);
        assertTrue(f.done());
        assertNull(f.current());
    }

    @Test
    @DisplayName("a waypoint is only consumed once the player is actually close")
    void needsToBeClose() {
        WaypointFollower f = new WaypointFollower(List.of(at(5, 64, 0)));
        assertFalse(f.advanceIfArrived(0.5, 0.5), "5 blocks away is not arrival");
        assertFalse(f.done());
        assertTrue(f.advanceIfArrived(5.5, 0.5));
    }

    @Test
    @DisplayName("several waypoints passed in one tick are all consumed")
    void consumesMultiple() {
        // A fast player can cross more than one waypoint between ticks; leaving them queued would
        // make the bot walk backwards to collect them. Walk through each in turn to show the loop
        // consumes every waypoint that is behind the player, not just the current one.
        WaypointFollower f = new WaypointFollower(List.of(at(0, 64, 0), at(1, 64, 0), at(2, 64, 0)));
        assertTrue(f.advanceIfArrived(0.5, 0.5));
        assertEquals(2, f.remaining());
        assertTrue(f.advanceIfArrived(1.5, 0.5));
        assertEquals(1, f.remaining());
        f.advanceIfArrived(2.5, 0.5);
        assertTrue(f.done());
        assertEquals(0, f.remaining());
    }

    @Test
    @DisplayName("the follower never skips ahead past a waypoint it has not reached")
    void neverSkipsAhead() {
        // Standing near the end of a path must not consume the waypoints before the current one:
        // the bot has to walk the route in order, or it would cut across whatever the planner routed
        // around.
        WaypointFollower f = new WaypointFollower(List.of(at(0, 64, 0), at(0, 64, 1), at(0, 64, 2)));
        assertFalse(f.advanceIfArrived(0.5, 2.5),
            "waypoint 0 is 2 blocks away, so nothing may be consumed");
        assertEquals(3, f.remaining());
        assertEquals(at(0, 64, 0), f.current());
    }

    @Test
    @DisplayName("the desired yaw faces the current waypoint")
    void yawFacesTheWaypoint() {
        WaypointFollower f = new WaypointFollower(List.of(at(10, 64, 0)));
        // Minecraft yaw: 0 is south (+z), -90 is east (+x).
        assertEquals(-90f, f.desiredYaw(0.5, 0.5), 0.001f,
            "a waypoint to the east must face east");
        WaypointFollower north = new WaypointFollower(List.of(at(0, 64, -10)));
        assertEquals(180f, Math.abs(north.desiredYaw(0.5, 0.5)), 0.001f,
            "a waypoint to the north must face north");
    }

    @Test
    @DisplayName("a waypoint above the feet asks for a jump, a level one does not")
    void jumpsOnlyForClimbs() {
        WaypointFollower up = new WaypointFollower(List.of(at(1, 65, 0)));
        assertTrue(up.shouldJump(64.0), "a waypoint one block up needs a jump");

        WaypointFollower level = new WaypointFollower(List.of(at(1, 64, 0)));
        assertFalse(level.shouldJump(64.0), "a level waypoint must not waste hunger on jumps");

        WaypointFollower down = new WaypointFollower(List.of(at(1, 63, 0)));
        assertFalse(down.shouldJump(64.0), "dropping down must not jump");
    }

    @Test
    @DisplayName("progress resets the stall counter and a stall eventually gives up")
    void stallDetection() {
        WaypointFollower f = new WaypointFollower(List.of(at(10, 64, 0)));
        assertFalse(f.noteProgress(false, 3));
        assertFalse(f.noteProgress(false, 3));
        assertEquals(2, f.stalledTicks());
        // Progress clears the count, so a slow but moving walk never trips the limit.
        assertFalse(f.noteProgress(true, 3));
        assertEquals(0, f.stalledTicks());
        assertFalse(f.noteProgress(false, 3));
        assertFalse(f.noteProgress(false, 3));
        assertTrue(f.noteProgress(false, 3), "the third stalled tick must report giving up");
    }

    @Test
    @DisplayName("an empty path is finished immediately")
    void emptyPath() {
        WaypointFollower f = new WaypointFollower(List.of());
        assertTrue(f.done());
        assertNull(f.current());
        assertFalse(f.advanceIfArrived(0, 0));
    }

    @Test
    @DisplayName("the follower does not mutate the path it was given")
    void doesNotMutateInput() {
        List<BlockPos3> path = new java.util.ArrayList<>(List.of(at(1, 64, 0)));
        WaypointFollower f = new WaypointFollower(path);
        f.advanceIfArrived(1.5, 0.5);
        assertEquals(1, path.size(), "the caller's list must not be modified");
    }
}
