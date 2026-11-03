package com.logvex.addon.schematicdropper.core;

/**
 * Maps a legacy numeric block id plus metadata to a modern block state.
 *
 * <p>Implemented by the Minecraft adapter using the version's flattening map. Kept as an interface
 * so the MCEdit reader stays free of Minecraft types and can be tested with a small fake table.
 */
public interface LegacyBlockIds {
    /** @return the modern block state, or {@code null} when the id is unknown. */
    BlockStateSpec resolve(int id, int meta);

    static LegacyBlockIds empty() {
        return (id, meta) -> null;
    }
}
