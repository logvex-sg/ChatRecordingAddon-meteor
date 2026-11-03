package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The automatic Creative material acquisition state machine.
 *
 * <p>Work happens inside {@link #tick(long)}, called once per client tick. The engine never
 * sleeps, never spawns threads and never mutates the inventory itself: it asks
 * {@link AcquisitionAction} to send a creative give and waits for the server to confirm it
 * through {@link PendingGive}.
 *
 * <p>Acquisition is measured against the live inventory, so it always creates only the missing
 * quantity and never duplicates items the player already holds. When a give is not confirmed in
 * time the engine simply re-measures the inventory before deciding what to do next, which makes
 * an uncertain result safe.
 *
 * <p>Creative can only place as many items as the inventory can hold. A requirement larger than
 * the free capacity is therefore satisfied in batches: acquire what fits, hand the batch to the
 * existing drop workflow through {@link DrainDelegate}, then continue. Without a drain delegate
 * the engine waits for space instead.
 */
public final class AcquisitionEngine {
    /** Hard cap on a single drop batch, so a stuck drop engine cannot stall acquisition. */
    private static final long DRAIN_TIMEOUT_MS = 180_000L;

    private final AcquisitionEnv env;
    private final AcquisitionAction actions;
    private final DrainDelegate drain;
    private final AcquisitionListener listener;

    private AcquisitionConfig config = AcquisitionConfig.defaults();

    private MaterialPlan plan = new MaterialPlan();
    private AcquisitionState state = AcquisitionState.IDLE;

    private final Map<String, Integer> acquired = new HashMap<>();
    private final Map<String, Integer> held = new HashMap<>();
    /**
     * Quantities a drain has already dropped. Progress is derived as
     * {@code drained + currently held}, so material that left through the drop workflow stays
     * credited and is never created twice, while items the player already had are credited once.
     */
    private final Map<String, Integer> drained = new HashMap<>();
    private final Set<String> blocked = new HashSet<>();

    private PendingGive pending;
    private long pendingSinceMs;
    private long lastActionMs;
    private long lastScanMs;
    private long startedMs;
    private long elapsedMs;
    private int retries;
    private boolean drainActive;
    private boolean finalDrain;
    private long drainStartedMs;
    private String currentMaterial;
    private int currentSlot = -1;
    private int currentAmount;
    private int failedOperations;

    public AcquisitionEngine(AcquisitionEnv env, AcquisitionAction actions,
                             DrainDelegate drain, AcquisitionListener listener) {
        this.env = env;
        this.actions = actions;
        this.drain = drain == null ? DrainDelegate.none() : drain;
        this.listener = listener;
    }

    public void setConfig(AcquisitionConfig config) {
        if (config != null) this.config = config;
    }

    public AcquisitionConfig config() {
        return config;
    }

    public void setPlan(MaterialPlan plan) {
        this.plan = plan == null ? new MaterialPlan() : plan;
    }

    public MaterialPlan plan() {
        return plan;
    }

    public AcquisitionState state() {
        return state;
    }

    public void start(long nowMs) {
        if (state.isRunning()) return;
        resetRun();
        startedMs = nowMs;
        elapsedMs = 0;
        lastActionMs = nowMs - config.operationDelayMs();
        lastScanMs = 0;
        transition(AcquisitionState.SCANNING);
        listener.onFeedback("Acquisition started.");
    }

    public void pause() {
        if (!state.isRunning()) return;
        if (pending != null) {
            listener.onFeedback("Finishing the current item before pausing.");
            return;
        }
        transition(AcquisitionState.PAUSED);
        listener.onFeedback("Acquisition paused.");
    }

    public void resume(long nowMs) {
        if (state != AcquisitionState.PAUSED) return;
        lastActionMs = nowMs - config.operationDelayMs();
        transition(AcquisitionState.SCANNING);
        listener.onFeedback("Acquisition resumed.");
    }

    public void stop() {
        abortDrainIfNeeded();
        finalDrain = false;
        pending = null;
        actions.cancelPending();
        currentMaterial = null;
        currentSlot = -1;
        currentAmount = 0;
        transition(AcquisitionState.IDLE);
        listener.onFeedback("Acquisition stopped.");
    }

    /** Clears confirmed counters and starts again from the current inventory. */
    public void reset() {
        stop();
        finalDrain = false;
        acquired.clear();
        held.clear();
        drained.clear();
        blocked.clear();
        retries = 0;
        failedOperations = 0;
        transition(AcquisitionState.IDLE);
        listener.onFeedback("Acquisition progress reset.");
    }

    /** Forgets the current material so it is measured and acquired again from scratch. */
    public void restartCurrent() {
        if (currentMaterial != null) {
            acquired.remove(currentMaterial);
            drained.remove(currentMaterial);
            blocked.remove(currentMaterial);
        }
        retries = 0;
        if (state == AcquisitionState.ERROR || state.isRunning()) transition(AcquisitionState.SCANNING);
        listener.onFeedback("Restarted the current material.");
    }

    /** Unblocks every material that previously failed, so they are retried. */
    public void retryFailed() {
        int count = blocked.size();
        blocked.clear();
        retries = 0;
        if (state == AcquisitionState.ERROR || state == AcquisitionState.WAITING_FOR_SPACE) {
            transition(AcquisitionState.SCANNING);
        }
        listener.onFeedback(count == 0 ? "Nothing to retry." : "Retrying " + count + " material(s).");
    }

    /** Discards run state on world change or disconnect without emitting completion. */
    public void onWorldChanged() {
        pending = null;
        actions.cancelPending();
        drainActive = false;
        finalDrain = false;
        blocked.clear();
        retries = 0;
        currentMaterial = null;
        currentSlot = -1;
        currentAmount = 0;
        held.clear();
        if (state != AcquisitionState.IDLE) transition(AcquisitionState.IDLE);
    }

    // ------------------------------------------------------------------- tick

    public void tick(long nowMs) {
        if (!state.isRunning()) return;
        if (!env.canRun()) return;
        if (!env.isCreative()) {
            fail(FailureType.NOT_CREATIVE, "Creative mode is required to acquire materials.", true);
            return;
        }

        switch (state) {
            case SCANNING -> tickScanning(nowMs);
            case ACQUIRING -> tickAcquiring(nowMs);
            case VERIFYING -> tickVerifying(nowMs);
            case WAITING_FOR_SPACE -> tickWaitingForSpace(nowMs);
            default -> { /* IDLE / PAUSED / COMPLETED / ERROR handled above */ }
        }
    }

    private void tickScanning(long nowMs) {
        refreshMeasurements();

        if (plan.isEmpty()) {
            transition(AcquisitionState.IDLE);
            listener.onFeedback("No material list imported.");
            return;
        }
        if (allComplete()) {
            if (beginFinalDrainIfNeeded()) return;
            complete(nowMs);
            return;
        }
        transition(AcquisitionState.ACQUIRING);
    }

    /**
     * Once every requirement is satisfied, whatever is still in the inventory is the last batch.
     * With automatic dropping on, that batch is handed to the drop workflow so the run ends with
     * the materials dropped, not merely acquired.
     */
    private boolean beginFinalDrainIfNeeded() {
        if (!config.autoDrop()) return false;
        if (hasHeldMaterial() && beginDrain()) {
            finalDrain = true;
            return true;
        }
        return false;
    }

    private boolean hasHeldMaterial() {
        for (Requirement r : plan.requirements()) {
            if (held.getOrDefault(r.itemId(), 0) > 0) return true;
        }
        return false;
    }

    private void tickAcquiring(long nowMs) {
        refreshMeasurements();

        if (allComplete()) {
            if (beginFinalDrainIfNeeded()) return;
            complete(nowMs);
            return;
        }

        Requirement target = selectMaterial();
        if (target == null) {
            // Every remaining material is blocked; wait and try again later.
            transition(AcquisitionState.WAITING_FOR_SPACE);
            lastScanMs = nowMs;
            return;
        }

        int have = progressOf(target);
        int needed = Math.max(0, target.required() - have);
        if (needed == 0) {
            transition(AcquisitionState.SCANNING);
            return;
        }

        if (!env.isKnownItem(target.itemId())) {
            fail(FailureType.UNKNOWN_ITEM, "Unknown item '" + target.itemId() + "'.", true);
            return;
        }

        int limit = env.stackLimitOf(target.itemId());
        if (limit <= 0) {
            fail(FailureType.UNKNOWN_ITEM, "Item '" + target.itemId() + "' has no stack size.", true);
            return;
        }

        List<SlotInfo> slots = env.writableSlots();
        int capacity = totalCapacity(slots, target.itemId(), limit);
        int amount = Math.min(needed, Math.min(limit, capacity));

        if (amount <= 0) {
            if (config.autoDrop() && beginDrain()) return;
            transition(AcquisitionState.WAITING_FOR_SPACE);
            lastScanMs = nowMs;
            return;
        }

        if (nowMs - lastActionMs < config.operationDelayMs()) return;

        int slot = pickSlot(slots, target.itemId(), limit);
        if (slot < 0) {
            transition(AcquisitionState.WAITING_FOR_SPACE);
            lastScanMs = nowMs;
            return;
        }

        GivePlan give = new GivePlan(target.itemId(), slot, amount, limit);
        if (!give.valid()) {
            fail(FailureType.INVALID_QUANTITY, "Invalid quantity for '" + target.itemId() + "'.", true);
            return;
        }

        currentMaterial = target.itemId();
        currentSlot = slot;
        currentAmount = amount;

        pending = actions.begin(give);
        pendingSinceMs = nowMs;
        if (pending == null) {
            fail(FailureType.UNKNOWN_ERROR, "The creative give could not be sent.", false);
            return;
        }
        transition(AcquisitionState.VERIFYING);
    }

    private void tickVerifying(long nowMs) {
        if (drainActive) {
            if (drainTimedOut()) {
                transition(AcquisitionState.SCANNING);
                return;
            }
            if (drain.draining()) return;
            drainActive = false;
            recordDrained();
            if (finalDrain) {
                finalDrain = false;
                complete(nowMs);
            } else {
                transition(AcquisitionState.SCANNING);
            }
            return;
        }

        if (pending == null) {
            transition(AcquisitionState.SCANNING);
            return;
        }

        switch (pending.poll()) {
            case PENDING -> {
                if (nowMs - pendingSinceMs > config.syncTimeoutMs()) {
                    pending = null;
                    // The server may still have applied the give, so re-measure before retrying.
                    fail(FailureType.SERVER_TIMEOUT, "The server did not confirm the give in time.", false);
                }
            }
            case CONFIRMED -> {
                int in = Math.max(1, pending.confirmedIn());
                String item = currentMaterial;
                pending = null;
                retries = 0;
                lastActionMs = nowMs;
                if (item != null) {
                    acquired.merge(item, in, Integer::sum);
                    listener.onAcquired(item, in);
                }
                currentSlot = -1;
                currentAmount = 0;
                transition(AcquisitionState.SCANNING);
            }
            case REJECTED -> {
                FailureType type = pending.failure() == null ? FailureType.SERVER_REJECTION : pending.failure();
                pending = null;
                fail(type, "The server rejected the creative give.",
                    type == FailureType.NO_CREATIVE_PERMISSION);
            }
            case TIMEOUT -> {
                pending = null;
                fail(FailureType.SERVER_TIMEOUT, "The creative give timed out.", false);
            }
        }
    }

    private void tickWaitingForSpace(long nowMs) {
        if (drainActive) {
            if (drainTimedOut()) {
                transition(AcquisitionState.SCANNING);
                return;
            }
            if (drain.draining()) return;
            drainActive = false;
            recordDrained();
            if (finalDrain) {
                finalDrain = false;
                complete(nowMs);
            } else {
                transition(AcquisitionState.SCANNING);
            }
            return;
        }

        if (lastScanMs != 0 && nowMs - lastScanMs < config.scanIntervalMs()) return;
        lastScanMs = nowMs;
        blocked.clear();
        transition(AcquisitionState.SCANNING);
    }

    // ---------------------------------------------------------------- helpers

    private void refreshMeasurements() {
        held.clear();
        for (Requirement r : plan.requirements()) {
            held.put(r.itemId(), Math.max(0, env.countOf(r.itemId())));
        }
    }

    /**
     * Total material accounted for: what a drain has already dropped plus what is currently held,
     * capped at the requirement. Deriving progress this way means a batch that was dropped is
     * still credited even though it is no longer in the inventory.
     */
    private int progressOf(Requirement r) {
        int total = drained.getOrDefault(r.itemId(), 0) + held.getOrDefault(r.itemId(), 0);
        return Math.min(r.required(), Math.max(0, total));
    }

    private boolean allComplete() {
        if (plan.isEmpty()) return false;
        for (Requirement r : plan.requirements()) {
            if (progressOf(r) < r.required()) return false;
        }
        return true;
    }

    private Requirement selectMaterial() {
        for (Requirement r : plan.requirements()) {
            if (blocked.contains(r.itemId())) continue;
            if (progressOf(r) < r.required()) return r;
        }
        return null;
    }

    private static int totalCapacity(List<SlotInfo> slots, String itemId, int limit) {
        long capacity = 0;
        for (SlotInfo slot : slots) {
            capacity += slot.roomFor(itemId, limit);
            if (capacity >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) capacity;
    }

    private static int pickSlot(List<SlotInfo> slots, String itemId, int limit) {
        int bestSlot = -1;
        int bestRoom = 0;
        for (SlotInfo slot : slots) {
            if (!slot.holds(itemId)) continue;
            int room = slot.roomFor(itemId, limit);
            if (room > bestRoom) {
                bestRoom = room;
                bestSlot = slot.slot();
            }
        }
        if (bestSlot >= 0) return bestSlot;

        for (SlotInfo slot : slots) {
            if (slot.empty()) return slot.slot();
        }
        return -1;
    }

    private boolean beginDrain() {
        drainStartedMs = System.currentTimeMillis();
        if (!drain.beginDrain()) {
            listener.onFeedback("Nothing to drop yet; waiting for free inventory space.");
            return false;
        }
        drainActive = true;
        transition(AcquisitionState.VERIFYING);
        return true;
    }

    /**
     * Ends a drain that has run for too long, so a stuck drop engine can never stall acquisition
     * forever. Whatever the drop engine already confirmed is still credited.
     */
    private boolean drainTimedOut() {
        if (!drainActive) return false;
        if (System.currentTimeMillis() - drainStartedMs < DRAIN_TIMEOUT_MS) return false;
        drain.abortDrain();
        drainActive = false;
        recordDrained();
        listener.onFeedback("The drop batch took too long; continuing.");
        return true;
    }

    private void abortDrainIfNeeded() {
        if (drainActive) {
            drain.abortDrain();
            drainActive = false;
        }
    }

    /**
     * Credits a finished drain. Drained items left the inventory through the drop engine, so they
     * are counted toward the requirement here; without this the engine would create them again
     * because the inventory no longer shows them.
     */
    private void recordDrained() {
        Map<String, Integer> drained = drain.lastDrain();
        if (drained == null || drained.isEmpty()) return;
        for (Map.Entry<String, Integer> entry : drained.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) continue;
            acquired.merge(entry.getKey(), entry.getValue(), Integer::sum);
            drained.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private void fail(FailureType type, String detail, boolean fatal) {
        retries++;
        failedOperations++;
        listener.onFailure(type, detail, fatal);

        if (fatal || !type.recoverable()) {
            transition(AcquisitionState.ERROR);
            return;
        }

        if (type == FailureType.INVENTORY_FULL) {
            retries = 0;
            transition(AcquisitionState.WAITING_FOR_SPACE);
            lastScanMs = 0;
            return;
        }

        if (retries < Math.max(1, config.retryLimit())) {
            lastActionMs = lastScanMs + config.retryBackoffMs();
            transition(AcquisitionState.SCANNING);
            return;
        }

        retries = 0;
        if (currentMaterial != null) blocked.add(currentMaterial);
        transition(AcquisitionState.SCANNING);
    }

    private void complete(long nowMs) {
        abortDrainIfNeeded();
        if (startedMs > 0) elapsedMs = nowMs - startedMs;
        currentMaterial = null;
        currentSlot = -1;
        currentAmount = 0;
        transition(AcquisitionState.COMPLETED);
        listener.onCompleted(countComplete(), totalAcquired(), elapsedMs);
    }

    private void resetRun() {
        pending = null;
        drainActive = false;
        retries = 0;
        blocked.clear();
        acquired.clear();
        held.clear();
        drained.clear();
        currentMaterial = null;
        currentSlot = -1;
        currentAmount = 0;
    }

    private void transition(AcquisitionState next) {
        if (state == next) return;
        state = next;
        listener.onStateChanged(next);
    }

    // ---------------------------------------------------------------- snapshots

    private long totalAcquired() {
        long total = 0;
        for (int value : acquired.values()) total += value;
        return total;
    }

    private int countComplete() {
        int count = 0;
        for (Requirement r : plan.requirements()) {
            if (progressOf(r) >= r.required()) count++;
        }
        return count;
    }

    /** Builds an immutable snapshot for the GUI and HUD. */
    public AcquisitionSnapshot snapshot() {
        List<MaterialAcquisition> materials = new ArrayList<>(plan.size());
        int complete = 0;
        long satisfiedTotal = 0;
        long totalRequired = plan.totalRequired();

        for (Requirement r : plan.requirements()) {
            int have = progressOf(r);
            int inInventory = held.getOrDefault(r.itemId(), 0);
            int done = Math.min(have, r.required());
            boolean isComplete = have >= r.required();
            if (isComplete) complete++;
            satisfiedTotal += done;

            materials.add(new MaterialAcquisition(
                r.itemId(),
                r.displayName(),
                r.required(),
                inInventory,
                Math.max(0, r.required() - have),
                acquired.getOrDefault(r.itemId(), 0),
                isComplete
            ));
        }

        long remaining = Math.max(0, totalRequired - satisfiedTotal);
        double percent = totalRequired <= 0 ? 0 : Math.min(100.0, (double) satisfiedTotal / totalRequired * 100.0);

        return new AcquisitionSnapshot(
            state,
            currentMaterial,
            currentSlot,
            currentAmount,
            plan.size(),
            complete,
            totalRequired,
            totalAcquired(),
            remaining,
            failedOperations,
            percent,
            List.copyOf(materials)
        );
    }
}
