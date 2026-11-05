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
    MAINTENANCE;

    @Override
    public String toString() {
        String lower = name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
