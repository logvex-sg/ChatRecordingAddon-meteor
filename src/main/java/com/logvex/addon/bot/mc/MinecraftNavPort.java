package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.NavPort;
import com.logvex.addon.bot.core.NavReach;
import com.logvex.addon.bot.core.PathSmoother;
import com.logvex.addon.bot.core.Pathfinder;
import com.logvex.addon.bot.core.WaypointFollower;
import meteordevelopment.meteorclient.pathing.IPathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.utils.player.CustomPlayerInput;
import net.minecraft.client.input.Input;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static com.logvex.addon.framework.LogClientContext.mc;

/**
 * {@link NavPort} that prefers a real path manager and otherwise plans its own route.
 *
 * <p>If Baritone or Voyager is installed, their path manager is used. Otherwise this class plans a
 * path with {@link Pathfinder} over a bounded {@link MinecraftNavGrid}, smooths it with
 * {@link PathSmoother} and walks it with {@link WaypointFollower}.
 *
 * <p>The earlier fallback simply faced the target and held forward, which stalled against any wall
 * between the bot and its goal. Planning a route is what makes the bot usable on real terrain; the
 * walker still reports failure the moment it stops making progress, so an impossible route ends
 * instead of pushing into a wall forever.
 */
public final class MinecraftNavPort implements NavPort {
    /** How many ticks without progress before the walker gives up. */
    private static final int STUCK_TICKS = 40;
    /** How many ticks of no progress before a jump is attempted. */
    private static final int JUMP_AFTER_TICKS = 8;
    /** How many cells the planner may expand before it gives up. */
    private static final int SEARCH_BUDGET = 60_000;

    private BlockPos3 target;
    private boolean active;
    private String failure = "";
    private boolean unreachable;
    private double maxDistance = 256;

    private CustomPlayerInput input;
    private Input previousInput;
    private double bestDistance = Double.MAX_VALUE;
    private boolean usingPathManager;

    private WaypointFollower follower;
    /** The backend actually driving this trip, for the dashboard. */
    private String backend = "built-in walker";

    public void setMaxDistance(double value) {
        this.maxDistance = Math.max(8, value);
    }

    @Override
    public double[] position() {
        if (mc.player == null) return new double[]{0, 0, 0};
        return new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()};
    }

    @Override
    public boolean start(BlockPos3 target) {
        if (mc.player == null || mc.world == null || target == null) return false;
        this.target = target;
        this.active = true;
        this.failure = "";
        this.unreachable = false;
        this.bestDistance = Double.MAX_VALUE;
        this.follower = null;

        IPathManager manager = PathManagers.get();
        usingPathManager = manager != null && !"none".equals(manager.getName());
        if (usingPathManager) {
            backend = manager.getName();
            manager.moveTo(new BlockPos(target.x(), target.y(), target.z()));
            return true;
        }
        backend = "built-in A*";
        return startWalker(target);
    }

    /**
     * Plans a route to {@code target} and starts following it.
     *
     * <p>A planning failure is reported rather than papered over: the caller needs to know the route
     * is impossible so it can pick another task, and silently walking in a straight line would look
     * like progress while achieving nothing.
     */
    private boolean startWalker(BlockPos3 target) {
        if (mc.player == null) return false;
        BlockPos3 start = new BlockPos3(
            (int) Math.floor(mc.player.getX()),
            (int) Math.floor(mc.player.getY()),
            (int) Math.floor(mc.player.getZ()));

        // A goal past the planner's reach is walked in stages: plan to the edge of the box in the
        // goal's direction, and re-plan once the bot gets there. Failing outright would make a long
        // trip impossible, which is worse than taking it in two hops.
        BlockPos3 goal = NavReach.clamp(start, target, MinecraftNavGrid.MAX_HORIZONTAL - 8);

        MinecraftNavGrid grid = MinecraftNavGrid.aroundPlayer(goal);
        if (grid == null) {
            failure = "The world is not loaded.";
            unreachable = true;
            stop();
            return false;
        }

        Pathfinder.Result result = new Pathfinder(SEARCH_BUDGET, 1).find(grid, start, goal);
        if (!result.found()) {
            failure = describeFailure(result.failure());
            unreachable = result.failure() == Pathfinder.Failure.NO_ROUTE
                || result.failure() == Pathfinder.Failure.OUT_OF_BOUNDS;
            stop();
            return false;
        }

        List<BlockPos3> path = PathSmoother.smooth(grid, start, result.path());
        if (path.isEmpty()) {
            // Already at the goal: nothing to walk, which is a success rather than a failure.
            active = false;
            return true;
        }
        follower = new WaypointFollower(path);

        previousInput = mc.player.input;
        input = new CustomPlayerInput();
        mc.player.input = input;
        return true;
    }

    private static String describeFailure(Pathfinder.Failure reason) {
        return switch (reason) {
            case OUT_OF_BOUNDS -> "The target is outside the area the bot can see.";
            case BAD_START -> "The bot cannot stand where it is.";
            case NO_ROUTE -> "No walkable route to the target.";
            case BUDGET_EXHAUSTED -> "The route search was too large; the target is probably too far.";
            case NONE -> "";
        };
    }

    @Override
    public void tick(long nowMs) {
        if (!active || mc.player == null) return;

        if (usingPathManager) {
            IPathManager manager = PathManagers.get();
            if (manager == null || !manager.isPathing()) {
                // A path manager that stopped without arriving means the route failed.
                if (target != null && !arrived(target, 1.5)) {
                    failure = "The path could not be completed.";
                    unreachable = true;
                    stop();
                }
            }
            return;
        }

        if (target == null) return;

        if (follower == null) {
            stop();
            return;
        }

        double[] pos = position();
        // Reaching a waypoint is horizontal, because a player walking over uneven ground is rarely
        // at the exact centre height the planner used.
        boolean reachedWaypoint = follower.advanceIfArrived(pos[0], pos[2]);
        if (follower.done()) {
            stop();
            return;
        }

        // Progress is measured against the waypoint being walked to, not the final target. A detour
        // around a large wall temporarily walks away from the target, and measuring against the
        // target would read that legitimate detour as being stuck.
        BlockPos3 waypoint = follower.current();
        double distance = Math.sqrt(Math.pow(pos[0] - waypoint.x(), 2)
            + Math.pow(pos[1] - waypoint.y(), 2) + Math.pow(pos[2] - waypoint.z(), 2));

        // A fresh waypoint resets the yardstick, or the first tick after a turn would compare a new
        // distance against the previous waypoint's and read as a stall.
        if (reachedWaypoint) bestDistance = Double.MAX_VALUE;
        boolean progressed = reachedWaypoint || distance < bestDistance - 0.15;
        if (distance < bestDistance) bestDistance = distance;

        if (follower.noteProgress(progressed, STUCK_TICKS)) {
            failure = "No progress toward the target; the route is blocked.";
            unreachable = true;
            stop();
            return;
        }

        mc.player.setYaw(follower.desiredYaw(pos[0], pos[2]));

        if (input == null) return;
        input.pressingForward = true;
        // Jump for a waypoint above the feet, or when a level waypoint will not budge — a lip of
        // terrain still needs a hop even though the planner saw flat ground.
        input.jumping = follower.shouldJump(pos[1]) || follower.stalledTicks() >= JUMP_AFTER_TICKS;
        input.sneaking = false;
        input.pressingBack = false;
        input.pressingLeft = false;
        input.pressingRight = false;
    }

    @Override
    public boolean running() {
        return active;
    }

    /** The target currently being navigated to, or {@code null} when navigation is not running. */
    @Override
    public BlockPos3 target() {
        return active ? target : null;
    }

    @Override
    public void stop() {
        if (usingPathManager) {
            IPathManager manager = PathManagers.get();
            if (manager != null) manager.stop();
        } else if (input != null && mc.player != null) {
            input.stop();
            mc.player.input = previousInput;
            input = null;
            previousInput = null;
        }
        follower = null;
        active = false;
    }

    @Override
    public boolean arrived(BlockPos3 target, double tolerance) {
        if (target == null || mc.player == null) return false;
        double[] pos = position();
        double dx = pos[0] - (target.x() + 0.5);
        double dz = pos[2] - (target.z() + 0.5);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal <= tolerance;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public boolean unreachable() {
        return unreachable;
    }

    @Override
    public double maxDistance() {
        return maxDistance;
    }

    /** True when a real path manager (not the built-in walker) is driving navigation. */
    public boolean usingPathManager() {
        return usingPathManager;
    }

    /** The name of the active navigation backend, for the dashboard. */
    @Override
    public String backendName() {
        if (usingPathManager) {
            IPathManager manager = PathManagers.get();
            return manager == null ? "none" : manager.getName();
        }
        return backend;
    }

    /**
     * How many waypoints are left on the planned route, or {@code -1} when no route is being walked.
     * The dashboard shows this so a long detour is visible rather than looking like a stuck bot.
     */
    public int remainingWaypoints() {
        return follower == null ? -1 : follower.remaining();
    }

    /** The planned route being walked, for a HUD or a debug overlay. Empty when none. */
    public List<BlockPos3> plannedPath() {
        return follower == null ? List.of() : follower.waypoints();
    }

    /**
     * The path manager that is active. {@code "none"} means neither Baritone nor Voyager is
     * installed and the built-in walker is being used.
     */
    public static String activeManagerName() {
        IPathManager manager = PathManagers.get();
        return manager == null ? "none" : manager.getName();
    }

    /** True when a real path manager (Baritone or Voyager) is available to drive navigation. */
    public static boolean pathManagerAvailable() {
        return !"none".equals(activeManagerName());
    }

    /**
     * Applies the path-manager settings. Baritone's walk-on-water/lava/step/no-fall options are
     * surfaced as module settings, so the bot should respect the user's configuration instead of
     * forcing its own. No-ops when no path manager is installed.
     */
    public static void applyPathSettings(boolean walkOnWater, boolean walkOnLava, boolean step, boolean noFall) {
        IPathManager manager = PathManagers.get();
        if (manager == null || "none".equals(manager.getName())) return;
        IPathManager.ISettings settings = manager.getSettings();
        if (settings == null) return;
        set(settings.getWalkOnWater(), walkOnWater);
        set(settings.getWalkOnLava(), walkOnLava);
        set(settings.getStep(), step);
        set(settings.getNoFall(), noFall);
        settings.save();
    }

    private static <T> void set(meteordevelopment.meteorclient.settings.Setting<T> setting, T value) {
        if (setting != null && value != null) setting.set(value);
    }
}
