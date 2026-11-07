package com.logvex.addon.bot.core;

/**
 * Navigation. The contract is deliberately narrow: start moving toward a target and report whether
 * the player is actually getting closer. The bot must never assume a movement command succeeded —
 * it checks {@link #position()} and re-plans when the player stops making progress.
 */
public interface NavPort {
    /** The player's current exact position as {@code [x, y, z]}. */
    double[] position();

    /**
     * Begins navigating to {@code target}. Returns {@code false} when navigation cannot even start
     * (no world, target unreachable, no path manager available).
     */
    boolean start(BlockPos3 target);

    /** Advances navigation by one tick. */
    void tick(long nowMs);

    /** True while a navigation is in progress. */
    boolean running();

    /** Stops any navigation in progress. */
    void stop();

    /**
     * The position currently being navigated to, or {@code null} when no navigation is in progress.
     * Used by the dashboard and HUD to show the destination and the remaining distance; it is never
     * used to decide success, which always comes from {@link #arrived}.
     */
    default BlockPos3 target() {
        return null;
    }

    /** True when the player is within {@code tolerance} blocks of the target. */
    boolean arrived(BlockPos3 target, double tolerance);

    /** A human-readable reason the current navigation failed, or empty. */
    String failureReason();

    /** True when the target was judged unreachable during planning. */
    boolean unreachable();

    /** How far the player can travel in the current world before the bot gives up. */
    double maxDistance();

    /**
     * The navigation backend in use, for display. Baritone (or Voyager) is detected at runtime and
     * path-manager abstraction; when neither is installed this reports the built-in walker.
     */
    default String backendName() {
        return "unknown";
    }
}
