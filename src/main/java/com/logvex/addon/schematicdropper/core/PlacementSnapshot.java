package com.logvex.addon.schematicdropper.core;

import java.util.List;

/**
 * Immutable view of a build, produced by {@link PlacementEngine#snapshot()}. The GUI and HUD read
 * only this so they can never observe a half-updated engine.
 */
public record PlacementSnapshot(
    PlacementStateMachine state,
    int totalBlocks,
    int alreadyCorrect,
    int completed,
    int failed,
    int skipped,
    int unresolved,
    int remaining,
    int conflicts,
    long materialsRequired,
    long materialsPlaced,
    double percent,
    BlockPos3 currentPos,
    BlockStateSpec currentTarget,
    String currentMaterial,
    long elapsedMs,
    int retries,
    List<PlannedBlock> blocks
) {
    public static PlacementSnapshot idle() {
        return new PlacementSnapshot(PlacementStateMachine.IDLE, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, null, null, null, 0, 0, List.of());
    }

    public boolean hasErrors() {
        return failed > 0 || unresolved > 0;
    }
}
