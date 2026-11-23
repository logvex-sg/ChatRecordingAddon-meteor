package com.logvex.addon.bot.core;

/**
 * Turns a long trip into a series of reachable ones.
 *
 * <p>The planner searches a bounded box, so a goal outside that box cannot be planned in one go.
 * Rather than refuse the trip, the destination is pulled back to the edge of the reachable area and
 * the caller re-plans from wherever it ends up. The bot then walks the trip in stages, which is how
 * a person crosses a long distance too: aim for the next landmark, not the horizon.
 *
 * <p>Kept here, apart from the client, so the arithmetic that decides "how far can I aim" is tested
 * directly instead of being trusted.
 */
public final class NavReach {

    private NavReach() {
    }

    /**
     * Pulls {@code goal} back toward {@code start} until it is no more than {@code maxSpan} blocks
     * away on the longer horizontal axis.
     *
     * <p>The height is kept, so a staged goal stays on the surface the caller asked for rather than
     * dropping to the player's feet.
     *
     * @return {@code goal} itself when it is already within reach, otherwise a nearer point on the
     *     same heading
     */
    public static BlockPos3 clamp(BlockPos3 start, BlockPos3 goal, int maxSpan) {
        if (start == null || goal == null) return goal;
        if (maxSpan <= 0) return goal;
        int dx = goal.x() - start.x();
        int dz = goal.z() - start.z();
        int span = Math.max(Math.abs(dx), Math.abs(dz));
        if (span <= maxSpan) return goal;
        double scale = maxSpan / (double) span;
        return new BlockPos3(
            start.x() + (int) Math.round(dx * scale),
            goal.y(),
            start.z() + (int) Math.round(dz * scale));
    }

    /** True when {@code goal} is near enough to plan in a single search. */
    public static boolean inReach(BlockPos3 start, BlockPos3 goal, int maxSpan) {
        if (start == null || goal == null) return false;
        return Math.max(Math.abs(goal.x() - start.x()), Math.abs(goal.z() - start.z())) <= maxSpan;
    }
}
