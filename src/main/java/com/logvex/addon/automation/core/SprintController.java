package com.logvex.addon.automation.core;

/**
 * Decides when the player should sprint.
 *
 * <p>Sprinting is only requested while the player is genuinely moving forward, is not sneaking and
 * has enough food; sprinting on an empty hunger bar is wasted and the client would stop it anyway.
 * The decision is a pure function so it can be unit-tested without a client.
 */
public final class SprintController {
    private boolean enabled;
    private boolean requireHunger;
    private int minFood;

    public SprintController(boolean requireHunger, int minFood) {
        this.requireHunger = requireHunger;
        this.minFood = clampFood(minFood);
    }

    private static int clampFood(int value) {
        return Math.max(0, Math.min(20, value));
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setRequireHunger(boolean value) {
        this.requireHunger = value;
    }

    public boolean requireHunger() {
        return requireHunger;
    }

    public void setMinFood(int value) {
        this.minFood = clampFood(value);
    }

    public int minFood() {
        return minFood;
    }

    /**
     * Whether the player should be sprinting right now.
     *
     * @param movingForward whether the forward key is held
     * @param foodLevel     current food level
     * @param sneaking      whether the player is sneaking
     * @param inWater       whether the player is in water
     */
    public boolean shouldSprint(boolean movingForward, int foodLevel, boolean sneaking, boolean inWater) {
        if (!enabled) return false;
        if (!movingForward || sneaking || inWater) return false;
        if (requireHunger && foodLevel <= minFood) return false;
        return true;
    }

    public String status() {
        if (!enabled) return "off";
        return requireHunger ? "on (min food " + minFood + ")" : "on";
    }
}
