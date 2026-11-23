package com.logvex.addon.bot.core;

import com.logvex.addon.bot.core.NavRules.Step;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over a {@link NavGrid}.
 *
 * <p>The search is deliberately bounded in three ways, because it runs on the game thread:
 * <ul>
 *   <li>a node budget ({@code maxExpansions}), so a pathological world cannot stall a frame;</li>
 *   <li>a goal radius, so "get near enough" does not require reaching an exact block;</li>
 *   <li>a refusal to start when the goal lies outside the grid, which is the usual reason a plan
 *       is impossible and is worth reporting without searching at all.</li>
 * </ul>
 *
 * <p>Costs are integers in tenths of a block (see {@link NavRules}), so the result is exactly
 * reproducible: the same world and the same goal always produce the same path, which is what makes
 * it testable and what stops the bot from jittering between two equal-cost routes.
 */
public final class Pathfinder {

    /** Why a search produced no path. */
    public enum Failure {
        /** A usable path was found. */
        NONE,
        /** The goal is outside the grid, so nothing can be known about it. */
        OUT_OF_BOUNDS,
        /** The start cell is not standable (mid-air, in a wall, in liquid). */
        BAD_START,
        /** No route exists within the movement rules. */
        NO_ROUTE,
        /** The node budget ran out before the search finished. */
        BUDGET_EXHAUSTED
    }

    /** The outcome of a search: a path, or the reason there is none. */
    public record Result(List<BlockPos3> path, int cost, int expanded, Failure failure) {
        public Result {
            path = path == null ? List.of() : List.copyOf(path);
        }

        public boolean found() {
            return failure == Failure.NONE && !path.isEmpty();
        }

        /** The next cell to walk to, or {@code null} when there is no path. */
        public BlockPos3 next() {
            return found() ? path.get(0) : null;
        }
    }

    private static final Result NO_START = new Result(List.of(), 0, 0, Failure.BAD_START);

    private final int maxExpansions;
    private final int goalRadius;

    /** A default planner: 40k expansions is a few milliseconds and covers a 64-block trip. */
    public Pathfinder() {
        this(40_000, 0);
    }

    /**
     * @param maxExpansions the most cells the search may expand before giving up
     * @param goalRadius    how close to the goal counts as arrival, in blocks (0 = exact)
     */
    public Pathfinder(int maxExpansions, int goalRadius) {
        this.maxExpansions = Math.max(1, maxExpansions);
        this.goalRadius = Math.max(0, goalRadius);
    }

    /**
     * Finds a path from {@code start} to {@code goal}.
     *
     * <p>The returned path <b>excludes</b> the start cell and includes the goal cell, so a caller can
     * walk it directly. It is empty when the start is already within the goal radius.
     */
    public Result find(NavGrid grid, BlockPos3 start, BlockPos3 goal) {
        if (grid == null || start == null || goal == null) {
            return new Result(List.of(), 0, 0, Failure.OUT_OF_BOUNDS);
        }
        if (!grid.contains(start) || !grid.contains(goal)) {
            return new Result(List.of(), 0, 0, Failure.OUT_OF_BOUNDS);
        }
        if (!NavRules.standable(grid, start)) return NO_START;
        if (withinGoal(start, goal)) return new Result(List.of(), 0, 0, Failure.NONE);

        Map<BlockPos3, Node> best = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>(
            (a, b) -> a.f != b.f ? Integer.compare(a.f, b.f) : a.tie - b.tie);

        Node first = new Node(start, null, 0, NavRules.heuristic(start, goal));
        best.put(start, first);
        open.add(first);

        int expanded = 0;
        int tie = 0;
        while (!open.isEmpty()) {
            Node current = open.poll();
            // A stale queue entry: a cheaper route to this cell was already expanded.
            Node known = best.get(current.pos);
            if (known != null && current.g > known.g) continue;

            if (withinGoal(current.pos, goal)) {
                return new Result(reconstruct(current), current.g, expanded, Failure.NONE);
            }
            if (expanded >= maxExpansions) {
                return new Result(List.of(), 0, expanded, Failure.BUDGET_EXHAUSTED);
            }
            expanded++;

            for (int dir = 0; dir < NavRules.directionCount(); dir++) {
                Step step = NavRules.step(grid, current.pos, dir);
                if (step == null) continue;
                BlockPos3 next = step.to();
                int g = current.g + step.cost();
                Node seen = best.get(next);
                if (seen != null && seen.g <= g) continue;
                Node node = new Node(next, current, g, g + NavRules.heuristic(next, goal));
                node.tie = tie++;
                best.put(next, node);
                open.add(node);
            }
        }
        return new Result(List.of(), 0, expanded, Failure.NO_ROUTE);
    }

    private boolean withinGoal(BlockPos3 pos, BlockPos3 goal) {
        if (goalRadius == 0) return pos.equals(goal);
        int dx = Math.abs(pos.x() - goal.x());
        int dy = Math.abs(pos.y() - goal.y());
        int dz = Math.abs(pos.z() - goal.z());
        return Math.max(dx, Math.max(dy, dz)) <= goalRadius;
    }

    private static List<BlockPos3> reconstruct(Node node) {
        List<BlockPos3> path = new ArrayList<>();
        for (Node n = node; n != null && n.parent != null; n = n.parent) path.add(n.pos);
        Collections.reverse(path);
        return path;
    }

    /** A search node. {@code g} is the cost so far, {@code f} the estimate through this cell. */
    private static final class Node {
        final BlockPos3 pos;
        final Node parent;
        final int g;
        final int f;
        int tie;

        Node(BlockPos3 pos, Node parent, int g, int f) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }
}
