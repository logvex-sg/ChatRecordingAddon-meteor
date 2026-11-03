package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.DrainDelegate;
import com.logvex.addon.schematicdropper.core.MaterialPlan;
import com.logvex.addon.schematicdropper.core.ProgressTracker;
import com.logvex.addon.schematicdropper.core.Requirement;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

/**
 * Bridges the acquisition workflow to the existing ordered drop workflow.
 *
 * <p>A drain does not ask the drop engine for the whole requirement: it loads a plan capped at
 * what the player currently holds, resets the shared {@link ProgressTracker} and starts the drop
 * engine. The engine can therefore finish a batch normally instead of waiting forever for
 * materials that have not been created yet, and the tracker's confirmed counters at the end are
 * exactly the quantities that were dropped.
 */
public final class DropBridge implements DrainDelegate {
    private final ProgressTracker tracker;
    private final ToIntFunction<String> heldCount;
    private final BooleanSupplier startDrop;
    private final Runnable cancelDrop;

    private MaterialPlan batch = new MaterialPlan();
    private boolean active;
    private Map<String, Integer> lastDrain = Map.of();

    /**
     * @param tracker    the tracker shared with the drop engine
     * @param heldCount  reads the live held quantity of an item id
     * @param startDrop  starts a drop run from the currently loaded plan
     * @param cancelDrop aborts a drop run without restoring rotation
     */
    public DropBridge(ProgressTracker tracker, ToIntFunction<String> heldCount,
                      BooleanSupplier startDrop, Runnable cancelDrop) {
        this.tracker = tracker;
        this.heldCount = heldCount;
        this.startDrop = startDrop;
        this.cancelDrop = cancelDrop;
    }

    @Override
    public boolean beginDrain() {
        lastDrain = Map.of();
        batch = new MaterialPlan();
        active = true;
        if (!startDrop.getAsBoolean()) {
            active = false;
            return false;
        }
        return true;
    }

    /**
     * Builds the batch plan: only materials that are actually held, capped at what is held. The
     * drop engine can then finish a batch normally instead of waiting for materials that have not
     * been created yet.
     *
     * @return {@code false} when nothing is held, so the drain would be pointless
     */
    public boolean prepare(MaterialPlan plan) {
        MaterialPlan capped = new MaterialPlan();
        for (Requirement r : plan.requirements()) {
            int held = Math.max(0, heldCount.applyAsInt(r.itemId()));
            if (held <= 0) continue;
            capped.add(new Requirement(r.itemId(), r.displayName(), Math.min(held, r.required())));
        }
        this.batch = capped;
        if (capped.isEmpty()) return false;
        tracker.setPlan(capped);
        tracker.resetProgress();
        return true;
    }

    @Override
    public boolean draining() {
        if (!active) return false;
        return tracker.state().isRunning();
    }

    @Override
    public void abortDrain() {
        if (!active) return;
        active = false;
        lastDrain = collect();
        cancelDrop.run();
    }

    @Override
    public Map<String, Integer> lastDrain() {
        if (!active) return lastDrain;
        return collect();
    }

    /** Closes the drain after the drop run reached a terminal state. */
    public void finish() {
        if (!active) return;
        active = false;
        lastDrain = collect();
    }

    public boolean isActive() {
        return active;
    }

    public MaterialPlan batch() {
        return batch;
    }

    private Map<String, Integer> collect() {
        Map<String, Integer> out = new HashMap<>();
        for (Requirement r : batch.requirements()) {
            int dropped = tracker.confirmed(r.itemId());
            if (dropped > 0) out.put(r.itemId(), dropped);
        }
        return out;
    }
}
