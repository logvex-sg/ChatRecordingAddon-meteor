package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.bot.core.WorldPort;

import java.util.List;

/**
 * Harvests mature crops and replants them.
 *
 * <p>A crop is only harvested when its block id says it is mature (the crop's "age" block id), and
 * a seed is only replanted when one is actually held. Progress is counted from the harvested item
 * appearing in the inventory, so an unbroken crop cannot be counted.
 */
public final class FarmingExecutor implements TaskExecutor {
    private static final double REACH = 4.0;

    @Override
    public TaskType type() {
        return TaskType.FARMING;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        task.setParameter("baseline", Integer.toString(context.inventory().snapshot().countOf(task.itemId())));
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        WorldPort world = context.world();
        if (!world.canRun()) return TaskOutcome.RETRY;

        int baseline = parseInt(task.parameter("baseline", "0"));
        int harvested = context.inventory().snapshot().countOf(task.itemId()) - baseline;
        if (harvested >= task.required()) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }
        task.setProgress(Math.max(0, harvested));

        BlockPos3 target = currentTarget(task, world);
        if (target == null) {
            List<BlockPos3> crops = world.findBlocks(matureBlocks(task.itemId(), context),
                context.config().effectiveRadius());
            if (crops.isEmpty()) return TaskOutcome.RETRY;
            target = crops.get(0);
            task.setParameter("target", target.x() + "," + target.y() + "," + target.z());
        }

        if (!context.nav().arrived(target, REACH)) {
            if (!context.nav().running() && !context.nav().start(target)) return TaskOutcome.RETRY;
            context.nav().tick(context.clock().now());
            return TaskOutcome.CONTINUE;
        }

        if (context.actions().mine(target)) {
            task.setParameter("target", "");
            replant(task, context, target);
        }
        return TaskOutcome.CONTINUE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        context.nav().stop();
    }

    @Override
    public String status(BotTask task) {
        return "Farming " + task.itemId();
    }

    private void replant(BotTask task, TaskContext context, BlockPos3 pos) {
        String seed = task.parameter("seed", "");
        if (seed.isEmpty() || context.inventory().snapshot().countOf(seed) <= 0) return;
        context.actions().place(pos, seed);
    }

    private static List<String> matureBlocks(String itemId, TaskContext context) {
        if (context.items() != null) return context.items().matureCropsFor(itemId);
        return List.of(itemId);
    }

    private BlockPos3 currentTarget(BotTask task, WorldPort world) {
        String raw = task.parameter("target", "");
        if (raw.isEmpty()) return null;
        String[] parts = raw.split(",");
        if (parts.length != 3) return null;
        try {
            BlockPos3 pos = new BlockPos3(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
            String block = world.blockAt(pos);
            return block == null || block.equals("minecraft:air") ? null : pos;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
