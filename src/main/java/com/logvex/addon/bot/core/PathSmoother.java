package com.logvex.addon.bot.core;

import com.logvex.addon.bot.core.NavRules.Step;

import java.util.ArrayList;
import java.util.List;

/**
 * Shortens a raw A* path into the fewest waypoints that are still walkable.
 *
 * <p>A* on a grid produces a staircase: it steps diagonally, then straight, then diagonally again,
 * which is correct but makes the bot twitch. String-pulling fixes that by skipping every waypoint
 * that can be reached in a straight line from the current one, so the bot walks long straight runs
 * and only turns where the world actually forces it.
 *
 * <p>Safety is not traded for smoothness: every candidate shortcut is verified by replaying the
 * move rules along the line, so a shortcut is only taken when a player could really walk it. A
 * path that would clip a corner or require a two-block jump is never shortened.
 */
public final class PathSmoother {

    private PathSmoother() {
    }

    /**
     * Removes redundant waypoints from {@code path}.
     *
     * @param grid the world the path was planned in
     * @param from the cell the walk starts at (the planner's start, not part of the path)
     * @param path the planned path, start excluded
     * @return a shorter path with the same endpoints, safe to walk
     */
    public static List<BlockPos3> smooth(NavGrid grid, BlockPos3 from, List<BlockPos3> path) {
        if (grid == null || from == null || path == null || path.size() < 2) {
            return path == null ? List.of() : List.copyOf(path);
        }
        List<BlockPos3> out = new ArrayList<>();
        BlockPos3 anchor = from;
        int index = 0;
        while (index < path.size()) {
            // Reach as far ahead as a single straight run allows, then commit that waypoint.
            int farthest = index;
            for (int candidate = path.size() - 1; candidate > index; candidate--) {
                if (walkableLine(grid, anchor, path.get(candidate))) {
                    farthest = candidate;
                    break;
                }
            }
            BlockPos3 next = path.get(farthest);
            out.add(next);
            anchor = next;
            index = farthest + 1;
        }
        return out;
    }

    /**
     * True when a player can walk from {@code a} to {@code b} without turning.
     *
     * <p>Only same-height and single-step runs are considered, because a straight line that changes
     * height more than once is not straight any more — the player would have to jump mid-run, which
     * is exactly the kind of move that fails against a real wall.
     */
    public static boolean walkableLine(NavGrid grid, BlockPos3 a, BlockPos3 b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        int dx = Integer.signum(b.x() - a.x());
        int dz = Integer.signum(b.z() - a.z());
        int dy = b.y() - a.y();
        if (Math.abs(dy) > NavRules.MAX_FALL) return false;

        BlockPos3 cursor = a;
        while (!cursor.equals(b)) {
            int stepX = Integer.compare(b.x(), cursor.x());
            int stepZ = Integer.compare(b.z(), cursor.z());
            // A pure diagonal run must not corner-cut at any point along the way.
            if (stepX != 0 && stepZ != 0
                && !NavRules.standable(grid, new BlockPos3(cursor.x() + stepX, cursor.y(), cursor.z()))
                && !NavRules.standable(grid, new BlockPos3(cursor.x(), cursor.y(), cursor.z() + stepZ))) {
                return false;
            }
            BlockPos3 next = new BlockPos3(cursor.x() + stepX, cursor.y(), cursor.z() + stepZ);
            if (next.y() != b.y()) next = new BlockPos3(next.x(), b.y(), next.z());
            if (!NavRules.standable(grid, next)) return false;
            cursor = next;
        }
        return true;
    }
}
