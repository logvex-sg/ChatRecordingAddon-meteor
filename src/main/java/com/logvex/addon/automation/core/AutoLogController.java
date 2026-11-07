package com.logvex.addon.automation.core;

/**
 * Decides when the client should disconnect to protect the player.
 *
 * <p>Each rule is a simple threshold, and the controller reports the first rule that fires with a
 * human-readable reason. It never disconnects on its own; it only answers the question, so the
 * Minecraft layer performs the disconnect and the decision stays testable. A grace period after
 * joining prevents an immediate disconnect while health and inventory are still syncing.
 */
public final class AutoLogController {
    /** Why the player should leave. */
    public enum Reason {
        NONE,
        LOW_HEALTH,
        NO_FOOD,
        FULL_INVENTORY,
        HOSTILE_NEARBY,
        PLAYER_NEARBY,
        DURABILITY,
        MANUAL
    }

    private boolean enabled;
    private float healthThreshold;
    private int foodThreshold;
    private boolean logOnFullInventory;
    private boolean logOnHostile;
    private double hostileRadius;
    private boolean logOnPlayer;
    private double playerRadius;
    private long graceMs;

    private long joinedAt;
    private boolean armed;

    public AutoLogController(float healthThreshold, int foodThreshold, long graceMs) {
        this.healthThreshold = Math.max(0, healthThreshold);
        this.foodThreshold = Math.max(0, Math.min(20, foodThreshold));
        this.graceMs = Math.max(0, graceMs);
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setHealthThreshold(float value) {
        this.healthThreshold = Math.max(0, value);
    }

    public void setFoodThreshold(int value) {
        this.foodThreshold = Math.max(0, Math.min(20, value));
    }

    public void setLogOnFullInventory(boolean value) {
        this.logOnFullInventory = value;
    }

    public void setLogOnHostile(boolean value, double radius) {
        this.logOnHostile = value;
        this.hostileRadius = Math.max(0, radius);
    }

    public void setLogOnPlayer(boolean value, double radius) {
        this.logOnPlayer = value;
        this.playerRadius = Math.max(0, radius);
    }

    public void setGraceMs(long value) {
        this.graceMs = Math.max(0, value);
    }

    /** Called when the client joins a world, to start the grace period. */
    public void onJoin(long now) {
        joinedAt = now;
        armed = true;
    }

    public void reset() {
        armed = false;
        joinedAt = 0;
    }

    /** True once the grace period has elapsed since the last join. */
    public boolean armed(long now) {
        return armed && now - joinedAt >= graceMs;
    }

    /**
     * Evaluates every rule and returns the first that fires.
     *
     * @param now        current time in milliseconds
     * @param health     current health, or a value above the threshold when unknown
     * @param food       current food level
     * @param freeSlots  empty inventory slots
     * @param hostile    whether a hostile mob is within {@code hostileRadius}
     * @param playerNear whether another player is within {@code playerRadius}
     */
    public Reason evaluate(long now, float health, int food, int freeSlots,
                           boolean hostile, boolean playerNear) {
        if (!enabled || !armed(now)) return Reason.NONE;
        if (health <= healthThreshold) return Reason.LOW_HEALTH;
        if (food <= foodThreshold) return Reason.NO_FOOD;
        if (logOnFullInventory && freeSlots <= 0) return Reason.FULL_INVENTORY;
        if (logOnHostile && hostile) return Reason.HOSTILE_NEARBY;
        if (logOnPlayer && playerNear) return Reason.PLAYER_NEARBY;
        return Reason.NONE;
    }

    /** A human-readable explanation of a reason, for chat and the notification. */
    public static String describe(Reason reason) {
        return switch (reason) {
            case LOW_HEALTH -> "health is too low";
            case NO_FOOD -> "food is too low";
            case FULL_INVENTORY -> "the inventory is full";
            case HOSTILE_NEARBY -> "a hostile mob is nearby";
            case PLAYER_NEARBY -> "another player is nearby";
            case DURABILITY -> "a tool is about to break";
            case MANUAL -> "requested by the user";
            case NONE -> "no reason";
        };
    }
}
