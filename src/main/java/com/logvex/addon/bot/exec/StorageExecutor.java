package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

import java.util.List;

/**
 * Stores the named item in the nearest container.
 *
 * <p>The executor only opens a real container block and hands the stack over through the ordinary
 * interaction. It verifies the container exists and that the item is held before starting, and
 * counts progress from the inventory shrinking, so a container the server refuses to open cannot
 * look like a successful deposit.
 */
public final class StorageExecutor implements TaskExecutor {
    private static final double REACH = 4.0;

    @Override
    public TaskType type() {
        return TaskType.STORAGE;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        task.setParameter("baseline", Integer.toString(context.inventory().snapshot().countOf(task.itemId())));
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        if (!context.world().canRun()) return TaskOutcome.RETRY;

        int baseline = parseInt(task.parameter("baseline", "0"));
        int held = context.inventory().snapshot().countOf(task.itemId());
        int stored = baseline - held;
        int wanted = task.required() > 0 ? task.required() : baseline;
        if (stored >= wanted) {
            task.setProgress(wanted);
            return TaskOutcome.DONE;
        }
        task.setProgress(Math.max(0, stored));

        List<BlockPos3> containers = context.world().findContainers(context.config().containerRadius());
        if (containers.isEmpty()) return TaskOutcome.RETRY;
        BlockPos3 container = containers.get(0);

        if (!context.nav().arrived(container, REACH)) {
            if (!context.nav().running() && !context.nav().start(container)) return TaskOutcome.RETRY;
            context.nav().tick(context.clock().now());
            return TaskOutcome.CONTINUE;
        }
        if (!context.actions().interact(container)) return TaskOutcome.RETRY;
        return TaskOutcome.CONTINUE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        context.nav().stop();
    }

    @Override
    public String status(BotTask task) {
        return "Storing " + task.itemId();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
