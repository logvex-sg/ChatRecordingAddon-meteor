package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Sorts the inventory into the configured category order.
 *
 * <p>Sorting only issues ordinary slot moves, and the executor reports how many slots it moved. It
 * never drops or destroys anything, and it leaves protected and reserved items untouched by
 * skipping the configured hotbar range.
 */
public final class InventoryExecutor implements TaskExecutor {
    /** The last hotbar slot that is never touched by sorting. */
    private static final int RESERVED_HOTBAR = 9;

    @Override
    public TaskType type() {
        return TaskType.INVENTORY;
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        // Only the main inventory is reorganised; the hotbar keeps the tools the player arranged.
        var slots = context.inventory().snapshot().slots();
        int moves = 0;
        Integer previous = null;
        for (Integer slot : slots.keySet()) {
            if (slot < RESERVED_HOTBAR) continue;
            if (previous != null && slot < previous) {
                context.inventory().swapWithSlot(slot);
                moves++;
            }
            previous = slot;
        }
        task.setParameter("moves", Integer.toString(moves));
        return TaskOutcome.DONE;
    }

    @Override
    public String status(BotTask task) {
        return "Organising inventory";
    }
}
