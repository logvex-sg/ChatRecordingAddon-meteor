package com.logvex.addon.schematicdropper.core;

import java.util.Locale;

/** The schematic file formats the importer understands. */
public enum SchematicFormat {
    /** Sponge / WorldEdit schematic (version 1, 2 or 3): a {@code Palette} plus a {@code BlockData} varint array. */
    SPONGE,
    /** Legacy MCEdit schematic: {@code Blocks}/{@code Data} byte arrays plus a {@code AddBlocks} nibble array. */
    MCEDIT,
    /** Litematica {@code .litematic}: region compounds with packed {@code BlockStates} longs. */
    LITEMATIC,
    /** The addon's own text material list; contains no geometry, only quantities. */
    MATERIAL_LIST,
    UNKNOWN;

    public static SchematicFormat fromFileName(String fileName) {
        if (fileName == null) return UNKNOWN;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".litematic")) return LITEMATIC;
        if (lower.endsWith(".schem")) return SPONGE;
        if (lower.endsWith(".schematic")) return MCEDIT;
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv")) return MATERIAL_LIST;
        return UNKNOWN;
    }
}
