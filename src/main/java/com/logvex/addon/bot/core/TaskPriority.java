package com.logvex.addon.bot.core;

/** Scheduling priority. A lower {@link #weight()} is scheduled first. */
public enum TaskPriority {
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    LOW(3);

    private final int weight;

    TaskPriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
