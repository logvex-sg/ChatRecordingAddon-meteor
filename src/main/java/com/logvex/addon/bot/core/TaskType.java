package com.logvex.addon.bot.core;

/** The kinds of work the bot can schedule. Each maps to an executor in the Minecraft layer. */
public enum TaskType {
    GATHERING,
    MINING,
    WOODCUTTING,
    FARMING,
    CRAFTING,
    SMELTING,
    BUILDING,
    NAVIGATION,
    EXPLORATION,
    INVENTORY,
    STORAGE,
    FISHING,
    TRANSPORT,
    MAINTENANCE,
    COMBAT,
    /** Places light sources where it is too dark for mobs to spawn. */
    LIGHTING,
    /** Kills named mob types for their drops, then collects the drops. */
    HUNTING,
    /** Cleans the inventory: drops junk and keeps tools, food and configured keeps. */
    TIDY,
    /** Internal: feed the player. Never planned from a user objective; queued by the controller. */
    EAT;

    @Override
    public String toString() {
        String lower = name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
