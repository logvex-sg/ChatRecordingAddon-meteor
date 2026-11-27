package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Frees inventory space by dropping bulk junk.
 *
 * <p>The droppable set is {@link JunkItems}: bulk blocks the bot itself produces while gathering.
 * Anything absent is kept, so a tidy pass can never throw away a tool, an ore, food, or something
 * the player named. The asymmetry is deliberate — a full inventory is an inconvenience, a discarded
 * pickaxe is a real loss.
 *
 * <p>Progress is measured from the item count actually falling, and the pass stops as soon as the
 * requested number of items is dropped. The whitelist is what keeps this safe, so the selected slot
 * is not special-cased: protecting it would only dead-end a bag that is entirely cobblestone.
 */
public final class TidyExecutor implements TaskExecutor {
    @Override
    public TaskType type() {
        return TaskType.TIDY;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        // A tidy objective with no count means "free every slot you can".
        if (task.required() <= 0) task.required(Math.max(1, context.inventory().snapshot().usedSlots()));
        task.setParameter("dropped", "0");
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        if (!context.actions().canAct()) return TaskOutcome.RETRY;

        int dropped = parseInt(task.parameter("dropped", "0"));
        if (dropped >= task.required()) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }

        String victim = JunkItems.biggest(context.inventory().snapshot());
        if (victim.isEmpty()) {
            // Nothing left worth dropping: report what was freed and stop rather than spinning.
            return dropped > 0 ? TaskOutcome.DONE : TaskOutcome.FAILED;
        }

        int before = context.inventory().snapshot().countOf(victim);
        if (!context.inventory().moveToHotbar(victim, before)) return TaskOutcome.RETRY;
        if (!context.inventory().dropHeld()) return TaskOutcome.RETRY;

        int after = context.inventory().snapshot().countOf(victim);
        if (after < before) {
            dropped++;
            task.setParameter("dropped", Integer.toString(dropped));
            task.setProgress(Math.min(task.required(), dropped));
        }
        return TaskOutcome.CONTINUE;
    }

    @Override
    public String status(BotTask task) {
        return "Tidying (" + task.parameter("dropped", "0") + "/" + task.required() + ")";
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
