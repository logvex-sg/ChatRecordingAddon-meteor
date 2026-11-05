package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Carries a held item to a destination and stores it.
 *
 * <p>Transport is navigation plus a store: the executor walks to the destination waypoint (from the
 * task's coordinates or a named waypoint), then deposits the named item. Both halves are verified,
 * so a transport only completes when the item has actually left the inventory.
 */
public final class TransportExecutor implements TaskExecutor {
    private static final double REACH = 4.0;

    @Override
    public TaskType type() {
        return TaskType.TRANSPORT;
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
        if (baseline > 0 && held < baseline) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }

        BlockPos3 destination = destination(task, context);
        if (destination == null) return TaskOutcome.FAILED;
        if (!context.nav().arrived(destination, REACH)) {
            if (!context.nav().running() && !context.nav().start(destination)) return TaskOutcome.RETRY;
            context.nav().tick(context.clock().now());
            return TaskOutcome.CONTINUE;
        }
        if (!context.actions().interact(destination)) return TaskOutcome.RETRY;
        return TaskOutcome.CONTINUE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        context.nav().stop();
    }

    @Override
    public String status(BotTask task) {
        return "Transporting " + task.itemId();
    }

    private static BlockPos3 destination(BotTask task, TaskContext context) {
        String x = task.parameter("x", "");
        if (!x.isEmpty()) {
            try {
                return new BlockPos3(Integer.parseInt(x), Integer.parseInt(task.parameter("y", "0")),
                    Integer.parseInt(task.parameter("z", "0")));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (context.memory() != null) {
            var home = context.memory().home();
            if (home.isPresent()) return new BlockPos3(home.get().x(), home.get().y(), home.get().z());
        }
        return null;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
