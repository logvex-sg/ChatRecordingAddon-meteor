package com.logvex.addon.bot.core;

/**
 * An immutable snapshot of every tunable the bot reads. The module builds one of these from its
 * the module's settings each time they change, so the core never reaches back into Minecraft and a
 * test can construct an exact configuration.
 */
public record BotConfig(
    int maxScanResults,
    int gatherRadius,
    int containerRadius,
    int entityRadius,
    double navigationTolerance,
    double maxTravelDistance,
    int minToolDurability,
    int inventoryFullThreshold,
    float healthThreshold,
    int foodThreshold,
    long actionCooldownMs,
    long taskTimeoutMs,
    int maxTasksPerTick,
    boolean lowPerformanceMode,
    int retryLimit,
    long retryBackoffMs,
    boolean stopOnDisconnect,
    boolean stopOnLowHealth
) {
    public static final BotConfig DEFAULTS = builder().build();

    public static Builder builder() {
        return new Builder();
    }

    /** A mutable builder so the module can assemble a config without a 18-argument constructor. */
    public static final class Builder {
        private int maxScanResults = 512;
        private int gatherRadius = 32;
        private int containerRadius = 16;
        private int entityRadius = 16;
        private double navigationTolerance = 2.0;
        private double maxTravelDistance = 256.0;
        private int minToolDurability = 10;
        private int inventoryFullThreshold = 1;
        private float healthThreshold = 8.0f;
        private int foodThreshold = 6;
        private long actionCooldownMs = 100;
        private long taskTimeoutMs = 120_000;
        private int maxTasksPerTick = 1;
        private boolean lowPerformanceMode = false;
        private int retryLimit = 3;
        private long retryBackoffMs = 500;
        private boolean stopOnDisconnect = true;
        private boolean stopOnLowHealth = true;

        public Builder maxScanResults(int v) { this.maxScanResults = Math.max(1, v); return this; }
        public Builder gatherRadius(int v) { this.gatherRadius = Math.max(1, v); return this; }
        public Builder containerRadius(int v) { this.containerRadius = Math.max(1, v); return this; }
        public Builder entityRadius(int v) { this.entityRadius = Math.max(1, v); return this; }
        public Builder navigationTolerance(double v) { this.navigationTolerance = Math.max(0.5, v); return this; }
        public Builder maxTravelDistance(double v) { this.maxTravelDistance = Math.max(8, v); return this; }
        public Builder minToolDurability(int v) { this.minToolDurability = Math.max(0, v); return this; }
        public Builder inventoryFullThreshold(int v) { this.inventoryFullThreshold = Math.max(0, v); return this; }
        public Builder healthThreshold(float v) { this.healthThreshold = Math.max(0, v); return this; }
        public Builder foodThreshold(int v) { this.foodThreshold = Math.max(0, v); return this; }
        public Builder actionCooldownMs(long v) { this.actionCooldownMs = Math.max(0, v); return this; }
        public Builder taskTimeoutMs(long v) { this.taskTimeoutMs = Math.max(1000, v); return this; }
        public Builder maxTasksPerTick(int v) { this.maxTasksPerTick = Math.max(1, v); return this; }
        public Builder lowPerformanceMode(boolean v) { this.lowPerformanceMode = v; return this; }
        public Builder retryLimit(int v) { this.retryLimit = Math.max(1, v); return this; }
        public Builder retryBackoffMs(long v) { this.retryBackoffMs = Math.max(0, v); return this; }
        public Builder stopOnDisconnect(boolean v) { this.stopOnDisconnect = v; return this; }
        public Builder stopOnLowHealth(boolean v) { this.stopOnLowHealth = v; return this; }

        public BotConfig build() {
            return new BotConfig(maxScanResults, gatherRadius, containerRadius, entityRadius,
                navigationTolerance, maxTravelDistance, minToolDurability, inventoryFullThreshold,
                healthThreshold, foodThreshold, actionCooldownMs, taskTimeoutMs, maxTasksPerTick,
                lowPerformanceMode, retryLimit, retryBackoffMs, stopOnDisconnect, stopOnLowHealth);
        }
    }

    /** The per-tick action budget: lower in low-performance mode. */
    public int effectiveActionsPerTick() {
        return lowPerformanceMode ? 1 : Math.max(1, maxTasksPerTick);
    }

    public int effectiveRadius() {
        return lowPerformanceMode ? Math.max(8, gatherRadius / 2) : gatherRadius;
    }
}
