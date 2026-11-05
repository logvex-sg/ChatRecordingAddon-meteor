package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

import java.util.List;

/**
 * Smelts items in the nearest furnace.
 *
 * <p>The executor requires an input, a fuel and a furnace before it starts, and counts progress from
 * the smelted output appearing in the inventory. It never reports success for a batch the server did
 * not produce.
 */
public final class SmeltingExecutor implements TaskExecutor {
    private static final List<String> FURNACE_BLOCKS = List.of(
        "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker");

    @Override
    public TaskType type() {
        return TaskType.SMELTING;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        task.setParameter("baseline", Integer.toString(context.inventory().snapshot().countOf(task.itemId())));
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        if (!context.world().canRun()) return TaskOutcome.RETRY;

        int baseline = parseInt(task.parameter("baseline", "0"));
        int produced = context.inventory().snapshot().countOf(task.itemId()) - baseline;
        if (produced >= task.required()) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }
        task.setProgress(Math.max(0, produced));

        String input = task.parameter("input", "");
        String fuel = task.parameter("fuel", "minecraft:coal");
        if (input.isEmpty()) return TaskOutcome.FAILED;
        if (context.inventory().snapshot().countOf(input) <= 0) return TaskOutcome.RETRY;

        List<BlockPos3> furnaces = context.world().findBlocks(FURNACE_BLOCKS,
            context.config().effectiveRadius());
        if (furnaces.isEmpty()) return TaskOutcome.RETRY;
        BlockPos3 furnace = furnaces.get(0);

        if (!context.nav().arrived(furnace, 4.0)) {
            if (!context.nav().running() && !context.nav().start(furnace)) return TaskOutcome.RETRY;
            context.nav().tick(context.clock().now());
            return TaskOutcome.CONTINUE;
        }
        context.actions().smelt(input, fuel, 1);
        return TaskOutcome.CONTINUE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        context.nav().stop();
    }

    @Override
    public String status(BotTask task) {
        return "Smelting " + task.itemId();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
