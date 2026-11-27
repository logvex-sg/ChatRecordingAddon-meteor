package com.logvex.addon.bot.core;

/**
 * Decides whether the bot may keep working. It is consulted before every action, so a safety stop
 * takes effect immediately rather than after the current task finishes.
 *
 * <p>The controller never mutates the world; it only answers questions. That keeps the decision
 * logic testable and makes it impossible for a safety check to have a side effect of its own.
 */
public final class SafetyController {
    /** Why the bot was asked to stop. */
    public enum Stop {
        NONE,
        DISCONNECTED,
        LOW_HEALTH,
        LOW_FOOD,
        SCREEN_OPEN,
        NOT_IN_WORLD,
        INVENTORY_FULL,
        WORLD_CHANGED,
        THREAT
    }

    private final BotConfig config;

    public SafetyController(BotConfig config) {
        this.config = config == null ? BotConfig.DEFAULTS : config;
    }

    /**
     * Evaluates the current situation.
     *
     * @param player       live player state
     * @param world        live world state
     * @param inventory    live inventory snapshot
     * @param threatNearby true when a hostile mob is within the configured range
     */
    public Stop evaluate(PlayerPort player, WorldPort world, InventorySnapshot inventory,
                         boolean threatNearby) {
        if (player == null || world == null) return Stop.NOT_IN_WORLD;
        if (!player.alive()) return Stop.DISCONNECTED;
        if (!world.canRun()) return Stop.DISCONNECTED;
        // A hostile mob outranks hunger: fleeing is the immediate problem, and a bot that stopped to
        // eat in front of a zombie would be taking damage the whole time.
        if (config.stopOnThreat() && threatNearby) return Stop.THREAT;
        if (config.stopOnLowHealth() && player.health() > 0 && player.health() <= config.healthThreshold()) {
            return Stop.LOW_HEALTH;
        }
        if (config.stopOnLowHealth() && player.foodLevel() <= config.foodThreshold()) {
            return Stop.LOW_FOOD;
        }
        return Stop.NONE;
    }

    /**
     * True when the bot should feed itself before the hunger stop would trip. Auto-eat is checked
     * against its own, higher threshold so the bot eats while it can still work instead of waiting
     * to be paused at the safety limit.
     *
     * <p>{@code hasFood} is passed in rather than read from the inventory here, because deciding
     * what counts as food needs the item registry. Requiring it also stops the bot asking to eat
     * forever when it is carrying nothing edible.
     */
    public boolean shouldEat(PlayerPort player, boolean hasFood) {
        if (!config.autoEat() || player == null || !player.alive()) return false;
        if (player.foodLevel() > config.eatAtFood()) return false;
        return hasFood;
    }

    /** True when the inventory is at or below the configured free-slot threshold. */
    public boolean inventoryFull(InventorySnapshot inventory) {
        if (inventory == null) return false;
        return inventory.emptySlots() <= config.inventoryFullThreshold();
    }

    /** A short explanation for a stop reason. */
    public static String describe(Stop stop) {
        return switch (stop) {
            case NONE -> "";
            case DISCONNECTED -> "Disconnected or the world is unavailable.";
            case LOW_HEALTH -> "Health is below the configured safety threshold.";
            case LOW_FOOD -> "Food is below the configured safety threshold.";
            case SCREEN_OPEN -> "A screen is open.";
            case NOT_IN_WORLD -> "Not in a world.";
            case INVENTORY_FULL -> "The inventory is full.";
            case WORLD_CHANGED -> "The world or dimension changed.";
            case THREAT -> "A hostile mob is within the safety radius.";
        };
    }

    /** True when the stop reason means the bot must halt entirely, not just pause. */
    public static boolean isFatal(Stop stop) {
        return stop == Stop.DISCONNECTED || stop == Stop.NOT_IN_WORLD || stop == Stop.WORLD_CHANGED;
    }
}
