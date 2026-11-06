package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.NbtNode;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.core.SchematicFormat;
import com.logvex.addon.schematicdropper.core.SchematicParseException;
import com.logvex.addon.schematicdropper.mc.SchematicFileLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtShort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The importer must identify a schematic from its content, not only its file name: downloads are
 * routinely renamed, stripped of their extension, or given the wrong one, and a reader chosen from a
 * wrong hint decodes the file into garbage. These tests cover both the pure detector and the real
 * loader reading misnamed files from disk.
 */
class SchematicFormatDetectionTest {

    // ------------------------------------------------------------------ detector

    private static NbtNode spongeTree() {
        NbtNode root = NbtNode.compound();
        NbtNode palette = NbtNode.compound();
        palette.put("minecraft:stone", NbtNode.number(NbtNode.Type.INT, 0));
        root.put("Palette", palette);
        root.put("BlockData", NbtNode.byteArray(new byte[]{0}));
        return root;
    }

    private static NbtNode litematicTree() {
        NbtNode root = NbtNode.compound();
        NbtNode regions = NbtNode.compound();
        regions.put("Main", NbtNode.compound());
        root.put("Regions", regions);
        return root;
    }

    private static NbtNode mceditTree() {
        NbtNode root = NbtNode.compound();
        root.put("Width", NbtNode.number(NbtNode.Type.SHORT, 2));
        root.put("Height", NbtNode.number(NbtNode.Type.SHORT, 1));
        root.put("Length", NbtNode.number(NbtNode.Type.SHORT, 1));
        root.put("Blocks", NbtNode.byteArray(new byte[]{1, 0}));
        return root;
    }

    @Test
    @DisplayName("the detector identifies each format from the tree alone")
    void detectsEachFormat() {
        assertEquals(SchematicFormat.SPONGE, SchematicFormat.detect(spongeTree()));
        assertEquals(SchematicFormat.LITEMATIC, SchematicFormat.detect(litematicTree()));
        assertEquals(SchematicFormat.MCEDIT, SchematicFormat.detect(mceditTree()));
        assertEquals(SchematicFormat.UNKNOWN, SchematicFormat.detect(NbtNode.compound()));
        assertEquals(SchematicFormat.UNKNOWN, SchematicFormat.detect(null));
    }

    @Test
    @DisplayName("a Sponge v3 Blocks container is detected as Sponge")
    void detectsSpongeV3() {
        NbtNode root = NbtNode.compound();
        NbtNode blocks = NbtNode.compound();
        NbtNode palette = NbtNode.compound();
        palette.put("minecraft:stone", NbtNode.number(NbtNode.Type.INT, 0));
        blocks.put("Palette", palette);
        blocks.put("Data", NbtNode.byteArray(new byte[]{0}));
        root.put("Blocks", blocks);
        assertEquals(SchematicFormat.SPONGE, SchematicFormat.detect(root));
    }

    @Test
    @DisplayName("an empty Regions compound does not force the litematic reader")
    void emptyRegionsIsNotLitematic() {
        NbtNode root = NbtNode.compound();
        root.put("Regions", NbtNode.compound());
        NbtNode palette = NbtNode.compound();
        palette.put("minecraft:stone", NbtNode.number(NbtNode.Type.INT, 0));
        root.put("Palette", palette);
        root.put("BlockData", NbtNode.byteArray(new byte[]{0}));
        assertEquals(SchematicFormat.SPONGE, SchematicFormat.detect(root));
    }

    @Test
    @DisplayName("isSchematic is true only for the three geometry formats")
    void isSchematic() {
        assertTrue(SchematicFormat.SPONGE.isSchematic());
        assertTrue(SchematicFormat.MCEDIT.isSchematic());
        assertTrue(SchematicFormat.LITEMATIC.isSchematic());
        org.junit.jupiter.api.Assertions.assertFalse(SchematicFormat.MATERIAL_LIST.isSchematic());
        org.junit.jupiter.api.Assertions.assertFalse(SchematicFormat.UNKNOWN.isSchematic());
    }

    // ------------------------------------------------------------------ loader

    private static byte[] gzip(NbtCompound c) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStream gz = new GZIPOutputStream(bos); DataOutputStream out = new DataOutputStream(gz)) {
            NbtIo.writeCompound(c, out);
        }
        return bos.toByteArray();
    }

    private static NbtCompound spongeCompound() {
        NbtCompound root = new NbtCompound();
        root.putInt("Version", 2);
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:stone", 0);
        palette.putInt("minecraft:dirt", 1);
        root.put("Palette", palette);
        root.putShort("Width", (short) 2);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        root.putByteArray("BlockData", new byte[]{0, 1});
        return root;
    }

    private static NbtCompound litematicCompound() {
        NbtCompound root = new NbtCompound();
        NbtCompound regions = new NbtCompound();
        NbtCompound region = new NbtCompound();
        region.put("Size", new NbtIntArray(new int[]{2, 1, 1}));
        region.put("Position", new NbtIntArray(new int[]{0, 0, 0}));

        NbtList palette = new NbtList();
        NbtCompound stone = new NbtCompound();
        stone.putString("Name", "minecraft:stone");
        NbtCompound dirt = new NbtCompound();
        dirt.putString("Name", "minecraft:dirt");
        palette.add(stone);
        palette.add(dirt);
        region.put("BlockStatePalette", palette);

        // Two entries of 2 bits each: index 0 = stone (0), index 1 = dirt (1), packed into one long.
        long packed = 0L | (1L << 2);
        region.put("BlockStates", new NbtLongArray(new long[]{packed}));
        regions.put("Main", region);
        root.put("Regions", regions);
        return root;
    }

    @Test
    @DisplayName("a Sponge file with the wrong extension still loads from its content")
    void loadsSpongeWithWrongExtension(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("build.dat");
        Files.write(file, gzip(spongeCompound()));

        Schematic s = new SchematicFileLoader().load(file);

        assertEquals(2, s.blockCount());
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 0, 0)).id());
        assertEquals("minecraft:dirt", s.at(new BlockPos3(1, 0, 0)).id());
    }

    @Test
    @DisplayName("a Sponge file with no extension at all still loads")
    void loadsExtensionlessSponge(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("schematic-export");
        Files.write(file, gzip(spongeCompound()));

        Schematic s = new SchematicFileLoader().load(file);
        assertEquals(2, s.blockCount());
    }

    @Test
    @DisplayName("a litematic misnamed as .schem loads through content detection")
    void loadsLitematicWithWrongExtension(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("house.schem");
        Files.write(file, gzip(litematicCompound()));

        Schematic s = new SchematicFileLoader().load(file);

        assertEquals(2, s.blockCount());
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 0, 0)).id());
        assertEquals("minecraft:dirt", s.at(new BlockPos3(1, 0, 0)).id());
    }

    @Test
    @DisplayName("a .nbt file is resolved by content, not treated as unsupported")
    void loadsNbtExtension(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("export.nbt");
        Files.write(file, gzip(litematicCompound()));

        Schematic s = new SchematicFileLoader().load(file);
        assertEquals(2, s.blockCount());
    }

    @Test
    @DisplayName("a file that is not a schematic reports a clear, user-facing reason")
    void unrecognisedFileReportsReason(@TempDir Path dir) throws Exception {
        NbtCompound root = new NbtCompound();
        root.putString("Unrelated", "value");
        Path file = dir.resolve("random.dat");
        Files.write(file, gzip(root));

        SchematicParseException e = assertThrows(SchematicParseException.class,
            () -> new SchematicFileLoader().load(file));
        assertTrue(e.getMessage().contains("Could not recognise"), e.getMessage());
    }

    @Test
    @DisplayName("a material list handed to the schematic loader is redirected, not misread")
    void materialListIsRedirected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("materials.txt");
        Files.writeString(file, "minecraft:stone 64\n");

        SchematicParseException e = assertThrows(SchematicParseException.class,
            () -> new SchematicFileLoader().load(file));
        assertTrue(e.getMessage().contains("material list"), e.getMessage());
    }
}
