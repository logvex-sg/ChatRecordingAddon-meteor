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
        // ".nbt" is used for litematics, WorldEdit exports and structure files alike; the content
        // decides, so it is reported as UNKNOWN here and resolved by {@link #detect}.
        return UNKNOWN;
    }

    /** True when the format carries block geometry rather than just a material list. */
    public boolean isSchematic() {
        return this == SPONGE || this == MCEDIT || this == LITEMATIC;
    }

    /**
     * Detects the format from the parsed NBT tree rather than the file name.
     *
     * <p>A file's extension is only a hint: downloads are frequently renamed, stripped of their
     * extension, or given the wrong one, and a reader picked from a wrong hint produces a garbled
     * or empty schematic. The tree itself is unambiguous:
     * <ul>
     *   <li>a {@code Regions} compound holding {@code BlockStatePalette} entries is a litematic;</li>
     *   <li>a {@code Palette} (at the root or under a {@code Blocks} container) with {@code BlockData}
     *       or {@code Data} is a Sponge/WorldEdit schematic;</li>
     *   <li>a {@code Blocks} byte array with {@code Width}/{@code Height}/{@code Length} is MCEdit.</li>
     * </ul>
     *
     * @return the detected format, or {@link #UNKNOWN} when the tree matches none of them
     */
    public static SchematicFormat detect(NbtNode root) {
        if (root == null || !root.isCompound()) return UNKNOWN;

        NbtNode regions = root.getCompound("Regions");
        if (regions != null && !regions.children().isEmpty()) return LITEMATIC;

        if (hasPalette(root) || hasPalette(root.getCompound("Blocks"))) return SPONGE;

        // MCEdit: numeric block arrays plus short dimensions and no palette.
        if (root.getByteArray("Blocks").length > 0
            && root.getInt("Width", -1) > 0 && root.getInt("Height", -1) > 0) {
            return MCEDIT;
        }
        // Sponge v1/v2 store the palette at the root; a bare BlockData array is still Sponge.
        if (root.getByteArray("BlockData").length > 0) return SPONGE;
        return UNKNOWN;
    }

    private static boolean hasPalette(NbtNode container) {
        if (container == null) return false;
        NbtNode palette = container.getCompound("Palette");
        if (palette == null) palette = container.getCompound("palette");
        if (palette == null) palette = container.getCompound("BlockPalette");
        if (palette == null) return false;
        return !palette.children().isEmpty();
    }
}
