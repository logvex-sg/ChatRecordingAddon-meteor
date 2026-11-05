package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Walks to a distant point in the current direction to reveal terrain.
 *
 * <p>Exploration is bounded: the target is one step of the configured travel distance away, and the
 * task is considered complete once the player reaches it. That keeps exploration from wandering
 * forever and makes it interruptible like any other task.
 */
public final class ExplorationExecutor implements TaskExecutor {
    @Override
    public TaskType type() {
        return TaskType.EXPLORATION;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        double[] pos = context.nav().position();
        if (pos == null) return;
        double distance = Math.min(64.0, context.config().maxTravelDistance());
        // Step roughly north-east so repeated explorations fan out rather than retrace.
        int dx = (int) Math.round(distance * 0.7);
        int dz = (int) Math.round(distance * 0.7);
        BlockPos3 target = new BlockPos3((int) Math.floor(pos[0]) + dx, (int) Math.floor(pos[1]),
            (int) Math.floor(pos[2]) + dz);
        task.setParameter("x", Integer.toString(target.x()));
        task.setParameter("y", Integer.toString(target.y()));
        task.setParameter("z", Integer.toString(target.z()));
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        if (!context.world().canRun()) return TaskOutcome.RETRY;
        String x = task.parameter("x", "");
        if (x.isEmpty()) return TaskOutcome.FAILED;
        BlockPos3 target;
        try {
            target = new BlockPos3(Integer.parseInt(x), Integer.parseInt(task.parameter("y", "0")),
                Integer.parseInt(task.parameter("z", "0")));
        } catch (NumberFormatException e) {
            return TaskOutcome.FAILED;
        }
        if (context.nav().arrived(target, context.config().navigationTolerance())) {
            context.nav().stop();
            return TaskOutcome.DONE;
        }
        if (!context.nav().running() && !context.nav().start(target)) return TaskOutcome.RETRY;
        context.nav().tick(context.clock().now());
        return TaskOutcome.CONTINUE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        context.nav().stop();
    }

    @Override
    public String status(BotTask task) {
        return "Exploring";
    }
}
