package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.LegacyBlockIds;
import com.logvex.addon.schematicdropper.core.LitematicSchematicReader;
import com.logvex.addon.schematicdropper.core.MceditSchematicReader;
import com.logvex.addon.schematicdropper.core.NbtNode;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.core.SchematicFormat;
import com.logvex.addon.schematicdropper.core.SchematicParseException;
import com.logvex.addon.schematicdropper.core.SpongeSchematicReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests for the three supported schematic formats. The readers work on {@link NbtNode}, so
 * these tests build NBT trees by hand and assert on the resulting {@link Schematic} without needing
 * a Minecraft runtime.
 */
class SchematicReaderTest {

    // ------------------------------------------------------------------ sponge

    private static NbtNode spongeRoot(int w, int h, int len, String[] palette, int[] data) {
        NbtNode root = NbtNode.compound();
        root.put("Version", NbtNode.number(NbtNode.Type.INT, 2));
        NbtNode paletteNode = NbtNode.compound();
        for (int i = 0; i < palette.length; i++) {
            paletteNode.put(palette[i], NbtNode.number(NbtNode.Type.INT, i));
        }
        root.put("Palette", paletteNode);
        root.put("PaletteMax", NbtNode.number(NbtNode.Type.INT, palette.length));
        root.put("Width", NbtNode.number(NbtNode.Type.SHORT, w));
        root.put("Height", NbtNode.number(NbtNode.Type.SHORT, h));
        root.put("Length", NbtNode.number(NbtNode.Type.SHORT, len));
        root.put("BlockData", NbtNode.byteArray(varint(data)));
        return root;
    }

    private static byte[] varint(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : values) {
            int v = value;
            while ((v & 0xFFFFFF80) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("a Sponge v2 schematic is read with its palette and geometry")
    void spongeV2() throws Exception {
        // 2x1x1: stone then air.
        NbtNode root = spongeRoot(2, 1, 1,
            new String[]{"minecraft:air", "minecraft:stone"}, new int[]{1, 0});

        Schematic s = new SpongeSchematicReader().read("test", SchematicFormat.SPONGE, root);

        assertEquals(2, s.sizeX());
        assertEquals(1, s.sizeY());
        assertEquals(1, s.sizeZ());
        assertEquals(1, s.blockCount(), "air must not be stored");
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 0, 0)).id());
        assertTrue(s.at(new BlockPos3(1, 0, 0)).isAir());
    }

    @Test
    @DisplayName("block properties in the palette are preserved")
    void spongeProperties() throws Exception {
        NbtNode root = spongeRoot(1, 1, 1,
            new String[]{"minecraft:oak_stairs[facing=east,half=bottom]"}, new int[]{0});

        Schematic s = new SpongeSchematicReader().read("stairs", SchematicFormat.SPONGE, root);

        assertEquals("east", s.at(new BlockPos3(0, 0, 0)).properties().get("facing"));
        assertEquals("bottom", s.at(new BlockPos3(0, 0, 0)).properties().get("half"));
    }

    @Test
    @DisplayName("Sponge v3 dimensions come from the Size array")
    void spongeV3Size() throws Exception {
        NbtNode root = NbtNode.compound();
        NbtNode size = NbtNode.intArray(new int[]{3, 1, 1});
        root.put("Size", size);
        NbtNode palette = NbtNode.compound();
        palette.put("minecraft:stone", NbtNode.number(NbtNode.Type.INT, 0));
        root.put("Palette", palette);
        root.put("BlockData", NbtNode.byteArray(varint(new int[]{0, 0, 0})));

        Schematic s = new SpongeSchematicReader().read("v3", SchematicFormat.SPONGE, root);

        assertEquals(3, s.sizeX());
        assertEquals(3, s.blockCount());
    }

    @Test
    @DisplayName("a truncated Sponge file is rejected, not silently accepted")
    void spongeTruncated() {
        NbtNode root = spongeRoot(4, 1, 1, new String[]{"minecraft:stone"}, new int[]{0});

        assertThrows(SchematicParseException.class,
            () -> new SpongeSchematicReader().read("bad", SchematicFormat.SPONGE, root));
    }

    @Test
    @DisplayName("Sponge v3 blocks live under the Blocks container")
    void spongeV3BlocksContainer() throws Exception {
        NbtNode root = NbtNode.compound();
        root.put("Version", NbtNode.number(NbtNode.Type.INT, 3));
        root.put("Size", NbtNode.intArray(new int[]{3, 1, 1}));
        NbtNode blocks = NbtNode.compound();
        NbtNode palette = NbtNode.compound();
        palette.put("minecraft:stone", NbtNode.number(NbtNode.Type.INT, 0));
        palette.put("minecraft:oak_planks", NbtNode.number(NbtNode.Type.INT, 1));
        blocks.put("Palette", palette);
        blocks.put("Data", NbtNode.byteArray(varint(new int[]{0, 1, 0})));
        root.put("Blocks", blocks);

        Schematic s = new SpongeSchematicReader().read("v3", SchematicFormat.SPONGE, root);

        assertEquals(3, s.sizeX());
        assertEquals(3, s.blockCount());
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 0, 0)).id());
        assertEquals("minecraft:oak_planks", s.at(new BlockPos3(1, 0, 0)).id());
        assertEquals("minecraft:stone", s.at(new BlockPos3(2, 0, 0)).id());
    }

    @Test
    @DisplayName("a Sponge file with no palette is rejected with a clear reason")
    void spongeNoPalette() {
        NbtNode root = NbtNode.compound();
        root.put("Width", NbtNode.number(NbtNode.Type.SHORT, 1));
        root.put("Height", NbtNode.number(NbtNode.Type.SHORT, 1));
        root.put("Length", NbtNode.number(NbtNode.Type.SHORT, 1));

        SchematicParseException e = assertThrows(SchematicParseException.class,
            () -> new SpongeSchematicReader().read("bad", SchematicFormat.SPONGE, root));
        assertTrue(e.getMessage().contains("palette"));
    }

    // ------------------------------------------------------------------ mcedit

    @Test
    @DisplayName("an MCEdit schematic resolves ids through the legacy table")
    void mcedit() throws Exception {
        NbtNode root = NbtNode.compound();
        root.put("Width", NbtNode.number(NbtNode.Type.SHORT, 2));
        root.put("Height", NbtNode.number(NbtNode.Type.SHORT, 1));
        root.put("Length", NbtNode.number(NbtNode.Type.SHORT, 1));
        root.put("Blocks", NbtNode.byteArray(new byte[]{1, 0}));
        root.put("Data", NbtNode.byteArray(new byte[]{0, 0}));

        LegacyBlockIds ids = (id, meta) -> id == 1 ? com.logvex.addon.schematicdropper.core.BlockStateSpec.of("minecraft:stone") : null;

        Schematic s = new MceditSchematicReader(ids).read("legacy", SchematicFormat.MCEDIT, root);

        assertEquals(1, s.blockCount());
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 0, 0)).id());
    }

    @Test
    @DisplayName("MCEdit AddBlocks extends the id beyond 255")
    void mceditAddBlocks() throws Exception {
        NbtNode root = NbtNode.compound();
        root.put("Width", NbtNode.number(NbtNode.Type.SHORT, 1));
        root.put("Height", NbtNode.number(NbtNode.Type.SHORT, 1));
        root.put("Length", NbtNode.number(NbtNode.Type.SHORT, 1));
        root.put("Blocks", NbtNode.byteArray(new byte[]{(byte) 0x80})); // low bits
        root.put("AddBlocks", NbtNode.byteArray(new byte[]{0x01}));     // high nibble 1 -> id 0x180
        root.put("Data", NbtNode.byteArray(new byte[]{0}));

        LegacyBlockIds ids = (id, meta) -> com.logvex.addon.schematicdropper.core.BlockStateSpec.of("minecraft:id-" + id);

        Schematic s = new MceditSchematicReader(ids).read("legacy", SchematicFormat.MCEDIT, root);

        assertEquals("minecraft:id-384", s.at(new BlockPos3(0, 0, 0)).id());
    }

    // ---------------------------------------------------------------- litematic

    private static NbtNode litematicRegion(int sx, int sy, int sz, int[] position,
                                           String[] palette, long[] states) {
        NbtNode region = NbtNode.compound();
        region.put("Size", NbtNode.intArray(new int[]{sx, sy, sz}));
        region.put("Position", NbtNode.intArray(position));
        NbtNode paletteList = NbtNode.list();
        for (String id : palette) {
            NbtNode entry = NbtNode.compound();
            entry.put("Name", NbtNode.string(id));
            paletteList.add(entry);
        }
        region.put("BlockStatePalette", paletteList);
        NbtNode statesList = NbtNode.list();
        for (long l : states) statesList.add(NbtNode.number(NbtNode.Type.LONG, l));
        region.put("BlockStates", statesList);
        return region;
    }

    @Test
    @DisplayName("a litematic packs 2-bit palette indices and rebases to the origin")
    void litematic() throws Exception {
        // 4 blocks at 2 bits each: indices 1,0,0,1 -> 0b01_00_00_01 = 0x11
        long packed = 0b01_00_00_01L;
        NbtNode region = litematicRegion(4, 1, 1, new int[]{0, 0, 0},
            new String[]{"minecraft:air", "minecraft:stone"}, new long[]{packed});

        NbtNode root = NbtNode.compound();
        NbtNode regions = NbtNode.compound();
        regions.put("main", region);
        root.put("Regions", regions);

        Schematic s = new LitematicSchematicReader().read("lite", SchematicFormat.LITEMATIC, root);

        assertEquals(2, s.blockCount());
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 0, 0)).id());
        assertEquals("minecraft:stone", s.at(new BlockPos3(3, 0, 0)).id());
    }

    @Test
    @DisplayName("a litematic entry that straddles a long boundary is read, not dropped")
    void litematicEntryCrossesLongBoundary() throws Exception {
        // 32-entry palette -> 5 bits per index. Index 12 starts at bit 60, so its low four bits sit
        // at the top of long 0 and its top bit continues into long 1. A reader that pads each long
        // (64/5 = 12 entries) would read long 1 instead and see air.
        String[] palette = new String[32];
        palette[0] = "minecraft:air";
        for (int i = 1; i < 32; i++) palette[i] = "minecraft:stone";
        palette[7] = "minecraft:diamond_block";

        // Index 0 is stone (anchors the origin at x=0), index 12 is diamond and straddles the longs.
        long[] states = new long[]{1L | (0b0111L << 60), 0L};
        NbtNode region = litematicRegion(13, 1, 1, new int[]{0, 0, 0}, palette, states);

        NbtNode root = NbtNode.compound();
        NbtNode regions = NbtNode.compound();
        regions.put("main", region);
        root.put("Regions", regions);

        Schematic s = new LitematicSchematicReader().read("lite", SchematicFormat.LITEMATIC, root);

        assertEquals(2, s.blockCount());
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 0, 0)).id());
        assertEquals("minecraft:diamond_block", s.at(new BlockPos3(12, 0, 0)).id());
    }

    @Test
    @DisplayName("multiple litematic regions are merged and rebased into one schematic")
    void litematicMultipleRegions() throws Exception {
        NbtNode root = NbtNode.compound();
        NbtNode regions = NbtNode.compound();
        // Region A at x=0, region B at x=10. Both single blocks, palette index 0 = stone.
        regions.put("a", litematicRegion(1, 1, 1, new int[]{0, 0, 0},
            new String[]{"minecraft:stone"}, new long[]{0L}));
        regions.put("b", litematicRegion(1, 1, 1, new int[]{10, 0, 0},
            new String[]{"minecraft:stone"}, new long[]{0L}));
        root.put("Regions", regions);

        Schematic s = new LitematicSchematicReader().read("multi", SchematicFormat.LITEMATIC, root);

        assertEquals(2, s.blockCount());
        assertEquals(11, s.sizeX(), "rebased width spans both regions");
        assertTrue(s.contains(new BlockPos3(0, 0, 0)));
        assertTrue(s.contains(new BlockPos3(10, 0, 0)));
    }

    @Test
    @DisplayName("a litematic without regions is rejected")
    void litematicNoRegions() {
        NbtNode root = NbtNode.compound();
        assertThrows(SchematicParseException.class,
            () -> new LitematicSchematicReader().read("bad", SchematicFormat.LITEMATIC, root));
    }

    // ------------------------------------------------------------- format detect

    @Test
    @DisplayName("file names map to the right format")
    void formatDetection() {
        assertEquals(SchematicFormat.SPONGE, SchematicFormat.fromFileName("build.schem"));
        assertEquals(SchematicFormat.MCEDIT, SchematicFormat.fromFileName("old.schematic"));
        assertEquals(SchematicFormat.LITEMATIC, SchematicFormat.fromFileName("plan.litematic"));
        assertEquals(SchematicFormat.MATERIAL_LIST, SchematicFormat.fromFileName("materials.txt"));
        assertEquals(SchematicFormat.UNKNOWN, SchematicFormat.fromFileName("notes.bin"));
    }

    @Test
    @DisplayName("a schematic derives an ordered material plan")
    void materialPlanFromSchematic() throws Exception {
        NbtNode root = spongeRoot(3, 1, 1,
            new String[]{"minecraft:stone", "minecraft:dirt"}, new int[]{0, 1, 0});
        Schematic s = new SpongeSchematicReader().read("mix", SchematicFormat.SPONGE, root);

        var plan = s.toMaterialPlan();

        assertEquals(2, plan.size());
        assertEquals(2, plan.get("minecraft:stone").required());
        assertEquals(1, plan.get("minecraft:dirt").required());
        assertFalse(plan.isEmpty());
    }

    @Test
    @DisplayName("a schematic's material plan survives the material-list round trip")
    void schematicMaterialsRoundTrip() throws Exception {
        // This is exactly what the module does when a schematic is loaded: derive the plan, write
        // "id quantity" lines, then re-read them with the material-list parser. Order and
        // quantities must survive both directions or the drop/acquisition queue is wrong.
        NbtNode root = spongeRoot(4, 1, 1,
            new String[]{"minecraft:stone", "minecraft:oak_planks"}, new int[]{0, 1, 1, 0});
        Schematic s = new SpongeSchematicReader().read("mix", SchematicFormat.SPONGE, root);

        List<String> entries = new ArrayList<>();
        for (var r : s.toMaterialPlan().requirements()) {
            entries.add(r.itemId() + " " + r.required());
        }

        var reparsed = com.logvex.addon.schematicdropper.core.MaterialListParser.parse(entries);

        assertEquals(2, reparsed.size());
        assertEquals("minecraft:stone", reparsed.get(0).itemId());
        assertEquals(2, reparsed.get(0).required());
        assertEquals("minecraft:oak_planks", reparsed.get(1).itemId());
        assertEquals(2, reparsed.get(1).required());
    }
}
