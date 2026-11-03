package com.logvex.addon.schematicdropper.core;

/**
 * One block placement the engine intends to perform.
 *
 * @param worldPos  absolute position being filled
 * @param target    the block state that must end up there
 * @param itemId    the item that must be held to place it
 * @param supportPos the neighbour the placement is clicked against, or {@code null} to let the
 *                   adapter pick one
 * @param category  coarse classification, used for ordering and failure explanations
 */
public record PlacementRequest(
    BlockPos3 worldPos,
    BlockStateSpec target,
    String itemId,
    BlockPos3 supportPos,
    BlockCategory category
) {
    public boolean valid() {
        return worldPos != null && target != null && !target.isAir()
            && itemId != null && !itemId.isBlank();
    }
}
