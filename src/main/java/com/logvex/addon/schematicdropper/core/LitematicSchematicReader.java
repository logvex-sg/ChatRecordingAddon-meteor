package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Litematica {@code .litematic} files.
 *
 * <p>A litematic holds one or more {@code Regions}. Each region has {@code Size} (absolute
 * dimensions), {@code Position} (the region origin) and a {@code BlockStatePalette} list. Geometry
 * is a {@code BlockStates} long array: the palette indices are bit-packed at a width of
 * {@code max(2, ceil(log2(paletteSize)))} bits per entry, <b>tightly packed</b> - an entry that
 * starts near the end of a long continues into the next one. Region-local coordinates are converted
 * into a single schematic space so multi-region files import as one schematic.
 */
public final class LitematicSchematicReader implements SchematicImporter {
    @Override
    public boolean supports(SchematicFormat format) {
        return format == SchematicFormat.LITEMATIC;
    }

    @Override
    public Schematic read(String name, SchematicFormat format, NbtNode root) throws SchematicParseException {
        if (root == null) throw new SchematicParseException("The file is empty.");

        NbtNode regions = root.getCompound("Regions");
        if (regions == null || regions.children().isEmpty()) {
            throw new SchematicParseException("The litematic has no regions.");
        }

        Map<BlockPos3, BlockStateSpec> blocks = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (Map.Entry<String, NbtNode> entry : regions.children().entrySet()) {
            NbtNode region = entry.getValue();
            if (region == null || !region.isCompound()) continue;

            int[] size = region.getIntArrayLoose("Size");
            if (size.length < 3) continue;
            int sx = Math.abs(size[0]);
            int sy = Math.abs(size[1]);
            int sz = Math.abs(size[2]);
            if (sx == 0 || sy == 0 || sz == 0) continue;

            int[] position = region.getIntArrayLoose("Position");
            int px = position.length >= 1 ? position[0] : 0;
            int py = position.length >= 2 ? position[1] : 0;
            int pz = position.length >= 3 ? position[2] : 0;

            List<BlockStateSpec> palette = readPalette(region);
            if (palette.isEmpty()) continue;

            long[] states = region.getList("BlockStates") != null
                ? longsOf(region.getList("BlockStates"))
                : region.getLongArrayLoose("BlockStates");
            if (states.length == 0) continue;

            long volume = (long) sx * sy * sz;
            int bits = bitsPerEntry(palette.size());

            for (long index = 0; index < volume; index++) {
                int paletteIndex = readEntry(states, index, bits);
                if (paletteIndex < 0 || paletteIndex >= palette.size()) continue;

                BlockStateSpec spec = palette.get(paletteIndex);
                if (spec == null || spec.isAir()) continue;

                int x = (int) (index % sx);
                int z = (int) ((index / sx) % sz);
                int y = (int) (index / ((long) sx * sz));

                int wx = px + x;
                int wy = py + y;
                int wz = pz + z;

                minX = Math.min(minX, wx);
                minY = Math.min(minY, wy);
                minZ = Math.min(minZ, wz);
                maxX = Math.max(maxX, wx);
                maxY = Math.max(maxY, wy);
                maxZ = Math.max(maxZ, wz);

                blocks.put(new BlockPos3(wx, wy, wz), spec);
            }
        }

        if (blocks.isEmpty()) throw new SchematicParseException("The litematic contains no blocks.");

        // Rebase so the schematic origin is (0,0,0), matching every other reader.
        Map<BlockPos3, BlockStateSpec> rebased = new LinkedHashMap<>(blocks.size());
        for (Map.Entry<BlockPos3, BlockStateSpec> e : blocks.entrySet()) {
            BlockPos3 p = e.getKey();
            rebased.put(new BlockPos3(p.x() - minX, p.y() - minY, p.z() - minZ), e.getValue());
        }

        return new Schematic(name, SchematicFormat.LITEMATIC,
            maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, rebased);
    }

    /**
     * Bits used per palette index, matching Litematica and vanilla's {@code Palette}: at least two,
     * otherwise the smallest width that can hold every index. A width of one would be ambiguous with
     * a single-entry palette, so it is never used.
     */
    private static int bitsPerEntry(int paletteSize) {
        int bits = 1;
        while ((1 << bits) < paletteSize) bits++;
        return Math.max(2, bits);
    }

    /**
     * Reads one palette index from the tightly packed array, matching Litematica's
     * {@code LitematicaBitArray.getAt}: an entry starting within eight bits of a long boundary
     * continues into the next long rather than being padded to it.
     */
    private static int readEntry(long[] states, long index, int bits) {
        long startOffset = index * bits;
        int startArrayIndex = (int) (startOffset >> 6);
        if (startArrayIndex >= states.length) return -1;

        int endArrayIndex = (int) (((index + 1L) * bits - 1L) >> 6);
        if (endArrayIndex >= states.length) return -1;

        int startBitOffset = (int) (startOffset & 0x3F);
        long mask = (1L << bits) - 1L;

        if (startArrayIndex == endArrayIndex) {
            return (int) ((states[startArrayIndex] >>> startBitOffset) & mask);
        }
        int endOffset = 64 - startBitOffset;
        return (int) ((states[startArrayIndex] >>> startBitOffset
            | states[endArrayIndex] << endOffset) & mask);
    }

    private static List<BlockStateSpec> readPalette(NbtNode region) {
        List<BlockStateSpec> palette = new ArrayList<>();
        NbtNode node = region.getList("BlockStatePalette");
        if (node == null) node = region.getList("Palette");
        if (node == null) return palette;
        for (NbtNode item : node.items()) {
            if (item.isCompound()) {
                String id = item.getString("Name", "");
                Map<String, String> props = new LinkedHashMap<>();
                NbtNode properties = item.getCompound("Properties");
                if (properties != null) {
                    for (Map.Entry<String, NbtNode> p : properties.children().entrySet()) {
                        props.put(p.getKey(), p.getValue().asString());
                    }
                }
                palette.add(BlockStateSpec.of(id, props));
            } else if (item.isString()) {
                palette.add(BlockStateSpec.parse(item.asString()));
            } else {
                palette.add(BlockStateSpec.air());
            }
        }
        return palette;
    }

    private static long[] longsOf(NbtNode list) {
        long[] out = new long[list.items().size()];
        for (int i = 0; i < out.length; i++) out[i] = list.items().get(i).asLong();
        return out;
    }
}
