package com.logvex.addon.schematicdropper.core;

/**
 * Callbacks for user-visible placement feedback. Implemented by the module; never called from a
 * background thread.
 */
public interface PlacementListener {
    void onStateChanged(PlacementStateMachine state);

    void onPlaced(BlockPos3 pos, BlockStateSpec state);

    void onFailed(BlockPos3 pos, PlacementFailure failure, String detail);

    void onConflict(BlockPos3 pos, BlockStateSpec expected, BlockStateSpec found);

    void onCompleted(long placed, long failed, long elapsedMs);

    void onFeedback(String message);
}
