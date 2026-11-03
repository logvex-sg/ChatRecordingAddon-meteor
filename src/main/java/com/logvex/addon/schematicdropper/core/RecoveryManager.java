package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether an interrupted build can be resumed, and what must be re-checked first.
 *
 * <p>Recovery never trusts a checkpoint blindly. A resume is only offered when the checkpoint
 * passes its integrity checks (same schematic, same origin, supported version) and the world is
 * available. Every position the checkpoint claims is complete is then re-validated against the live
 * world: a position that no longer matches is put back into the plan, so a build that was partly
 * destroyed while the game was closed is rebuilt instead of being reported as finished.
 */
public final class RecoveryManager {
    /** The outcome of planning a resume. */
    public record Plan(
        boolean canResume,
        String reason,
        List<PlannedBlock> pending,
        int verifiedComplete,
        int invalidated,
        int totalBlocks
    ) {
        public boolean hasPendingWork() {
            return !pending.isEmpty();
        }
    }

    private final CheckpointStore store;

    public RecoveryManager(CheckpointStore store) {
        this.store = store == null ? CheckpointStore.inMemory() : store;
    }

    /**
     * Builds a resume plan.
     *
     * @param schematic the schematic being built
     * @param origin    the origin the build uses
     * @param comparison the fresh world comparison for that origin
     */
    public Plan planResume(Schematic schematic, BlockPos3 origin, WorldComparison comparison) {
        if (schematic == null) {
            return new Plan(false, "no schematic loaded", List.of(), 0, 0, 0);
        }
        if (comparison == null) {
            return new Plan(false, "the world is not available", List.of(), 0, 0, 0);
        }

        Optional<Checkpoint> loaded = store.load(schematic.name());
        if (loaded.isEmpty()) {
            return new Plan(false, "no checkpoint for this schematic", List.of(), 0, 0, 0);
        }

        Checkpoint checkpoint = loaded.get();
        Checkpoint.Integrity integrity = checkpoint.validate(Checkpoint.fingerprint(schematic), origin);
        if (!integrity.valid()) {
            return new Plan(false, integrity.reason(), List.of(), 0, 0, 0);
        }

        // Re-validate: a checkpointed position only stays "complete" if the world still matches.
        List<PlannedBlock> pending = new ArrayList<>();
        int verified = 0;
        int invalidated = 0;

        for (WorldComparison.Entry entry : comparison.entries()) {
            boolean claimed = checkpoint.completed().containsKey(entry.worldPos());
            if (!claimed) {
                if (entry.status() == ComparisonStatus.MISSING || entry.status() == ComparisonStatus.INCORRECT) {
                    pending.add(new PlannedBlock(entry.schematicPos(), entry.worldPos(), entry.target(),
                        PlacementState.PENDING, BlockCategory.SIMPLE, 0, null));
                }
                continue;
            }

            if (entry.status() == ComparisonStatus.CORRECT) {
                verified++;
            } else {
                // The checkpoint says done, the world disagrees: rebuild it.
                invalidated++;
                pending.add(new PlannedBlock(entry.schematicPos(), entry.worldPos(), entry.target(),
                    PlacementState.PENDING, BlockCategory.SIMPLE, 0, "checkpoint position no longer matches"));
            }
        }

        String reason = invalidated == 0
            ? "resumed " + verified + " confirmed positions"
            : "resumed " + verified + " positions; " + invalidated + " had to be rebuilt";
        return new Plan(true, reason, List.copyOf(pending), verified, invalidated, checkpoint.totalBlocks());
    }

    /** Saves the current engine state as a checkpoint. */
    public void save(Schematic schematic, BlockPos3 origin, PlacementEngine engine, long nowMs) {
        if (schematic == null || engine == null) return;
        store.save(Checkpoint.capture(schematic.name(), Checkpoint.fingerprint(schematic), origin, engine, nowMs));
    }

    public CheckpointStore store() {
        return store;
    }
}
