package com.logvex.addon.automation.core;

import java.util.List;

/**
 * Decides when the player should eat and which held item to eat.
 *
 * <p>The decision is a pure function of the food level, the configured threshold and the food items
 * the player actually holds, so it is fully unit-testable and the Minecraft layer only has to
 * perform the selected action. Eating is only requested when the player is hungry and a suitable
 * food item is held, so the controller never asks the client to right-click air.
 */
public final class AutoEatController {
    private final int defaultThreshold;
    private final int defaultPriority;

    private int threshold;
    private int priority;
    private boolean enabled;

    private String activeFoodId;
    private long lastEatAt;

    public AutoEatController(int threshold, int priority) {
        this.defaultThreshold = clampThreshold(threshold);
        this.defaultPriority = clampPriority(priority);
        this.threshold = this.defaultThreshold;
        this.priority = this.defaultPriority;
    }

    private static int clampThreshold(int value) {
        return Math.max(0, Math.min(20, value));
    }

    private static int clampPriority(int value) {
        return Math.max(0, Math.min(9, value));
    }

    public void setThreshold(int value) {
        this.threshold = clampThreshold(value);
    }

    public int threshold() {
        return threshold;
    }

    public void setPriority(int value) {
        this.priority = clampPriority(value);
    }

    public int priority() {
        return priority;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        if (!enabled) activeFoodId = null;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Restores the configured defaults, e.g. when the module is re-enabled. */
    public void resetToDefaults() {
        threshold = defaultThreshold;
        priority = defaultPriority;
        reset();
    }

    /** Resets the runtime state, e.g. on a world change. */
    public void reset() {
        activeFoodId = null;
        lastEatAt = 0;
    }

    /**
     * Whether the player should eat right now.
     *
     * @param foodLevel   current food level (0..20)
     * @param health      current health, kept for callers that want to make eating urgent
     * @param heldFoodIds the food item ids the player holds, most-preferred first
     */
    public boolean shouldEat(int foodLevel, float health, List<String> heldFoodIds) {
        if (!enabled) return false;
        if (foodLevel > threshold) return false;
        return heldFoodIds != null && !heldFoodIds.isEmpty();
    }

    /**
     * Chooses the food to eat: the held item with the highest nutrition, so a player at low hunger
     * eats a real meal rather than a snack. Ties keep the earlier (more preferred) item.
     *
     * @param heldFoodIds the held food ids, most-preferred first
     * @param foodValue   a function returning an item's nutrition, or 0 when unknown
     */
    public String chooseFood(List<String> heldFoodIds, java.util.function.ToIntFunction<String> foodValue) {
        if (heldFoodIds == null || heldFoodIds.isEmpty()) return null;
        String best = heldFoodIds.get(0);
        int bestValue = foodValue == null ? 0 : foodValue.applyAsInt(best);
        for (int i = 1; i < heldFoodIds.size(); i++) {
            String candidate = heldFoodIds.get(i);
            int value = foodValue == null ? 0 : foodValue.applyAsInt(candidate);
            if (value > bestValue) {
                best = candidate;
                bestValue = value;
            }
        }
        activeFoodId = best;
        return best;
    }

    /** The food the controller last selected, or {@code null}. */
    public String activeFoodId() {
        return activeFoodId;
    }

    /** Records that an eat was performed at {@code now}, for rate limiting. */
    public void markEaten(long now) {
        lastEatAt = now;
    }

    /** True when the eat cooldown has elapsed. */
    public boolean cooldownElapsed(long now, long cooldownMs) {
        return now - lastEatAt >= Math.max(0, cooldownMs);
    }

    public long lastEatAt() {
        return lastEatAt;
    }

    /** A short, human-readable status line for the HUD and chat. */
    public String status(int foodLevel, int heldFoodCount) {
        if (!enabled) return "off";
        return "food " + foodLevel + "/20, threshold " + threshold
            + ", " + heldFoodCount + " food item" + (heldFoodCount == 1 ? "" : "s") + " held";
    }
}
