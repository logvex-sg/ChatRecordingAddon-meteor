package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a schematic one verified placement at a time.
 *
 * <p>All work happens inside {@link #tick(long)}, which the module calls once per client tick. The
 * engine never sleeps, never spawns threads, never mutates the world or the inventory itself and
 * never assumes that sending an interaction packet placed a block: a block is only marked
 * {@link PlacementState#COMPLETED} after {@link #tick} re-reads the world and finds it matches.
 *
 * <p>The engine also re-checks the world before every placement, so a block another player placed
 * in the meantime is recognised as already correct and skipped instead of being placed again.
 */
public final class PlacementEngine {
    private final PlacementEnv env;
    private final PlacementAction actions;
    private final PlacementListener listener;
    private BlockTraits traits;

    private PlacementConfig config = PlacementConfig.defaults();

    private PlacementStateMachine state = PlacementStateMachine.IDLE;
    private List<PlannedBlock> plan = List.of();
    private final Map<BlockPos3, Integer> attempts = new HashMap<>();
    private final Set<BlockPos3> givenUp = new HashSet<>();

    private PendingPlacement pending;
    private PlacementRequest activeRequest;
    private long pendingSinceMs;
    private long lastActionMs;
    private long lastRecheckMs;
    private long startedMs;
    private long elapsedMs;
    private int retries;

    private int placedCount;
    private int failedCount;
    private int skippedCount;
    private int alreadyCorrectCount;
    private int unresolvedCount;
    private int conflictCount;
    private long materialsPlaced;
    private long materialsRequired;

    private int perTickBudget = 1;
    private BlockPos3 currentPos;
    private BlockStateSpec currentTarget;
    private String currentMaterial;

    public PlacementEngine(PlacementEnv env, PlacementAction actions, PlacementListener listener) {
        this.env = env;
        this.actions = actions;
        this.listener = listener;
    }

    public void setTraits(BlockTraits traits) {
        this.traits = traits;
    }

    public PlacementConfig config() {
        return config;
    }

    public void setConfig(PlacementConfig config) {
        if (config != null) this.config = config;
    }

    public PlacementStateMachine state() {
        return state;
    }

    public List<PlannedBlock> plan() {
        return plan;
    }

    // ---------------------------------------------------------------- planning

    /**
     * Replaces the plan from a world comparison, running the dependency solver.
     *
     * @return the solver plan (ordered blocks plus any unresolved dependency notes)
     */
    public DependencySolver.Plan setComparison(WorldComparison comparison, Schematic schematic) {
        DependencySolver solver = new DependencySolver(traits);
        DependencySolver.Plan solved = solver.solve(comparison, schematic);
        setPlan(solved.ordered());
        return solved;
    }

    /** Replaces the plan directly. Resets the per-block attempt counters. */
    public void setPlan(List<PlannedBlock> blocks) {
        this.plan = blocks == null ? List.of() : List.copyOf(blocks);
        attempts.clear();
        givenUp.clear();
        recount();
    }

    private void recount() {
        placedCount = 0;
        failedCount = 0;
        skippedCount = 0;
        alreadyCorrectCount = 0;
        unresolvedCount = 0;
        conflictCount = 0;
        materialsRequired = 0;
        materialsPlaced = 0;

        for (PlannedBlock b : plan) {
            materialsRequired++;
            switch (b.state()) {
                case COMPLETED -> {
                    placedCount++;
                    materialsPlaced++;
                }
                case ALREADY_CORRECT -> alreadyCorrectCount++;
                case SKIPPED -> skippedCount++;
                case UNRESOLVED -> unresolvedCount++;
                case FAILED -> failedCount++;
                default -> { /* still pending */ }
            }
        }
    }

    // ---------------------------------------------------------------- controls

    public void start(long nowMs) {
        if (state.isRunning()) return;
        if (plan.isEmpty()) {
            listener.onFeedback("No build plan loaded.");
            return;
        }
        startedMs = nowMs;
        elapsedMs = 0;
        lastActionMs = nowMs - config.operationDelayMs();
        lastRecheckMs = 0;
        pending = null;
        activeRequest = null;
        retries = 0;
        transition(PlacementStateMachine.SCANNING);
        listener.onFeedback("Build started.");
    }

    public void pause() {
        if (!state.isRunning()) return;
        if (state == PlacementStateMachine.VERIFYING && pending != null) {
            listener.onFeedback("Finishing the current placement before pausing.");
            return;
        }
        transition(PlacementStateMachine.PAUSED);
        listener.onFeedback("Build paused.");
    }

    public void resume(long nowMs) {
        if (state != PlacementStateMachine.PAUSED) return;
        lastActionMs = nowMs - config.operationDelayMs();
        transition(PlacementStateMachine.SCANNING);
        listener.onFeedback("Build resumed.");
    }

    public void stop() {
        pending = null;
        activeRequest = null;
        if (actions != null) actions.cancelPending();
        currentPos = null;
        currentTarget = null;
        currentMaterial = null;
        transition(PlacementStateMachine.IDLE);
        listener.onFeedback("Build stopped.");
    }

    /** Discards run state on a world change or disconnect without reporting completion. */
    public void onWorldChanged() {
        pending = null;
        activeRequest = null;
        if (actions != null) actions.cancelPending();
        currentPos = null;
        currentTarget = null;
        currentMaterial = null;
        if (state != PlacementStateMachine.IDLE) transition(PlacementStateMachine.IDLE);
    }

    /** Re-checks the world and returns the block to {@link PlacementState#PENDING}. */
    public void retry(BlockPos3 pos) {
        if (pos == null) return;
        attempts.remove(pos);
        givenUp.remove(pos);
        List<PlannedBlock> next = new ArrayList<>(plan.size());
        for (PlannedBlock b : plan) {
            if (b.worldPos().equals(pos) && b.state() == PlacementState.FAILED) {
                next.add(b.withState(PlacementState.PENDING, "retry requested"));
            } else {
                next.add(b);
            }
        }
        plan = List.copyOf(next);
        recount();
        resumeAfterRetry();
    }

    /** Retries every block that failed. */
    public void retryAllFailed() {
        List<PlannedBlock> next = new ArrayList<>(plan.size());
        for (PlannedBlock b : plan) {
            if (b.state() == PlacementState.FAILED) {
                attempts.remove(b.worldPos());
                givenUp.remove(b.worldPos());
                next.add(b.withState(PlacementState.PENDING, "retry requested"));
            } else {
                next.add(b);
            }
        }
        plan = List.copyOf(next);
        recount();
        resumeAfterRetry();
    }

    /**
     * A build that finished (or errored) with failures is terminal; retrying a failed block must
     * move it back into the active states, or the retry would be silently ignored.
     */
    private void resumeAfterRetry() {
        boolean hasWork = false;
        for (PlannedBlock b : plan) {
            if (b.state() == PlacementState.PENDING || b.state() == PlacementState.BLOCKED) {
                hasWork = true;
                break;
            }
        }
        if (hasWork && (state == PlacementStateMachine.COMPLETED || state == PlacementStateMachine.ERROR
            || state == PlacementStateMachine.IDLE)) {
            transition(PlacementStateMachine.SCANNING);
        }
    }

    // ------------------------------------------------------------------- tick

    public void tick(long nowMs) {
        if (state == PlacementStateMachine.IDLE || state == PlacementStateMachine.PAUSED
            || state == PlacementStateMachine.COMPLETED || state == PlacementStateMachine.ERROR) {
            return;
        }

        if (!env.canRun()) {
            if (state != PlacementStateMachine.WAITING_FOR_MATERIAL) {
                transition(PlacementStateMachine.WAITING_FOR_MATERIAL);
            }
            return;
        }

        adaptBudget();

        // Resolve the transient SCANNING state in the same tick so a build is responsive: one tick
        // should be enough to start the next placement, not two.
        if (state == PlacementStateMachine.SCANNING) {
            if (plan.isEmpty()) {
                transition(PlacementStateMachine.IDLE);
                return;
            }
            if (allResolved()) {
                complete(nowMs);
                return;
            }
            transition(PlacementStateMachine.PLACING);
        }

        switch (state) {
            case PLACING -> tickPlacing(nowMs);
            case VERIFYING -> tickVerifying(nowMs);
            case WAITING_FOR_SUPPORT, WAITING_FOR_MATERIAL -> tickWaiting(nowMs);
            default -> { /* handled above */ }
        }
    }

    /**
     * Chooses how many placements to attempt this tick.
     *
     * <p>With adaptive throttling the budget stays at the configured maximum while placements
     * succeed and drops to {@code minOperationsPerTick} as soon as one fails, so a struggling server
     * is not flooded. Correctness is never traded for speed: the budget only limits attempts, never
     * skips verification.
     */
    private void adaptBudget() {
        int max = Math.max(1, config.operationsPerTick());
        if (!config.adaptiveThrottle()) {
            perTickBudget = max;
            return;
        }
        int min = Math.max(1, Math.min(config.minOperationsPerTick(), max));
        if (failedCount > 0 || retries > 0) {
            perTickBudget = min;
        } else {
            perTickBudget = Math.min(max, perTickBudget + 1);
            if (perTickBudget < min) perTickBudget = min;
        }
    }

    private void tickPlacing(long nowMs) {
        if (allResolved()) {
            complete(nowMs);
            return;
        }

        int budget = perTickBudget;
        for (int i = 0; i < budget; i++) {
            if (nowMs - lastActionMs < config.operationDelayMs()) return;
            if (!placeNext(nowMs)) return;
        }
    }

    /** Finds the next placeable block and starts its placement. @return false when nothing ran. */
    private boolean placeNext(long nowMs) {
        PlannedBlock next = selectNext();
        if (next == null) {
            // Everything left is blocked on support or material; wait and re-check later.
            if (needsSupport()) transition(PlacementStateMachine.WAITING_FOR_SUPPORT);
            else transition(PlacementStateMachine.WAITING_FOR_MATERIAL);
            return false;
        }

        BlockPos3 pos = next.worldPos();

        // Re-check the world before every placement: another player may have built this already.
        if (!env.isLoaded(pos)) {
            fail(next, PlacementFailure.CHUNK_UNLOADED, "chunk not loaded");
            return false;
        }
        if (matchesWorld(next)) {
            markState(next, PlacementState.ALREADY_CORRECT, "already present");
            alreadyCorrectCount++;
            transition(PlacementStateMachine.SCANNING);
            return false;
        }
        BlockStateSpec found = env.stateAt(pos);
        if (found != null && !found.isAir() && !env.isReplaceable(pos)
            && !found.sameBlock(next.target())) {
            // Something unrelated is here now. Never replace it silently.
            handleConflict(next, found);
            return false;
        }

        // Support check for gravity and attached blocks.
        if (!hasSupport(next)) {
            if (attempts.getOrDefault(pos, 0) >= config.retryLimit()) {
                giveUp(next, PlacementFailure.MISSING_SUPPORT, "support was never built");
            } else {
                markState(next, PlacementState.BLOCKED, "waiting for support");
            }
            return false;
        }

        if (env.countOf(next.target().id()) <= 0) {
            markState(next, PlacementState.BLOCKED, "waiting for material");
            transition(PlacementStateMachine.WAITING_FOR_MATERIAL);
            return false;
        }

        if (!env.canPlace()) {
            markState(next, PlacementState.BLOCKED, "cannot place right now");
            return false;
        }

        double dist = env.distanceSquaredTo(pos);
        if (dist >= 0 && dist > config.maxRange() * config.maxRange()) {
            fail(next, PlacementFailure.OUT_OF_RANGE, "position is out of range");
            return false;
        }

        BlockPos3 support = pickSupport(next);
        PlacementRequest request = new PlacementRequest(pos, next.target(), next.target().id(),
            support, next.category());
        if (!request.valid()) {
            fail(next, PlacementFailure.INVALID_PLACEMENT, "invalid request");
            return false;
        }

        currentPos = pos;
        currentTarget = next.target();
        currentMaterial = next.target().id();

        pending = actions.begin(request);
        activeRequest = request;
        pendingSinceMs = nowMs;
        if (pending == null) {
            fail(next, PlacementFailure.SERVER_REJECTED, "the placement could not be sent");
            return false;
        }

        markState(next, PlacementState.PLACING, null);
        transition(PlacementStateMachine.VERIFYING);
        return true;
    }

    private void tickVerifying(long nowMs) {
        if (pending == null) {
            transition(PlacementStateMachine.SCANNING);
            return;
        }

        PendingPlacement.Status status = pending.poll();
        switch (status) {
            case PENDING -> {
                if (nowMs - pendingSinceMs > config.verifyTimeoutMs()) {
                    resolvePending(PlacementFailure.SERVER_REJECTED, "the server did not respond in time", nowMs);
                }
            }
            case CONFIRMED -> {
                // Even a CONFIRMED handle is only trusted after the engine itself sees the block.
                PlannedBlock block = find(currentPos);
                pending = null;
                activeRequest = null;
                if (block != null && config.verifyPlacements() && !matchesWorld(block)) {
                    resolvePending(PlacementFailure.VERIFY_FAILED, "the world did not change", nowMs);
                } else if (block != null) {
                    markState(block, PlacementState.COMPLETED, null);
                    placedCount++;
                    materialsPlaced++;
                    attempts.remove(block.worldPos());
                    retries = 0;
                    lastActionMs = nowMs;
                    currentPos = null;
                    currentTarget = null;
                    currentMaterial = null;
                    listener.onPlaced(block.worldPos(), block.target());
                    transition(PlacementStateMachine.SCANNING);
                } else {
                    transition(PlacementStateMachine.SCANNING);
                }
            }
            case REJECTED -> resolvePending(
                pending.failure() == null ? PlacementFailure.SERVER_REJECTED : pending.failure(),
                "the server rejected the placement", nowMs);
            case TIMEOUT -> resolvePending(PlacementFailure.SERVER_REJECTED, "the placement timed out", nowMs);
        }
    }

    /** Handles a failed placement: retry while under the limit, otherwise mark it failed. */
    private void resolvePending(PlacementFailure failure, String detail, long nowMs) {
        pending = null;
        PlannedBlock block = find(currentPos);
        activeRequest = null;
        currentPos = null;
        currentTarget = null;
        currentMaterial = null;

        if (block == null) {
            transition(PlacementStateMachine.SCANNING);
            return;
        }

        int used = attempts.merge(block.worldPos(), 1, Integer::sum);
        if (failure.recoverable() && used < config.retryLimit() && !givenUp.contains(block.worldPos())) {
            retries++;
            markState(block, PlacementState.PENDING, "retrying after " + failure);
            lastActionMs = nowMs + config.retryBackoffMs();
            transition(PlacementStateMachine.SCANNING);
            return;
        }

        giveUp(block, failure, detail);
    }

    private void giveUp(PlannedBlock block, PlacementFailure failure, String detail) {
        givenUp.add(block.worldPos());
        markState(block, PlacementState.FAILED, failure + ": " + detail);
        failedCount++;
        listener.onFailed(block.worldPos(), failure, detail);
    }

    private void fail(PlannedBlock block, PlacementFailure failure, String detail) {
        givenUp.add(block.worldPos());
        markState(block, PlacementState.FAILED, failure + ": " + detail);
        failedCount++;
        listener.onFailed(block.worldPos(), failure, detail);
        transition(PlacementStateMachine.SCANNING);
    }

    private void handleConflict(PlannedBlock block, BlockStateSpec found) {
        conflictCount++;
        listener.onConflict(block.worldPos(), block.target(), found);
        ConflictPolicy policy = config.conflictPolicy();
        switch (policy == null ? ConflictPolicy.SKIP : policy) {
            case REPLACE -> {
                // Only reachable when the world reported the block as replaceable, which the caller
                // already checked; a genuine structure never lands here.
                markState(block, PlacementState.PENDING, "replacing a replaceable block");
            }
            case PAUSE -> {
                markState(block, PlacementState.SKIPPED, "conflict; paused for review");
                skippedCount++;
                transition(PlacementStateMachine.PAUSED);
                listener.onFeedback("Build paused: the world does not match the schematic at " + block.worldPos() + ".");
            }
            case SKIP -> {
                markState(block, PlacementState.SKIPPED, "conflict; left as found");
                skippedCount++;
            }
        }
    }

    private void tickWaiting(long nowMs) {
        if (lastRecheckMs != 0 && nowMs - lastRecheckMs < config.recheckIntervalMs()) return;
        lastRecheckMs = nowMs;
        transition(PlacementStateMachine.SCANNING);
    }

    // ---------------------------------------------------------------- selection

    private PlannedBlock selectNext() {
        for (PlannedBlock b : plan) {
            if (givenUp.contains(b.worldPos())) continue;
            PlacementState s = b.state();
            if (s == PlacementState.PENDING || s == PlacementState.BLOCKED || s == PlacementState.READY) {
                return b;
            }
        }
        return null;
    }

    private boolean needsSupport() {
        for (PlannedBlock b : plan) {
            if (b.state() == PlacementState.BLOCKED && "waiting for support".equals(b.note())) return true;
        }
        return false;
    }

    /** True when the block needs a support and none of its dependency positions is solid. */
    private boolean hasSupport(PlannedBlock block) {
        List<BlockPos3> deps = PlacementDependencies.of(block.target(), block.worldPos(), traits);
        if (deps.isEmpty()) return true;
        for (BlockPos3 dep : deps) {
            BlockStateSpec depState = env.stateAt(dep);
            if (depState != null && !depState.isAir() && !env.isReplaceable(dep)) return true;
        }
        return false;
    }

    private BlockPos3 pickSupport(PlannedBlock block) {
        List<BlockPos3> deps = PlacementDependencies.of(block.target(), block.worldPos(), traits);
        for (BlockPos3 dep : deps) {
            BlockStateSpec depState = env.stateAt(dep);
            if (depState != null && !depState.isAir() && !env.isReplaceable(dep)) return dep;
        }
        return null;
    }

    /** True when the world already holds exactly the required state at the block's position. */
    private boolean matchesWorld(PlannedBlock block) {
        BlockStateSpec found = env.stateAt(block.worldPos());
        return found != null && block.target().id().equals(found.id()) && block.target().matches(found);
    }

    private boolean allResolved() {
        for (PlannedBlock b : plan) {
            PlacementState s = b.state();
            if (s == PlacementState.PENDING || s == PlacementState.BLOCKED
                || s == PlacementState.READY || s == PlacementState.PLACING) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private PlannedBlock find(BlockPos3 pos) {
        if (pos == null) return null;
        for (PlannedBlock b : plan) if (b.worldPos().equals(pos)) return b;
        return null;
    }

    private void markState(PlannedBlock block, PlacementState next, String note) {
        List<PlannedBlock> updated = new ArrayList<>(plan.size());
        for (PlannedBlock b : plan) {
            if (b.worldPos().equals(block.worldPos())) updated.add(b.withState(next, note));
            else updated.add(b);
        }
        plan = List.copyOf(updated);
    }

    private void transition(PlacementStateMachine next) {
        if (state == next) return;
        state = next;
        listener.onStateChanged(next);
    }

    private void complete(long nowMs) {
        elapsedMs = nowMs - startedMs;
        transition(PlacementStateMachine.COMPLETED);
        listener.onCompleted(placedCount, failedCount, elapsedMs);
    }

    // ---------------------------------------------------------------- snapshot

    public PlacementSnapshot snapshot() {
        int remaining = 0;
        for (PlannedBlock b : plan) {
            if (b.needsPlacement() || b.state() == PlacementState.PLACING) remaining++;
        }
        double percent = plan.isEmpty() ? 0 : (double) (placedCount + alreadyCorrectCount) / plan.size() * 100.0;

        return new PlacementSnapshot(
            state, plan.size(), alreadyCorrectCount, placedCount, failedCount, skippedCount,
            unresolvedCount, remaining, conflictCount, materialsRequired, materialsPlaced,
            Math.min(100.0, percent), currentPos, currentTarget, currentMaterial,
            elapsedMs, retries, List.copyOf(plan)
        );
    }

    /** Failed blocks with their reasons, for the error viewer. */
    public List<PlannedBlock> failures() {
        List<PlannedBlock> out = new ArrayList<>();
        for (PlannedBlock b : plan) {
            if (b.state() == PlacementState.FAILED || b.state() == PlacementState.UNRESOLVED
                || b.state() == PlacementState.SKIPPED) {
                out.add(b);
            }
        }
        return out;
    }

    /** The last placement request sent, or {@code null}. Used by the 3D preview. */
    public PlacementRequest activeRequest() {
        return activeRequest;
    }

    /** Exposes the plan for checkpointing. */
    public Map<BlockPos3, String> completedPositions() {
        Map<BlockPos3, String> out = new LinkedHashMap<>();
        for (PlannedBlock b : plan) {
            if (b.state() == PlacementState.COMPLETED || b.state() == PlacementState.ALREADY_CORRECT) {
                out.put(b.worldPos(), b.target().serialize());
            }
        }
        return out;
    }
}
