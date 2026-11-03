package com.logvex.addon.schematicdropper.core;

import java.util.List;

/**
 * A rectangular sub-region of a schematic.
 *
 * <p>Large schematics are split into zones so a build can be done region by region. A zone holds the
 * schematic-local block positions it covers; its material requirement is the sum of the block ids it
 * contains.
 */
public final class BuildZone {
    private final int index;
    private final BlockPos3 min;
    private final BlockPos3 max;
    private final List<BlockPos3> positions;

    public BuildZone(int index, BlockPos3 min, BlockPos3 max, List<BlockPos3> positions) {
        this.index = index;
        this.min = min;
        this.max = max;
        this.positions = List.copyOf(positions == null ? List.of() : positions);
    }

    public int index() {
        return index;
    }

    public BlockPos3 min() {
        return min;
    }

    public BlockPos3 max() {
        return max;
    }

    public List<BlockPos3> positions() {
        return positions;
    }

    public int blockCount() {
        return positions.size();
    }

    public boolean contains(BlockPos3 pos) {
        return pos.x() >= min.x() && pos.x() <= max.x()
            && pos.y() >= min.y() && pos.y() <= max.y()
            && pos.z() >= min.z() && pos.z() <= max.z();
    }

    /** A short label for the GUI, e.g. {@code Zone 3 (12x8x5)}. */
    public String label() {
        return "Zone " + (index + 1) + " (" + (max.x() - min.x() + 1) + "x"
            + (max.y() - min.y() + 1) + "x" + (max.z() - min.z() + 1) + ")";
    }

    @Override
    public String toString() {
        return label();
    }
}
