package com.logvex.addon.schematicdropper.core;

/**
 * One block of the schematic, resolved into world coordinates and given a lifecycle state.
 *
 * <p>Immutable: the engine replaces an entry when its state changes so snapshots handed to the GUI
 * can never observe a half-updated plan.
 */
public record PlannedBlock(
    BlockPos3 schematicPos,
    BlockPos3 worldPos,
    BlockStateSpec target,
    PlacementState state,
    BlockCategory category,
    int dependencies,
    String note
) {
    public PlannedBlock withState(PlacementState next, String reason) {
        return new PlannedBlock(schematicPos, worldPos, target, next, category, dependencies, reason);
    }

    public boolean needsPlacement() {
        return state == PlacementState.PENDING || state == PlacementState.BLOCKED
            || state == PlacementState.READY || state == PlacementState.FAILED;
    }

    public String describe() {
        return target.serialize() + " at " + worldPos;
    }
}
