package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The execution state machine.
 *
 * <p>All work happens inside {@link #tick(long)}, which the module calls once per client tick.
 * The engine never sleeps, never spawns threads and never mutates the inventory itself: it asks
 * {@link ActionPort} to start a drop and then waits for the server to confirm it through
 * {@link PendingDrop}. Progress only advances on confirmation.
 */
public final class DropEngine {
    private final EnvPort env;
    private final RotationPort rotation;
    private final ActionPort actions;
    private final ProgressTracker tracker;
    private final EngineListener listener;

    private DropConfig config = DropConfig.defaults();
    private StackStrategy stackStrategy = StackStrategy.LARGEST_FIRST;

    private PendingDrop pending;
    private long pendingSinceMs;
    private long lastActionMs;
    private long lastScanMs;
    private long startedMs;
    private long elapsedMs;

    private DropPlan planned;
    private Rotation originalRotation;
    private Rotation lastTargetRotation;
    private long lastTargetUpdateMs;

    private int retries;
    private final Set<String> blocked = new HashSet<>();
    private String activeMaterial;
    private int activeSlot = -1;

    /**
     * A pause that arrived while a transfer was awaiting confirmation. It is honoured as soon as
     * that transfer resolves, so the Pause button always takes effect instead of doing nothing
     * whenever the run happened to be mid-confirmation.
     */
    private boolean pauseRequested;

    public DropEngine(EnvPort env, RotationPort rotation, ActionPort actions,
                      ProgressTracker tracker, EngineListener listener) {
        this.env = env;
        this.rotation = rotation;
        this.actions = actions;
        this.tracker = tracker;
        this.listener = listener;
    }

    public ProgressTracker tracker() {
        return tracker;
    }

    public DropConfig config() {
        return config;
    }

    public void setConfig(DropConfig config) {
        if (config != null) this.config = config;
    }

    public void setStackStrategy(StackStrategy strategy) {
        if (strategy != null) this.stackStrategy = strategy;
    }

    public State state() {
        return tracker.state();
    }

    public ProgressSnapshot snapshot() {
        return tracker.snapshot();
    }

    // ---------------------------------------------------------------- controls

    public void start(long nowMs) {
        if (tracker.state().isRunning()) return;
        pending = null;
        retries = 0;
        blocked.clear();
        startedMs = nowMs;
        elapsedMs = 0;
        lastActionMs = nowMs - config.operationDelayMs();
        lastScanMs = 0;
        originalRotation = null;
        transition(State.SCANNING);
        listener.onFeedback("Started.");
    }

    public void pause() {
        if (!tracker.state().isRunning()) return;
        if (pending != null) {
            // Cannot safely abandon an in-flight transaction; let it resolve first. The request is
            // remembered and applied in applyPendingPause once the transfer confirms or fails.
            pauseRequested = true;
            listener.onFeedback("Finishing current confirmation before pausing...");
            return;
        }
        pauseRequested = false;
        restoreRotationIfNeeded();
        transition(State.PAUSED);
        listener.onFeedback("Paused.");
    }

    public void resume(long nowMs) {
        if (tracker.state() != State.PAUSED) return;
        pauseRequested = false;
        lastActionMs = nowMs - config.operationDelayMs();
        transition(State.SCANNING);
        listener.onFeedback("Resumed.");
    }

    /**
     * Applies a pause that was requested while a transfer was in flight. Caller is inside the tick.
     *
     * <p>The check is "nothing in flight" rather than a specific state: a confirmed transfer moves
     * the machine to {@code SELECTING}, not {@code SCANNING}, so matching only one state would drop
     * the request and the pause button would appear to do nothing.
     */
    private void applyPendingPause() {
        if (!pauseRequested) return;
        if (pending != null || !tracker.state().isRunning()) return;
        pauseRequested = false;
        restoreRotationIfNeeded();
        transition(State.PAUSED);
        listener.onFeedback("Paused.");
    }

    public void stop() {
        pending = null;
        pauseRequested = false;
        actions.cancelPending();
        restoreRotationIfNeeded();
        activeMaterial = null;
        activeSlot = -1;
        tracker.clearCurrentStack();
        tracker.setCurrentMaterial(null);
        transition(State.IDLE);
        listener.onFeedback("Stopped.");
    }

    /**
     * Resets confirmed progress. Because progress is only ever advanced from confirmed transfers,
     * clearing it cannot cause an already-dropped item to be dropped again: the next run simply
     * re-measures the (now smaller) inventory.
     */
    public void resetProgress() {
        stop();
        tracker.resetProgress();
        blocked.clear();
        retries = 0;
        transition(State.IDLE);
        listener.onFeedback("Progress reset.");
    }

    public void requestRescan(long nowMs) {
        lastScanMs = 0;
        if (tracker.state() == State.IDLE || tracker.state() == State.COMPLETED) {
            transition(State.SCANNING);
        }
        listener.onFeedback("Rescanning inventory.");
    }

    /** Discards run state on world change or disconnect without emitting completion. */
    public void onWorldChanged() {
        pending = null;
        pauseRequested = false;
        actions.cancelPending();
        blocked.clear();
        retries = 0;
        activeMaterial = null;
        activeSlot = -1;
        originalRotation = null;
        tracker.clearCurrentStack();
        tracker.setCurrentMaterial(null);
        tracker.clearInventory();
        if (tracker.state() != State.IDLE) transition(State.IDLE);
    }

    // ------------------------------------------------------------------- tick

    public void tick(long nowMs) {
        State state = tracker.state();
        if (state == State.IDLE || state == State.PAUSED || state == State.COMPLETED) return;

        // Honour a pause requested while a transfer was in flight, before any new work starts.
        applyPendingPause();
        if (tracker.state() == State.PAUSED) return;

        if (!env.canRun()) {
            if (state != State.WAITING_FOR_INVENTORY) {
                transition(State.WAITING_FOR_INVENTORY);
            }
            return;
        }

        switch (state) {
            case SCANNING -> tickScanning(nowMs);
            case SELECTING -> tickSelecting(nowMs);
            case ROTATING -> tickRotating(nowMs);
            case DROPPING -> tickDropping(nowMs);
            case WAITING_FOR_CONFIRMATION -> tickWaiting(nowMs);
            case WAITING_FOR_INVENTORY -> tickWaitingForInventory(nowMs);
            case ERROR -> { /* stays until reset or rescan */ }
            default -> { /* IDLE / PAUSED / COMPLETED handled above */ }
        }
    }

    private void tickScanning(long nowMs) {
        refreshInventory();

        if (tracker.plan().isEmpty()) {
            transition(State.IDLE);
            listener.onFeedback("No material plan loaded.");
            return;
        }

        blocked.clear();
        if (tracker.isComplete()) {
            complete(nowMs);
            return;
        }

        lastScanMs = nowMs;
        transition(State.SELECTING);
    }

    private void tickSelecting(long nowMs) {
        if (tracker.isComplete()) {
            complete(nowMs);
            return;
        }

        List<Requirement> ordered = MaterialOrder.order(tracker.plan(), config.orderingMode());
        Requirement chosen = null;
        boolean sawPendingWithoutStock = false;

        for (Requirement r : ordered) {
            if (tracker.remaining(r.itemId()) <= 0) continue;

            if (blocked.contains(r.itemId())) continue;

            List<StackEntry> stacks = env.stacksOf(r.itemId());
            if (stacks.isEmpty()) {
                sawPendingWithoutStock = true;
                continue;
            }

            chosen = r;
            break;
        }

        if (chosen == null) {
            // Nothing droppable right now.
            if (config.recoveryMode() == RecoveryMode.BEST_EFFORT && sawPendingWithoutStock && blocked.isEmpty()) {
                // Every remaining material is out of stock; wait rather than reporting completion.
                transition(State.WAITING_FOR_INVENTORY);
                return;
            }
            if (config.pauseOnMissing() || sawPendingWithoutStock) {
                transition(State.WAITING_FOR_INVENTORY);
                return;
            }
            // Nothing left that can be processed and nothing missing.
            complete(nowMs);
            return;
        }

        int remaining = tracker.remaining(chosen.itemId());
        List<StackEntry> stacks = env.stacksOf(chosen.itemId());
        StackEntry stack = chooseStack(stacks, remaining);
        if (stack == null || stack.isEmpty()) {
            transition(State.WAITING_FOR_INVENTORY);
            return;
        }

        DropAction action = decideAction(stack, remaining);

        activeMaterial = chosen.itemId();
        activeSlot = stack.slot();
        planned = new DropPlan(chosen.itemId(), stack.slot(), action,
            action == DropAction.DROP_STACK ? stack.count() : 1, stack.count());

        tracker.setCurrentMaterial(chosen.itemId());
        tracker.setCurrentStack(stack.slot(), stack.count());

        transition(State.ROTATING);
    }

    private void tickRotating(long nowMs) {
        if (!config.rotationEnabled()) {
            transition(State.DROPPING);
            return;
        }

        Rotation target = desiredRotation(nowMs);
        if (target == null) {
            fail(nowMs, FailureType.TARGET_INVALID, "No valid target to rotate toward.");
            return;
        }

        if (originalRotation == null) originalRotation = env.currentRotation();

        boolean instant = config.rotationMode() == RotationMode.INSTANT;
        Rotation applied = rotation.apply(target, instant, config.rotationSpeed());

        if (instant || applied == null
            || (RotationMath.within(applied.yaw(), target.yaw(), 2.0f)
                && RotationMath.within(applied.pitch(), target.pitch(), 2.0f))) {
            transition(State.DROPPING);
        }
    }

    private void tickDropping(long nowMs) {
        if (nowMs - lastActionMs < config.operationDelayMs()) return;

        if (planned == null) {
            transition(State.SELECTING);
            return;
        }

        if (!env.canInteract()) {
            fail(nowMs, FailureType.SCREEN_INVALID, "Screen handler is not ready.");
            return;
        }

        if (!actions.prepare()) return;

        if (env.countOf(planned.itemId()) < planned.expectedOut()) {
            fail(nowMs, FailureType.INVENTORY_CHANGED, "Stack moved before the drop could be sent.");
            return;
        }

        pending = actions.begin(planned);
        pendingSinceMs = nowMs;
        if (pending == null) {
            fail(nowMs, FailureType.UNKNOWN_ERROR, "Drop could not be started.");
            return;
        }
        transition(State.WAITING_FOR_CONFIRMATION);
    }

    private void tickWaiting(long nowMs) {
        if (pending == null) {
            transition(State.SELECTING);
            return;
        }

        PendingDrop.Status status = pending.poll();
        switch (status) {
            case PENDING -> {
                if (nowMs - pendingSinceMs > config.syncTimeoutMs()) {
                    pending = null;
                    fail(nowMs, FailureType.SERVER_TIMEOUT, "Server did not confirm the drop in time.");
                }
            }
            case CONFIRMED -> {
                int out = Math.max(1, pending.confirmedOut());
                String item = activeMaterial;
                pending = null;
                retries = 0;
                tracker.addConfirmed(item, out);
                tracker.clearMissing();
                tracker.clearCurrentStack();
                lastActionMs = nowMs;

                int remaining = tracker.remaining(item);
                listener.onConfirmed(item, out, remaining);

                if (tracker.isComplete()) {
                    complete(nowMs);
                } else {
                    transition(State.SELECTING);
                }
            }
            case REJECTED -> {
                FailureType type = pending.failure() == null ? FailureType.SERVER_REJECTION : pending.failure();
                pending = null;
                fail(nowMs, type, "Server rejected the drop.");
            }
            case TIMEOUT -> {
                pending = null;
                fail(nowMs, FailureType.SERVER_TIMEOUT, "Drop timed out.");
            }
        }
    }

    private void tickWaitingForInventory(long nowMs) {
        if (lastScanMs == 0 || nowMs - lastScanMs >= config.rescanIntervalMs()) {
            refreshInventory();
            lastScanMs = nowMs;

            boolean anyAvailable = false;
            for (Requirement r : tracker.plan().requirements()) {
                if (tracker.remaining(r.itemId()) > 0 && env.countOf(r.itemId()) > 0) {
                    anyAvailable = true;
                    break;
                }
            }
            if (anyAvailable) {
                blocked.clear();
                transition(State.SELECTING);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void refreshInventory() {
        Map<String, Integer> snapshot = env.inventorySnapshot();
        tracker.clearMissing();
        for (Requirement r : tracker.plan().requirements()) {
            int held = snapshot.getOrDefault(r.itemId(), 0);
            tracker.setInventory(r.itemId(), held);
            int remaining = tracker.remaining(r.itemId());
            if (remaining > 0 && held <= 0) {
                tracker.setMissing(r.itemId(), remaining);
            }
        }
    }

    private Rotation desiredRotation(long nowMs) {
        if (!config.rotationEnabled()) return null;
        if (lastTargetRotation != null && nowMs - lastTargetUpdateMs < config.targetUpdateMs()) {
            return lastTargetRotation;
        }
        Rotation target = env.desiredRotation();
        if (target == null) return null;
        if (!RotationMath.isFinite(target.yaw()) || !RotationMath.isFinite(target.pitch())) return null;
        lastTargetRotation = target;
        lastTargetUpdateMs = nowMs;
        return target;
    }

    private StackEntry chooseStack(List<StackEntry> stacks, int remaining) {
        if (stacks.isEmpty()) return null;
        List<StackEntry> usable = new ArrayList<>(stacks.size());
        for (StackEntry s : stacks) if (!s.isEmpty()) usable.add(s);
        if (usable.isEmpty()) return null;

        return switch (stackStrategy) {
            case LARGEST_FIRST -> usable.stream().max((a, b) -> Integer.compare(a.count(), b.count())).orElse(usable.get(0));
            case SMALLEST_FIRST -> usable.stream().min((a, b) -> Integer.compare(a.count(), b.count())).orElse(usable.get(0));
            case HOTBAR_FIRST -> {
                StackEntry best = usable.get(0);
                for (StackEntry s : usable) {
                    if (s.slot() < best.slot()) best = s;
                }
                yield best;
            }
        };
    }

    private DropAction decideAction(StackEntry stack, int remaining) {
        if (config.dropAction() == DropAction.DROP_STACK && stack.count() <= remaining) {
            return DropAction.DROP_STACK;
        }
        return DropAction.DROP_ONE;
    }

    private void fail(long nowMs, FailureType type, String detail) {
        retries++;
        tracker.recordFailure();
        listener.onFailure(type, detail);
        tracker.clearCurrentStack();

        if (!type.recoverable()) {
            transition(State.ERROR);
            return;
        }

        if (retries < config.retryLimit()) {
            lastActionMs = nowMs + config.retryBackoffMs();
            transition(State.SELECTING);
            return;
        }

        retries = 0;
        if (config.recoveryMode() == RecoveryMode.BEST_EFFORT && activeMaterial != null) {
            blocked.add(activeMaterial);
            transition(State.SELECTING);
        } else {
            transition(State.WAITING_FOR_INVENTORY);
        }
    }

    private void complete(long nowMs) {
        if (startedMs > 0) elapsedMs = nowMs - startedMs;
        restoreRotationIfNeeded();
        tracker.clearCurrentStack();
        tracker.setCurrentMaterial(null);
        transition(State.COMPLETED);
        listener.onCompleted(tracker.totalConfirmed(), elapsedMs);
    }

    private void restoreRotationIfNeeded() {
        if (config.restoreRotation() && originalRotation != null && config.rotationEnabled()) {
            rotation.restore(originalRotation);
        }
        originalRotation = null;
        lastTargetRotation = null;
    }

    private void transition(State next) {
        if (tracker.state() == next) return;
        tracker.setState(next);
        listener.onStateChanged(next);
    }
}
