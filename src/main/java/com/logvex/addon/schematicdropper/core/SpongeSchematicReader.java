package com.logvex.addon.schematicdropper.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads Sponge / WorldEdit {@code .schem} files (format versions 1, 2 and 3).
 *
 * <p>Geometry lives in a {@code Palette} compound mapping {@code "minecraft:oak_stairs[facing=east]"}
 * to a palette index, plus a {@code BlockData} byte array of varint-encoded palette indices in
 * {@code y * length * width + z * width + x} order. Version 3 stores the dimensions in a {@code Size}
 * int array; versions 1 and 2 use {@code Width}/{@code Height}/{@code Length}.
 *
 * <p>Blocks whose state is {@code minecraft:air} are dropped so the model only holds real blocks.
 */
public final class SpongeSchematicReader implements SchematicImporter {
    @Override
    public boolean supports(SchematicFormat format) {
        return format == SchematicFormat.SPONGE;
    }

    @Override
    public Schematic read(String name, SchematicFormat format, NbtNode root) throws SchematicParseException {
        if (root == null) throw new SchematicParseException("The file is empty.");

        int width;
        int height;
        int length;
        int[] size = root.getIntArray("Size");
        if (size.length >= 3) {
            width = size[0];
            height = size[1];
            length = size[2];
        } else {
            width = root.getInt("Width", -1);
            height = root.getInt("Height", -1);
            length = root.getInt("Length", -1);
        }
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new SchematicParseException("The schematic has no valid dimensions.");
        }

        NbtNode paletteNode = root.getCompound("Palette");
        if (paletteNode == null) paletteNode = root.getCompound("palette");
        if (paletteNode == null) throw new SchematicParseException("The schematic has no block palette.");

        Map<Integer, BlockStateSpec> palette = new LinkedHashMap<>();
        for (Map.Entry<String, NbtNode> entry : paletteNode.children().entrySet()) {
            if (!entry.getValue().isNumber()) continue;
            palette.put(entry.getValue().asInt(), BlockStateSpec.parse(entry.getKey()));
        }
        if (palette.isEmpty()) throw new SchematicParseException("The block palette is empty.");

        byte[] data = root.getByteArray("BlockData");
        if (data.length == 0) throw new SchematicParseException("The schematic has no block data.");

        long volume = (long) width * height * length;
        Map<BlockPos3, BlockStateSpec> blocks = new LinkedHashMap<>();
        int index = 0;
        int cursor = 0;
        long produced = 0;

        while (cursor < data.length && produced < volume) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (cursor >= data.length) throw new SchematicParseException("The block data is truncated.");
                byte b = data[cursor++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) throw new SchematicParseException("The block data is corrupt.");
            }

            int x = index % width;
            int z = (index / width) % length;
            int y = index / (width * length);

            BlockStateSpec spec = palette.get(value);
            if (spec == null) {
                throw new SchematicParseException("The block data references palette index " + value + " which is missing.");
            }
            if (!spec.isAir()) blocks.put(new BlockPos3(x, y, z), spec);

            index++;
            produced++;
        }

        if (produced < volume) {
            throw new SchematicParseException("The block data ended after " + produced + " of " + volume + " blocks.");
        }

        return new Schematic(name, SchematicFormat.SPONGE, width, height, length, blocks);
    }
}
