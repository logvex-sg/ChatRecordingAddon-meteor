package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.ExecutorRegistry;
import com.logvex.addon.bot.core.TaskType;

/**
 * Builds the registry of every executor the bot ships with. Having one place that wires the
 * executors means the controller never has to know which types exist, and a missing type shows up
 * as "unsupported" instead of a silent no-op.
 */
public final class StandardExecutors {
    private StandardExecutors() {
    }

    public static ExecutorRegistry create() {
        ExecutorRegistry registry = new ExecutorRegistry();
        registry.register(new GatheringExecutor(TaskType.GATHERING));
        registry.register(new GatheringExecutor(TaskType.MINING));
        registry.register(new GatheringExecutor(TaskType.WOODCUTTING));
        registry.register(new FarmingExecutor());
        registry.register(new CraftingExecutor());
        registry.register(new SmeltingExecutor());
        registry.register(new BuildExecutor());
        registry.register(new NavigationExecutor());
        registry.register(new ExplorationExecutor());
        registry.register(new InventoryExecutor());
        registry.register(new StorageExecutor());
        registry.register(new FishingExecutor());
        registry.register(new TransportExecutor());
        registry.register(new MaintenanceExecutor());
        return registry;
    }
}
