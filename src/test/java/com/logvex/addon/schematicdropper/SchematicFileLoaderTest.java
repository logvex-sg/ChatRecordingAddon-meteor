package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.mc.SchematicFileLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtShort;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtIo;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicFileLoaderTest {
    private static NbtCompound sponge() {
        NbtCompound root = new NbtCompound();
        root.putInt("Version", 2);
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:stone", 0);
        palette.putInt("minecraft:dirt", 1);
        root.put("Palette", palette);
        root.putInt("PaletteMax", 2);
        root.putShort("Width", (short) 3);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        root.putByteArray("BlockData", new byte[]{0, 1, 0});
        return root;
    }

    private static byte[] gzip(NbtCompound c) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStream gz = new GZIPOutputStream(bos); DataOutputStream out = new DataOutputStream(gz)) {
            NbtIo.writeCompound(c, out);
        }
        return bos.toByteArray();
    }

    @Test
    @DisplayName("a gzipped sponge .schem loads through the real loader")
    void loadsSponge(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("build.schem");
        Files.write(file, gzip(sponge()));

        Schematic s = new SchematicFileLoader().load(file);

        assertEquals(3, s.sizeX());
        assertEquals(3, s.blockCount());
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 0, 0)).id());
        assertEquals("minecraft:dirt", s.at(new BlockPos3(1, 0, 0)).id());
    }

    @Test
    @DisplayName("a Sponge v3 .schem with the Blocks container loads from disk")
    void loadsSpongeV3(@TempDir Path dir) throws Exception {
        NbtCompound root = new NbtCompound();
        root.putInt("Version", 3);
        root.putInt("DataVersion", 3953);
        root.putIntArray("Size", new int[]{3, 1, 1});
        NbtCompound blocks = new NbtCompound();
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:quartz_pillar", 0);
        palette.putInt("minecraft:spruce_planks", 1);
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", new byte[]{0, 1, 0});
        root.put("Blocks", blocks);

        Path file = dir.resolve("modern.schem");
        Files.write(file, gzip(root));

        Schematic s = new SchematicFileLoader().load(file);

        assertEquals(3, s.sizeX());
        assertEquals("minecraft:quartz_pillar", s.at(new BlockPos3(0, 0, 0)).id());
        assertEquals("minecraft:spruce_planks", s.at(new BlockPos3(1, 0, 0)).id());
        assertEquals(2, s.toMaterialPlan().size());
    }

    @Test
    @DisplayName("a real WorldEdit v3 file wrapped in a Schematic compound loads")
    void loadsWrappedWorldEditV3(@TempDir Path dir) throws Exception {
        // WorldEdit 7.3+ writes an empty root name and a literal "Schematic" child holding the
        // payload (see the Sponge schematic specification). Reading the root directly finds no
        // palette and rejects the file, which is the "can't import schematics" symptom.
        NbtCompound payload = new NbtCompound();
        payload.putInt("Version", 3);
        payload.putInt("DataVersion", 3953);
        payload.putShort("Width", (short) 3);
        payload.putShort("Height", (short) 1);
        payload.putShort("Length", (short) 1);
        NbtCompound blocks = new NbtCompound();
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:quartz_pillar", 0);
        palette.putInt("minecraft:spruce_planks", 1);
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", new byte[]{0, 1, 0});
        payload.put("Blocks", blocks);

        NbtCompound root = new NbtCompound();
        root.put("Schematic", payload);

        Path file = dir.resolve("wrapped.schem");
        Files.write(file, gzip(root));

        Schematic s = new SchematicFileLoader().load(file);

        assertEquals(3, s.sizeX());
        assertEquals("minecraft:quartz_pillar", s.at(new BlockPos3(0, 0, 0)).id());
        assertEquals("minecraft:spruce_planks", s.at(new BlockPos3(1, 0, 0)).id());
        assertEquals(2, s.toMaterialPlan().size());
    }

    @Test
    @DisplayName("a realistic WorldEdit v3 file with block states and multiple layers loads")
    void loadsRealisticWorldEditV3(@TempDir Path dir) throws Exception {
        // 2x2x2: air, a block state with properties, and blocks on both y layers. This mirrors the
        // shape WorldEdit writes (dimensions in "Size", everything else under "Blocks").
        NbtCompound root = new NbtCompound();
        root.putInt("Version", 3);
        root.putInt("DataVersion", 3953);
        root.putIntArray("Size", new int[]{2, 2, 2});

        NbtCompound blocks = new NbtCompound();
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:oak_stairs[facing=east,half=bottom]", 1);
        palette.putInt("minecraft:stone", 2);
        blocks.put("Palette", palette);
        // Index order is y*length*width + z*width + x: (0,0,0),(1,0,0),(0,0,1),(1,0,1),(0,1,0)...
        blocks.putByteArray("Data", new byte[]{1, 2, 0, 2, 2, 2, 2, 2});
        root.put("Blocks", blocks);

        Path file = dir.resolve("house.schem");
        Files.write(file, gzip(root));

        Schematic s = new SchematicFileLoader().load(file);

        assertEquals(2, s.sizeX());
        assertEquals(2, s.sizeY());
        assertEquals(2, s.sizeZ());
        // Air is dropped, so 7 of the 8 cells are real blocks.
        assertEquals(7, s.blockCount());
        assertEquals("minecraft:oak_stairs", s.at(new BlockPos3(0, 0, 0)).id());
        assertTrue(s.at(new BlockPos3(0, 0, 0)).hasProperties());
        assertEquals("minecraft:stone", s.at(new BlockPos3(0, 1, 0)).id());
        assertEquals("minecraft:stone", s.at(new BlockPos3(1, 1, 1)).id());
    }
}
