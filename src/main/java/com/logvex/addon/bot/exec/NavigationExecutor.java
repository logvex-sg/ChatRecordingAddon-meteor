package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.NavPort;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

import java.util.Optional;

/**
 * Walks the player to a target position.
 *
 * <p>The target is read from the task's {@code x}/{@code y}/{@code z} parameters, or from a
 * waypoint of the task's item id in {@link com.logvex.addon.bot.core.BotMemory}. Success is only
 * reported once the player is actually within tolerance, so a navigation that never started cannot
 * be mistaken for an arrival.
 */
public final class NavigationExecutor implements TaskExecutor {
    @Override
    public TaskType type() {
        return TaskType.NAVIGATION;
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        NavPort nav = context.nav();
        BlockPos3 target = target(task, context);
        if (target == null) return TaskOutcome.FAILED;

        if (nav.arrived(target, context.config().navigationTolerance())) {
            nav.stop();
            return TaskOutcome.DONE;
        }
        if (nav.unreachable()) {
            nav.stop();
            return TaskOutcome.FAILED;
        }
        if (!nav.running()) {
            double distance = distance(nav.position(), target);
            if (distance > nav.maxDistance()) {
                return TaskOutcome.FAILED;
            }
            if (!nav.start(target)) return TaskOutcome.RETRY;
        }
        nav.tick(context.clock().now());
        return TaskOutcome.CONTINUE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        context.nav().stop();
    }

    @Override
    public String status(BotTask task) {
        return "Navigating to " + task.parameter("x", "?") + ", " + task.parameter("y", "?")
            + ", " + task.parameter("z", "?");
    }

    private static BlockPos3 target(BotTask task, TaskContext context) {
        String x = task.parameter("x", "");
        if (!x.isEmpty()) {
            try {
                return new BlockPos3(Integer.parseInt(x),
                    Integer.parseInt(task.parameter("y", "0")),
                    Integer.parseInt(task.parameter("z", "0")));
            } catch (NumberFormatException ignored) {
                // Fall through to a named waypoint.
            }
        }
        if (context.memory() != null && !task.itemId().isEmpty()) {
            Optional<com.logvex.addon.bot.core.BotMemory.Waypoint> waypoint =
                context.memory().waypoint(task.itemId());
            if (waypoint.isPresent()) {
                var w = waypoint.get();
                return new BlockPos3(w.x(), w.y(), w.z());
            }
        }
        return null;
    }

    private static double distance(double[] from, BlockPos3 to) {
        if (from == null) return Double.MAX_VALUE;
        double dx = from[0] - to.x();
        double dy = from[1] - to.y();
        double dz = from[2] - to.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
