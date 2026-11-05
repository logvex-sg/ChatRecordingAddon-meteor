package com.logvex.addon.bot.core;

/**
 * Everything a {@link TaskExecutor} may read or use, bundled so executors take one argument and
 * tests can build a context from fakes.
 */
public record TaskContext(
    WorldPort world,
    PlayerPort player,
    InventoryPort inventory,
    NavPort nav,
    ActionPorts actions,
    ItemKnowledge items,
    RecipeSource recipes,
    BuildPort build,
    BotClock clock,
    BotConfig config,
    BotMemory memory
) {
}
