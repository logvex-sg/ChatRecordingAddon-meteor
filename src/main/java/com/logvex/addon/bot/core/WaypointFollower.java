package com.logvex.addon.bot.core;

import java.util.List;

/**
 * Walks a planned path one waypoint at a time, deciding only what a player's hands would do: which
 * way to face and when to jump.
 *
 * <p>Kept Minecraft-free so the steering can be tested as arithmetic. The caller feeds in the real
 * position each tick and applies the answer to the player input; this class never touches a player.
 *
 * <p>Arrival is measured horizontally, because a player walking over uneven ground is rarely at the
 * exact height of the cell centre. A waypoint that is above the player asks for a jump — that is the
 * only way a walker climbs, and it is what the planner assumed when it allowed the climb.
 */
public final class WaypointFollower {

    /** How close, horizontally, the player must be before the next waypoint is targeted. */
    public static final double ARRIVE_RADIUS = 0.7;

    private final List<BlockPos3> waypoints;
    private int index;
    private int stalledTicks;

    public WaypointFollower(List<BlockPos3> waypoints) {
        this.waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }

    /** The waypoint being walked toward, or {@code null} when the path is finished. */
    public BlockPos3 current() {
        return index < waypoints.size() ? waypoints.get(index) : null;
    }

    /** The waypoint after the current one, or {@code null} at the end. */
    public BlockPos3 next() {
        return index + 1 < waypoints.size() ? waypoints.get(index + 1) : null;
    }

    /** True when every waypoint has been reached. */
    public boolean done() {
        return index >= waypoints.size();
    }

    /** How many waypoints remain, including the current one. */
    public int remaining() {
        return Math.max(0, waypoints.size() - index);
    }

    /** The waypoint index being walked toward, for the HUD. */
    public int index() {
        return index;
    }

    /** The whole path, for a caller that wants to draw it. */
    public List<BlockPos3> waypoints() {
        return waypoints;
    }

    /**
     * Advances past every waypoint the player has already reached.
     *
     * @return true when at least one waypoint was consumed
     */
    public boolean advanceIfArrived(double x, double z) {
        boolean advanced = false;
        while (!done()) {
            BlockPos3 target = current();
            double dx = x - (target.x() + 0.5);
            double dz = z - (target.z() + 0.5);
            if (Math.sqrt(dx * dx + dz * dz) > ARRIVE_RADIUS) break;
            index++;
            advanced = true;
        }
        return advanced;
    }

    /**
     * The yaw, in degrees, that faces the current waypoint.
     *
     * <p>Minecraft's yaw runs clockwise from south, and {@code atan2(-dx, dz)} is the convention the
     * rest of this addon already uses, so a path and a manual heading agree.
     */
    public float desiredYaw(double x, double z) {
        BlockPos3 target = current();
        if (target == null) return 0f;
        double dx = target.x() + 0.5 - x;
        double dz = target.z() + 0.5 - z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * True when the player should jump to reach the current waypoint.
     *
     * <p>Only a waypoint above the feet is worth jumping for; jumping at a level waypoint just wastes
     * hunger, and the planner never asks for a climb it did not check.
     */
    public boolean shouldJump(double y) {
        BlockPos3 target = current();
        if (target == null) return false;
        return target.y() > Math.floor(y) + 0.05;
    }

    /**
     * Records a tick that made no progress, and reports whether the follower has been stuck long
     * enough to give up.
     *
     * <p>The caller decides what "progress" means; this only counts. A follower that never gives up
     * is how a bot walks into a wall forever.
     */
    public boolean noteProgress(boolean madeProgress, int stuckLimit) {
        if (madeProgress) {
            stalledTicks = 0;
            return false;
        }
        stalledTicks++;
        return stalledTicks >= stuckLimit;
    }

    /** How many consecutive ticks have made no progress. */
    public int stalledTicks() {
        return stalledTicks;
    }
}
