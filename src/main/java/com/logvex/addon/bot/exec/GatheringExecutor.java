package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.ActionPorts;
import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.NavPort;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.bot.core.WorldPort;

import java.util.List;

/**
 * Collects an item by finding the blocks that yield it, walking to the nearest one and mining it
 * until the required amount is in the inventory.
 *
 * <p>Progress is measured from the inventory, never from the number of swing packets sent: an item
 * only counts once it is actually held, so a block that respawns or a break the server rejects
 * cannot inflate the count. The executor re-finds a target whenever the current one is gone, so it
 * keeps working through a vein instead of giving up after one block.
 */
public final class GatheringExecutor implements TaskExecutor {
    /** How close the player must be to mine the current target. */
    private static final double REACH = 4.0;

    private final TaskType type;

    public GatheringExecutor(TaskType type) {
        this.type = type;
    }

    @Override
    public TaskType type() {
        return type;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        task.setParameter("baseline", Integer.toString(context.inventory().snapshot().countOf(task.itemId())));
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        WorldPort world = context.world();
        NavPort nav = context.nav();
        ActionPorts actions = context.actions();
        if (!world.canRun()) return TaskOutcome.RETRY;

        int baseline = parseInt(task.parameter("baseline", "0"));
        int collected = context.inventory().snapshot().countOf(task.itemId()) - baseline;
        if (collected >= task.required()) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }
        task.setProgress(Math.max(0, collected));

        // Continue with the target already chosen, if it still exists.
        BlockPos3 target = currentTarget(task, world);
        if (target == null) {
            List<String> blockIds = blocksFor(task.itemId(), context);
            List<BlockPos3> candidates = world.findBlocks(blockIds, context.config().effectiveRadius());
            if (candidates.isEmpty()) {
                // Nothing nearby; report a retry so the controller can rescan or recover.
                return TaskOutcome.RETRY;
            }
            target = candidates.get(0);
            task.setParameter("target", target.x() + "," + target.y() + "," + target.z());
        }

        if (!nav.arrived(target, REACH)) {
            if (!nav.running()) {
                if (!nav.start(target)) return TaskOutcome.RETRY;
            }
            nav.tick(context.clock().now());
            return TaskOutcome.CONTINUE;
        }

        if (actions.mine(target)) {
            task.setParameter("target", "");
        }
        return TaskOutcome.CONTINUE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        context.nav().stop();
    }

    @Override
    public String status(BotTask task) {
        return "Gathering " + task.itemId() + " (" + task.progress() + "/" + task.required() + ")";
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
            return block == null || block.equals("minecraft:air") || block.equals("minecraft:cave_air")
                ? null : pos;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The block ids that yield the item, resolved from the registry-backed knowledge. */
    private static List<String> blocksFor(String itemId, TaskContext context) {
        if (context.items() != null) return context.items().blocksYielding(itemId);
        return List.of(itemId);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
