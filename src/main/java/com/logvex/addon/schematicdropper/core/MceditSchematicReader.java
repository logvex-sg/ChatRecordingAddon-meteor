package com.logvex.addon.schematicdropper.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads legacy MCEdit {@code .schematic} files.
 *
 * <p>Geometry is stored as three parallel arrays: {@code Blocks} (the low 8 bits of the block id),
 * {@code AddBlocks} (a nibble array holding bits 8-11, packed two per byte) and {@code Data} (the
 * block metadata). The block id is looked up in the version's block registry by numeric id, which
 * the Minecraft adapter supplies through {@link LegacyBlockIds} so this reader stays Minecraft-free.
 */
public final class MceditSchematicReader implements SchematicImporter {
    private final LegacyBlockIds blockIds;

    public MceditSchematicReader(LegacyBlockIds blockIds) {
        this.blockIds = blockIds;
    }

    @Override
    public boolean supports(SchematicFormat format) {
        return format == SchematicFormat.MCEDIT;
    }

    @Override
    public Schematic read(String name, SchematicFormat format, NbtNode root) throws SchematicParseException {
        if (root == null) throw new SchematicParseException("The file is empty.");

        int width = root.getInt("Width", -1);
        int height = root.getInt("Height", -1);
        int length = root.getInt("Length", -1);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new SchematicParseException("The schematic has no valid dimensions.");
        }

        byte[] blocks = root.getByteArray("Blocks");
        long volume = (long) width * height * length;
        if (blocks.length == 0) throw new SchematicParseException("The schematic has no block data.");
        if (blocks.length < volume) {
            throw new SchematicParseException("The block data ended after " + blocks.length + " of " + volume + " blocks.");
        }

        byte[] add = root.getByteArray("AddBlocks");
        byte[] data = root.getByteArray("Data");
        Map<Integer, BlockStateSpec> byId = new LinkedHashMap<>();

        Map<BlockPos3, BlockStateSpec> out = new LinkedHashMap<>();
        for (int index = 0; index < volume; index++) {
            int id = blocks[index] & 0xFF;
            if (add.length > 0) {
                int nibble = index >> 1;
                if (nibble < add.length) {
                    int b = add[nibble] & 0xFF;
                    id |= ((index & 1) == 0 ? (b & 0x0F) : (b >> 4)) << 8;
                }
            }
            if (id == 0) continue; // air

            int meta = index < data.length ? data[index] & 0x0F : 0;
            int key = (id << 4) | meta;
            BlockStateSpec spec = byId.get(key);
            if (spec == null) {
                spec = blockIds.resolve(id, meta);
                byId.put(key, spec);
            }
            if (spec == null || spec.isAir()) continue;

            int x = index % width;
            int z = (index / width) % length;
            int y = index / (width * length);
            out.put(new BlockPos3(x, y, z), spec);
        }

        return new Schematic(name, SchematicFormat.MCEDIT, width, height, length, out);
    }
}
