package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.NbtNode;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.core.SchematicFormat;
import com.logvex.addon.schematicdropper.core.SchematicParseException;
import com.logvex.addon.schematicdropper.core.StructureSchematicReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vanilla structure reader: a {@code palette} list plus a {@code blocks} list, with the
 * bounding box derived from the positions and rebased to the origin.
 */
class StructureSchematicReaderTest {

    private static NbtNode structure() {
        NbtNode root = NbtNode.compound();
        root.put("DataVersion", NbtNode.number(NbtNode.Type.INT, 3953));

        NbtNode palette = NbtNode.list();
        NbtNode air = NbtNode.compound();
        air.put("Name", NbtNode.string("minecraft:air"));
        palette.add(air);
        NbtNode stairs = NbtNode.compound();
        stairs.put("Name", NbtNode.string("minecraft:oak_stairs"));
        NbtNode props = NbtNode.compound();
        props.put("facing", NbtNode.string("east"));
        stairs.put("Properties", props);
        palette.add(stairs);
        root.put("palette", palette);

        NbtNode blocks = NbtNode.list();
        blocks.add(block(1, 0, 0, 0));
        blocks.add(block(1, 2, 3, 0));
        blocks.add(block(0, 1, 1, 1)); // air, dropped
        root.put("blocks", blocks);
        return root;
    }

    private static NbtNode block(int state, int x, int y, int z) {
        NbtNode node = NbtNode.compound();
        node.put("state", NbtNode.number(NbtNode.Type.INT, state));
        node.put("pos", NbtNode.intArray(new int[]{x, y, z}));
        return node;
    }

    @Test
    @DisplayName("a structure is read, air is dropped and the box is rebased to the origin")
    void readsStructure() throws SchematicParseException {
        Schematic s = new StructureSchematicReader().read("village.nbt", SchematicFormat.STRUCTURE, structure());

        assertEquals(SchematicFormat.STRUCTURE, s.format());
        assertEquals(2, s.blockCount());
        assertEquals(3, s.sizeX());
        assertEquals(4, s.sizeY());
        assertEquals(1, s.sizeZ());
        assertEquals("minecraft:oak_stairs", s.at(new BlockPos3(0, 0, 0)).id());
        assertTrue(s.at(new BlockPos3(0, 0, 0)).hasProperties());
        assertEquals("minecraft:oak_stairs", s.at(new BlockPos3(2, 3, 0)).id());
    }

    @Test
    @DisplayName("a structure with no palette is rejected with a clear reason")
    void noPaletteRejected() {
        NbtNode root = NbtNode.compound();
        assertThrows(SchematicParseException.class,
            () -> new StructureSchematicReader().read("x.nbt", SchematicFormat.STRUCTURE, root));
    }

    @Test
    @DisplayName("a position stored as a list of ints is read through the loose accessor")
    void loosePositionArray() throws SchematicParseException {
        NbtNode root = NbtNode.compound();
        NbtNode palette = NbtNode.list();
        NbtNode stone = NbtNode.compound();
        stone.put("Name", NbtNode.string("minecraft:stone"));
        palette.add(stone);
        root.put("palette", palette);

        // pos encoded as a list of ints rather than an int array.
        NbtNode blocks = NbtNode.list();
        NbtNode entry = NbtNode.compound();
        entry.put("state", NbtNode.number(NbtNode.Type.INT, 0));
        NbtNode posList = NbtNode.list();
        posList.add(NbtNode.number(NbtNode.Type.INT, 5));
        posList.add(NbtNode.number(NbtNode.Type.INT, 6));
        posList.add(NbtNode.number(NbtNode.Type.INT, 7));
        entry.put("pos", posList);
        blocks.add(entry);
        root.put("blocks", blocks);

        Schematic s = new StructureSchematicReader().read("s.nbt", SchematicFormat.STRUCTURE, root);
        assertEquals(1, s.blockCount());
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 0, 0)).id());
    }

    @Test
    @DisplayName("a structure is detected as STRUCTURE, not Sponge")
    void detected() {
        assertEquals(SchematicFormat.STRUCTURE, SchematicFormat.detect(structure()));
        assertTrue(SchematicFormat.STRUCTURE.isSchematic());
    }
}
