package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.FoodSource;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Eats until the food level is back above the auto-eat threshold.
 *
 * <p>Progress is measured from the food level rising, not from the act of clicking, because the
 * server decides whether an item may be eaten. The executor selects a food slot, uses it, and
 * re-reads the food level on the next step; a bite that did not raise the level is retried a
 * bounded number of times and then reported as a failure instead of looping forever.
 */
public final class EatExecutor implements TaskExecutor {
    /** How many bites one run may attempt before it gives up. */
    private static final int MAX_BITES = 12;

    @Override
    public TaskType type() {
        return TaskType.EAT;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        task.setParameter("bites", "0");
        task.setParameter("lastFood", Integer.toString(food(context)));
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        if (!context.world().canRun() || context.player() == null) return TaskOutcome.RETRY;

        int food = food(context);
        int target = (int) Math.ceil(context.config().eatAtFood());
        if (food >= target) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }

        int bites = parseInt(task.parameter("bites", "0"));
        if (bites >= MAX_BITES) {
            task.fail("Could not eat: the food level did not rise.");
            return TaskOutcome.FAILED;
        }

        int lastFood = parseInt(task.parameter("lastFood", Integer.toString(food)));
        // A bite that did not raise the level means the item was not eaten (wrong slot, or the
        // player was interrupted); keep trying until the budget runs out.
        if (food <= lastFood) {
            String foodId = FoodSource.firstFood(context);
            if (foodId.isEmpty()) {
                task.fail("No food in the inventory.");
                return TaskOutcome.FAILED;
            }
            context.inventory().moveToHotbar(foodId, 1);
            context.actions().eat();
            task.setParameter("bites", Integer.toString(bites + 1));
        }
        task.setParameter("lastFood", Integer.toString(food));
        task.setProgress(Math.max(0, food - (int) Math.ceil(context.config().eatAtFood())));
        return TaskOutcome.CONTINUE;
    }

    @Override
    public String status(BotTask task) {
        return "Eating";
    }

    private static int food(TaskContext context) {
        return context.player() == null ? 0 : context.player().foodLevel();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
