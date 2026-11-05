package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotConfig;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.bot.exec.GatheringExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gathering executor must count progress from the inventory, not from the actions it sent, and
 * it must keep working through a vein instead of stopping after one block.
 */
class GatheringExecutorTest {
    private BotTask gather(String item, int amount) {
        return new BotTask(TaskType.GATHERING, "gather " + item).itemId(item).required(amount);
    }

    @Test
    void failsFastWhenThereAreNoSourceBlocks() {
        BotFakes.Harness h = new BotFakes.Harness();
        GatheringExecutor executor = new GatheringExecutor(TaskType.GATHERING);
        BotTask task = gather("minecraft:oak_log", 4);
        executor.begin(task, h.context());

        // No blocks in the world: the executor asks for a retry rather than pretending to work.
        assertEquals(TaskOutcome.RETRY, executor.step(task, h.context()));
    }

    @Test
    void completesOnlyOnceTheItemIsActuallyHeld() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:oak_log", List.of(new BlockPos3(1, 64, 0)));
        h.nav.arrivedNow = true;

        GatheringExecutor executor = new GatheringExecutor(TaskType.GATHERING);
        BotTask task = gather("minecraft:oak_log", 1);
        executor.begin(task, h.context());

        // First step mines the block but the inventory is still empty, so the task is not done.
        assertEquals(TaskOutcome.CONTINUE, executor.step(task, h.context()));
        assertTrue(h.actions.calls.stream().anyMatch(c -> c.startsWith("mine")));

        // The server confirms the drop: only now does the task finish.
        h.inventory.items.put("minecraft:oak_log", 1);
        assertEquals(TaskOutcome.DONE, executor.step(task, h.context()));
    }

    @Test
    void countsProgressFromTheInventory() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:stone", List.of(new BlockPos3(1, 64, 0)));
        h.nav.arrivedNow = true;

        GatheringExecutor executor = new GatheringExecutor(TaskType.MINING);
        BotTask task = new BotTask(TaskType.MINING, "mine stone").itemId("minecraft:stone").required(10);
        executor.begin(task, h.context());

        h.inventory.items.put("minecraft:stone", 4);
        executor.step(task, h.context());
        assertEquals(4, task.progress());
    }

    @Test
    void stopsNavigatingWhenTheTaskEnds() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:oak_log", List.of(new BlockPos3(8, 64, 0)));
        GatheringExecutor executor = new GatheringExecutor(TaskType.GATHERING);
        BotTask task = gather("minecraft:oak_log", 1);
        executor.begin(task, h.context());
        executor.step(task, h.context());
        assertTrue(h.nav.running);

        executor.end(task, h.context());
        assertTrue(!h.nav.running);
    }

    @Test
    void retriesWhenTheWorldIsUnavailable() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.canRun = false;
        GatheringExecutor executor = new GatheringExecutor(TaskType.GATHERING);
        BotTask task = gather("minecraft:oak_log", 1);
        executor.begin(task, h.context());
        assertEquals(TaskOutcome.RETRY, executor.step(task, h.context()));
    }

    @Test
    void configDefaultsAreSane() {
        BotConfig config = BotConfig.DEFAULTS;
        assertTrue(config.gatherRadius() > 0);
        assertTrue(config.actionCooldownMs() >= 0);
        assertTrue(config.retryLimit() >= 1);
    }
}
