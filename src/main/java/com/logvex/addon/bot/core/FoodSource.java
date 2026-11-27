package com.logvex.addon.bot.core;

/**
 * Chooses which food the bot should eat.
 *
 * <p>Kept in the core so both the controller (deciding whether a meal is even possible) and the
 * eat executor (choosing the item) agree on the answer. The order comes from
 * {@link ItemKnowledge#foods()}, so a better food is preferred over a worse one.
 */
public final class FoodSource {
    private FoodSource() {
    }

    /** The best food the player is carrying, or empty when there is nothing edible. */
    public static String firstFood(TaskContext context) {
        if (context == null || context.items() == null || context.inventory() == null) return "";
        InventorySnapshot inventory = context.inventory().snapshot();
        for (String food : context.items().foods()) {
            if (inventory.countOf(food) > 0) return food;
        }
        return "";
    }

    /** True when the player is carrying at least one edible item. */
    public static boolean hasFood(TaskContext context) {
        return !firstFood(context).isEmpty();
    }
}
