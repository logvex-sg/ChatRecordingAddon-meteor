package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads vanilla structure NBT files (the format a structure block writes and data packs ship).
 *
 * <p>A structure stores a {@code palette} list of block states and a {@code blocks} list of
 * positioned entries. Each palette entry is a compound with a {@code Name} and an optional
 * {@code Properties} compound; each block entry has an {@code pos} int list and a {@code state}
 * palette index. There are no dimensions in the file, so the bounding box is derived from the
 * positions and the result is rebased so its minimum corner is {@code (0,0,0)}, matching every
 * other reader.
 *
 * <p>Both the modern lowercase keys and the older capitalised keys are accepted, and the palette
 * and position arrays are read through the loose accessors so a list-encoded array is not lost.
 */
public final class StructureSchematicReader implements SchematicImporter {
    @Override
    public boolean supports(SchematicFormat format) {
        return format == SchematicFormat.STRUCTURE;
    }

    @Override
    public Schematic read(String name, SchematicFormat format, NbtNode root) throws SchematicParseException {
        if (root == null) throw new SchematicParseException("The file is empty.");

        NbtNode paletteNode = root.getList("palette");
        if (paletteNode == null) paletteNode = root.getList("Palette");
        if (paletteNode == null || paletteNode.items().isEmpty()) {
            throw new SchematicParseException("The structure has no block palette.");
        }

        List<BlockStateSpec> palette = new ArrayList<>(paletteNode.items().size());
        for (NbtNode item : paletteNode.items()) {
            palette.add(readState(item));
        }

        NbtNode blocksNode = root.getList("blocks");
        if (blocksNode == null) blocksNode = root.getList("Blocks");
        if (blocksNode == null || blocksNode.items().isEmpty()) {
            throw new SchematicParseException("The structure has no blocks.");
        }

        Map<BlockPos3, BlockStateSpec> blocks = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (NbtNode entry : blocksNode.items()) {
            if (entry == null || !entry.isCompound()) continue;

            int[] pos = entry.getIntArrayLoose("pos");
            if (pos.length < 3) pos = entry.getIntArrayLoose("Pos");
            if (pos.length < 3) continue;

            int stateIndex = entry.getInt("state", entry.getInt("State", -1));
            if (stateIndex < 0 || stateIndex >= palette.size()) continue;

            BlockStateSpec spec = palette.get(stateIndex);
            if (spec == null || spec.isAir()) continue;

            int x = pos[0];
            int y = pos[1];
            int z = pos[2];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            blocks.put(new BlockPos3(x, y, z), spec);
        }

        if (blocks.isEmpty()) throw new SchematicParseException("The structure contains no blocks.");

        Map<BlockPos3, BlockStateSpec> rebased = new LinkedHashMap<>(blocks.size());
        for (Map.Entry<BlockPos3, BlockStateSpec> e : blocks.entrySet()) {
            BlockPos3 p = e.getKey();
            rebased.put(new BlockPos3(p.x() - minX, p.y() - minY, p.z() - minZ), e.getValue());
        }

        return new Schematic(name, SchematicFormat.STRUCTURE,
            maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, rebased);
    }

    private static BlockStateSpec readState(NbtNode node) {
        if (node == null) return BlockStateSpec.air();
        if (node.isString()) return BlockStateSpec.parse(node.asString());
        if (!node.isCompound()) return BlockStateSpec.air();

        String id = node.getString("Name", node.getString("name", ""));
        if (id.isEmpty()) return BlockStateSpec.air();

        Map<String, String> props = new LinkedHashMap<>();
        NbtNode properties = node.getCompound("Properties");
        if (properties == null) properties = node.getCompound("properties");
        if (properties != null) {
            for (Map.Entry<String, NbtNode> p : properties.children().entrySet()) {
                props.put(p.getKey(), p.getValue().asString());
            }
        }
        return BlockStateSpec.of(id, props);
    }
}
