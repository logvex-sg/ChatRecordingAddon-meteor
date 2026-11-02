package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for run progress.
 *
 * <p>Only {@link #addConfirmed(String, int)} moves the "dropped" counters, and the engine calls
 * it exclusively after the server acknowledged the drop. Everything the GUI and HUD display is
 * derived from this tracker through {@link #snapshot()}.
 */
public final class ProgressTracker {
    private final Map<String, Integer> confirmed = new HashMap<>();
    private final Map<String, Integer> inventory = new HashMap<>();
    private final Map<String, Integer> missing = new HashMap<>();

    private MaterialPlan plan = new MaterialPlan();
    private State state = State.IDLE;
    private String currentMaterial;
    private int currentStackSlot = -1;
    private int currentStackCount;
    private int failedOperations;

    public void setPlan(MaterialPlan plan) {
        this.plan = plan == null ? new MaterialPlan() : plan;
    }

    public MaterialPlan plan() {
        return plan;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state == null ? State.IDLE : state;
    }

    public int confirmed(String itemId) {
        return confirmed.getOrDefault(itemId, 0);
    }

    public int remaining(String itemId) {
        Requirement r = plan.get(itemId);
        if (r == null) return 0;
        return Math.max(0, r.required() - confirmed(itemId));
    }

    public long totalConfirmed() {
        long total = 0;
        for (int value : confirmed.values()) total += value;
    return total;
    }

    public long totalRequired() {
        return plan.totalRequired();
    }

    public long totalRemaining() {
        return Math.max(0, totalRequired() - totalConfirmed());
    }

    /** Records a confirmed transfer. Amounts {@code <= 0} are ignored. */
    public void addConfirmed(String itemId, int amount) {
        if (itemId == null || amount <= 0) return;
        int next = confirmed(itemId) + amount;
        Requirement r = plan.get(itemId);
        if (r != null) next = Math.min(next, r.required());
        confirmed.put(itemId, next);
    }

    public void setInventory(String itemId, int count) {
        if (itemId == null) return;
        if (count <= 0) inventory.remove(itemId);
        else inventory.put(itemId, count);
    }

    public int inventory(String itemId) {
        return inventory.getOrDefault(itemId, 0);
    }

    public void setMissing(String itemId, int quantity) {
        if (itemId == null) return;
        if (quantity <= 0) missing.remove(itemId);
        else missing.put(itemId, quantity);
    }

    public void clearMissing() {
        missing.clear();
    }

    public int missingQuantity(String itemId) {
        return missing.getOrDefault(itemId, 0);
    }

    public int missingTypeCount() {
        return missing.size();
    }

    public void setCurrentMaterial(String itemId) {
        this.currentMaterial = itemId;
    }

    public void setCurrentStack(int slot, int count) {
        this.currentStackSlot = slot;
        this.currentStackCount = count;
    }

    public void clearCurrentStack() {
        this.currentStackSlot = -1;
        this.currentStackCount = 0;
    }

    public void recordFailure() {
        failedOperations++;
    }

    public int failedOperations() {
        return failedOperations;
    }

    /** Number of distinct materials in the plan. */
    public int requirementCount() {
        return plan.size();
    }

    /** Clears all confirmed progress. Does not touch the plan. */
    public void resetProgress() {
        confirmed.clear();
        missing.clear();
        failedOperations = 0;
        currentMaterial = null;
        clearCurrentStack();
        state = State.IDLE;
    }

    /** Clears everything, including the plan. */
    public void resetAll() {
        resetProgress();
        plan = new MaterialPlan();
        inventory.clear();
    }

    public boolean isComplete() {
        long required = totalRequired();
        return required > 0 && totalConfirmed() >= required;
    }

    public double percent() {
        long required = totalRequired();
        if (required <= 0) return 0;
        return Math.min(100.0, (double) totalConfirmed() / required * 100.0);
    }

    /** Builds an immutable snapshot for rendering. */
    public ProgressSnapshot snapshot() {
        List<MaterialProgress> materials = new ArrayList<>(plan.size());
        int completedTypes = 0;

        for (Requirement r : plan.requirements()) {
            int done = Math.min(confirmed(r.itemId()), r.required());
            if (done >= r.required()) completedTypes++;

            materials.add(new MaterialProgress(
                r.itemId(),
                r.displayName(),
                r.required(),
                done,
                Math.max(0, r.required() - done),
                inventory(r.itemId()),
                missingQuantity(r.itemId())
            ));
        }

        return new ProgressSnapshot(
            state,
            totalRequired(),
            totalConfirmed(),
            totalRemaining(),
            completedTypes,
            plan.size(),
            missingTypeCount(),
            currentMaterial,
            currentStackSlot,
            currentStackCount,
            failedOperations,
            percent(),
            List.copyOf(materials)
        );
    }
}
