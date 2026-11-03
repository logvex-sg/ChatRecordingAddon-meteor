package com.logvex.addon.schematicdropper.core;

/**
 * An integer block coordinate. Deliberately Minecraft-free so the whole planning and comparison
 * pipeline can be unit tested without a running client.
 */
public record BlockPos3(int x, int y, int z) implements Comparable<BlockPos3> {
    public BlockPos3 offset(int dx, int dy, int dz) {
        return new BlockPos3(x + dx, y + dy, z + dz);
    }

    public BlockPos3 above() {
        return new BlockPos3(x, y + 1, z);
    }

    public BlockPos3 below() {
        return new BlockPos3(x, y - 1, z);
    }

    /** The four horizontal neighbours, in a stable order (north, east, south, west). */
    public BlockPos3[] horizontalNeighbours() {
        return new BlockPos3[]{
            new BlockPos3(x, y, z - 1),
            new BlockPos3(x + 1, y, z),
            new BlockPos3(x, y, z + 1),
            new BlockPos3(x - 1, y, z)
        };
    }

    public int manhattan(BlockPos3 other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    /** Bottom-up, then north-to-south, then west-to-east: a deterministic build order. */
    @Override
    public int compareTo(BlockPos3 o) {
        int c = Integer.compare(y, o.y);
        if (c != 0) return c;
        c = Integer.compare(z, o.z);
        if (c != 0) return c;
        return Integer.compare(x, o.x);
    }

    @Override
    public String toString() {
        return x + ", " + y + ", " + z;
    }
}
