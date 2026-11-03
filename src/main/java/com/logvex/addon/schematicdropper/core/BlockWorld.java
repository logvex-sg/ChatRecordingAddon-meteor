package com.logvex.addon.schematicdropper.core;

/**
 * Read access to the live world at schematic coordinates.
 *
 * <p>The comparison layer only ever reads through this interface; the Minecraft adapter implements
 * it over the client world. Keeping it abstract means conflict detection and placement planning are
 * unit testable with an in-memory world.
 */
public interface BlockWorld {
    /** The block currently at an absolute world position. */
    BlockStateSpec stateAt(BlockPos3 worldPos);

    /** True when a block can be replaced by a placement without breaking something first. */
    boolean isReplaceable(BlockPos3 worldPos);

    /** True when the chunk containing the position is loaded and can be trusted. */
    boolean isLoaded(BlockPos3 worldPos);

    /** A world with nothing but air; used when no world is available. */
    static BlockWorld empty() {
        return new BlockWorld() {
            @Override
            public BlockStateSpec stateAt(BlockPos3 worldPos) {
                return BlockStateSpec.air();
            }

            @Override
            public boolean isReplaceable(BlockPos3 worldPos) {
                return true;
            }

            @Override
            public boolean isLoaded(BlockPos3 worldPos) {
                return true;
            }
        };
    }
}
