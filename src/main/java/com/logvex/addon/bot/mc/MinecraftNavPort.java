package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.NavPort;
import com.logvex.addon.framework.pathing.IPathManager;
import com.logvex.addon.framework.pathing.PathManagers;
import com.logvex.addon.framework.utils.player.CustomPlayerInput;
import net.minecraft.client.input.Input;
import net.minecraft.util.math.BlockPos;

import static com.logvex.addon.framework.MeteorClient.mc;

/**
 * {@link NavPort} that prefers a real path manager and otherwise falls back to a bounded walker.
 *
 * <p>If Baritone or Voyager is installed, their path manager is used. Otherwise the bot walks toward
 * the target using the same {@link CustomPlayerInput} override Meteor's own modules use. The walker
 * is deliberately conservative: it stops and reports failure when it stops making progress, so a
 * blocked or impossible route ends the navigation instead of walking into a wall forever.
 */
public final class MinecraftNavPort implements NavPort {
    /** How many ticks without progress before the walker gives up. */
    private static final int STUCK_TICKS = 40;
    /** How many ticks of no progress before a jump is attempted. */
    private static final int JUMP_AFTER_TICKS = 8;

    private BlockPos3 target;
    private boolean active;
    private String failure = "";
    private boolean unreachable;
    private double maxDistance = 256;

    private CustomPlayerInput input;
    private Input previousInput;
    private double bestDistance = Double.MAX_VALUE;
    private int stuckTicks;
    private int noProgressTicks;
    private boolean usingPathManager;

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
        this.stuckTicks = 0;
        this.noProgressTicks = 0;

        IPathManager manager = PathManagers.get();
        usingPathManager = manager != null && !"none".equals(manager.getName());
        if (usingPathManager) {
            manager.moveTo(new BlockPos(target.x(), target.y(), target.z()));
            return true;
        }
        return startWalker();
    }

    private boolean startWalker() {
        if (mc.player == null) return false;
        previousInput = mc.player.input;
        input = new CustomPlayerInput();
        mc.player.input = input;
        return true;
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

        double[] pos = position();
        double distance = Math.sqrt(Math.pow(pos[0] - target.x(), 2) + Math.pow(pos[1] - target.y(), 2)
            + Math.pow(pos[2] - target.z(), 2));

        if (distance < bestDistance - 0.15) {
            bestDistance = distance;
            stuckTicks = 0;
            noProgressTicks = 0;
        } else {
            stuckTicks++;
            noProgressTicks++;
        }

        if (stuckTicks >= STUCK_TICKS) {
            failure = "No progress toward the target; the route is blocked.";
            unreachable = true;
            stop();
            return;
        }

        double dx = target.x() + 0.5 - pos[0];
        double dz = target.z() + 0.5 - pos[2];
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setYaw(yaw);

        if (input == null) return;
        input.pressingForward = true;
        input.jumping = noProgressTicks >= JUMP_AFTER_TICKS;
        input.sneaking = false;
        input.pressingBack = false;
        input.pressingLeft = false;
        input.pressingRight = false;
    }

    @Override
    public boolean running() {
        return active;
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
        return "built-in walker";
    }

    /**
     * The path manager Meteor selected. {@code "none"} means neither Baritone nor Voyager is
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
     * Applies Meteor's path-manager settings. Baritone's walk-on-water/lava/step/no-fall options are
     * surfaced as Meteor settings, so the bot should respect the user's configuration instead of
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

    private static <T> void set(com.logvex.addon.framework.settings.Setting<T> setting, T value) {
        if (setting != null && value != null) setting.set(value);
    }
}
