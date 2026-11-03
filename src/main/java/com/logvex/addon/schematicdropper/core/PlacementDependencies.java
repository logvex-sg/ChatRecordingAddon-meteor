package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Which positions a block must have in place before it can be placed.
 *
 * <p>Extracted so the dependency solver and the placement engine share one definition of a
 * dependency; a divergence between the planned order and the runtime check would otherwise let a
 * gravity block be placed before its support.
 *
 * <p>Rules follow verified Minecraft behaviour:
 * <ul>
 *   <li>Gravity blocks (sand, gravel, concrete powder) and floor-attached blocks (torch, plant,
 *       rail, redstone wire, carpet) need the cell below.</li>
 *   <li>Wall-attached blocks (wall torch, ladder, lever, wall sign/banner) need any solid
 *       horizontal neighbour.</li>
 * </ul>
 */
public final class PlacementDependencies {
    private PlacementDependencies() {
    }

    /** Candidate support positions, in a stable order. Empty when the block is self-supporting. */
    public static List<BlockPos3> of(BlockStateSpec target, BlockPos3 worldPos, BlockTraits traits) {
        List<BlockPos3> deps = new ArrayList<>(4);
        if (target == null || worldPos == null) return deps;

        String id = target.id();
        if (isGravity(id, traits) || isFloorAttached(id, traits)) {
            deps.add(worldPos.below());
        }
        if (isWallAttached(id)) {
            deps.addAll(List.of(worldPos.horizontalNeighbours()));
        }
        return deps;
    }

    /** True when the block needs no support at all. */
    public static boolean isSelfSupporting(BlockStateSpec target, BlockTraits traits) {
        return of(target, new BlockPos3(0, 0, 0), traits).isEmpty();
    }

    public static boolean isGravity(String id, BlockTraits traits) {
        return traits != null && traits.isGravityAffected(id);
    }

    public static boolean isFloorAttached(String id, BlockTraits traits) {
        return traits != null && traits.requiresSupport(id) && !isWallAttached(id);
    }

    public static boolean isWallAttached(String id) {
        if (id == null) return false;
        return id.endsWith("_wall_torch") || id.endsWith("_wall_sign")
            || id.endsWith("_wall_banner") || id.endsWith("_ladder") || id.endsWith("_lever");
    }
}
