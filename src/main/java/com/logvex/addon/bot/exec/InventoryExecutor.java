package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.InventorySnapshot;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Makes room in the inventory before a haul.
 *
 * <p>The task's {@code required} is the number of free slots wanted. The executor reports how many
 * are free, and when there are too few it drops bulk junk until enough space exists. It never
 * discards anything outside {@link JunkItems}, so running out of space with a full bag of ore is
 * reported as a failure rather than quietly destroying the haul.
 *
 * <p>The previous version compared each slot index to the one before it and swapped when the new
 * index was lower. Slot maps are built in ascending order, so that was never true and the task did
 * nothing at all. Sorting is not possible through this port either — the only slot move available is
 * a hotbar swap, which exchanges the held item rather than merging stacks — so the task now does
 * something it can actually verify.
 */
public final class InventoryExecutor implements TaskExecutor {
    @Override
    public TaskType type() {
        return TaskType.INVENTORY;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        // A request with no count means "make room for one more stack".
        if (task.required() <= 0) task.required(1);
        task.setParameter("freed", "0");
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        InventorySnapshot snapshot = context.inventory().snapshot();
        int free = snapshot.emptySlots();
        task.setParameter("free", Integer.toString(free));

        String wanted = task.itemId();
        if (!wanted.isEmpty()) {
            task.setParameter("room", Integer.toString(
                snapshot.capacityFor(wanted, context.inventory().stackSize(wanted))));
        }

        if (free >= task.required()) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }

        String junk = JunkItems.biggest(snapshot);
        if (junk.isEmpty()) {
            // Every slot holds something worth keeping: say so instead of dropping a valuable item.
            task.fail("Only " + free + " slot(s) free and nothing safe to drop.");
            return TaskOutcome.FAILED;
        }

        int before = snapshot.countOf(junk);
        if (!context.inventory().moveToHotbar(junk, before)) return TaskOutcome.RETRY;
        if (!context.inventory().dropHeld()) return TaskOutcome.RETRY;

        if (context.inventory().snapshot().countOf(junk) < before) {
            task.setParameter("freed", Integer.toString(parseInt(task.parameter("freed", "0")) + 1));
        }
        return TaskOutcome.CONTINUE;
    }

    @Override
    public String status(BotTask task) {
        return "Making room (" + task.parameter("free", "0") + "/" + task.required() + " free)";
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
