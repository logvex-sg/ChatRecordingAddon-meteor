package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Fishes by repeatedly casting and reeling with a fishing rod, using only the ordinary use-item
 * interaction.
 *
 * <p>Progress is measured from the caught items appearing in the inventory. The executor does not
 * read the bobber's private state to predict a bite; it simply casts, waits for the held item to
 * change or for the reel to succeed, and repeats. That keeps it within normal game mechanics.
 */
public final class FishingExecutor implements TaskExecutor {
    /** How long to let a cast sit before reeling, in milliseconds. */
    private static final long CAST_MS = 4000;

    @Override
    public TaskType type() {
        return TaskType.FISHING;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        task.setParameter("baseline", Integer.toString(countBaseline(task, context)));
        task.setParameter("castAt", "0");
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        if (!context.world().canRun()) return TaskOutcome.RETRY;

        int baseline = parseInt(task.parameter("baseline", "0"));
        int caught = totalInventory(context) - baseline;
        if (caught >= task.required()) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }
        task.setProgress(Math.max(0, caught));

        if (context.inventory().snapshot().countOf("minecraft:fishing_rod") <= 0) {
            return TaskOutcome.FAILED;
        }

        long now = context.clock().now();
        long castAt = parseLong(task.parameter("castAt", "0"));
        if (castAt == 0) {
            context.actions().useItem();
            task.setParameter("castAt", Long.toString(now));
            return TaskOutcome.CONTINUE;
        }
        if (now - castAt >= CAST_MS) {
            context.actions().useItem();
            task.setParameter("castAt", "0");
        }
        return TaskOutcome.CONTINUE;
    }

    @Override
    public String status(BotTask task) {
        return "Fishing";
    }

    private static int countBaseline(BotTask task, TaskContext context) {
        return totalInventory(context);
    }

    private static int totalInventory(TaskContext context) {
        int total = 0;
        for (int count : context.inventory().snapshot().totals().values()) total += count;
        return total;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
