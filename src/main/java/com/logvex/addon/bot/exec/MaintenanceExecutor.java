package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.ItemStackView;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Reports tool durability and flags worn tools.
 *
 * <p>This is deliberately a read-only check: it inspects the held tools, records the lowest
 * durability it finds on the task, and finishes. It does not throw anything away, because silently
 * discarding a tool is worse than telling the user it is nearly spent.
 */
public final class MaintenanceExecutor implements TaskExecutor {
    @Override
    public TaskType type() {
        return TaskType.MAINTENANCE;
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        int lowest = Integer.MAX_VALUE;
        int worn = 0;
        for (ItemStackView stack : context.inventory().snapshot().slots().values()) {
            int left = stack.durabilityLeft();
            if (left < 0) continue;
            lowest = Math.min(lowest, left);
            if (left <= context.config().minToolDurability()) worn++;
        }
        task.setParameter("lowest", lowest == Integer.MAX_VALUE ? "-1" : Integer.toString(lowest));
        task.setParameter("worn", Integer.toString(worn));
        task.setProgress(worn == 0 ? task.required() : 0);
        return TaskOutcome.DONE;
    }

    @Override
    public String status(BotTask task) {
        return "Checking tools";
    }
}
