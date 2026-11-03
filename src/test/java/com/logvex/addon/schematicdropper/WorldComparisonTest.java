package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.core.BlockTraits;
import com.logvex.addon.schematicdropper.core.ComparisonStatus;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.core.SchematicFormat;
import com.logvex.addon.schematicdropper.core.WorldComparison;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comparison of a schematic against a live world. */
class WorldComparisonTest {
    private final NewFakes.FakeBlockWorld world = new NewFakes.FakeBlockWorld();
    private final BlockTraits traits = new NewFakes.FakeTraits();

    private static Schematic schematic(Map<BlockPos3, String> blocks) {
        Map<BlockPos3, BlockStateSpec> states = new LinkedHashMap<>();
        for (Map.Entry<BlockPos3, String> e : blocks.entrySet()) {
            states.put(e.getKey(), BlockStateSpec.parse(e.getValue()));
        }
        return new Schematic("test", SchematicFormat.SPONGE, 4, 4, 4, states);
    }

    @Test
    @DisplayName("a block already matching the schematic is reported correct")
    void correctBlock() {
        Schematic s = schematic(Map.of(new BlockPos3(1, 1, 1), "minecraft:stone"));
        world.blocks.put(new BlockPos3(10, 1, 1), BlockStateSpec.parse("minecraft:stone"));

        WorldComparison c = WorldComparison.compare(s, world, new BlockPos3(9, 0, 0), traits);

        assertEquals(1, c.count(ComparisonStatus.CORRECT));
        assertEquals(0, c.needingWork());
    }

    @Test
    @DisplayName("an air position where a block is required is missing")
    void missingBlock() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));

        WorldComparison c = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);

        assertEquals(1, c.count(ComparisonStatus.MISSING));
        assertEquals(1, c.needingWork());
    }

    @Test
    @DisplayName("the same block with different properties is incorrect, not a conflict")
    void wrongProperties() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:oak_stairs[facing=east]"));
        world.blocks.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:oak_stairs[facing=west]"));

        WorldComparison c = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);

        assertEquals(1, c.count(ComparisonStatus.INCORRECT));
        assertEquals(1, c.needingWork());
    }

    @Test
    @DisplayName("an unrelated block is a conflict and is never treated as work")
    void conflict() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        world.blocks.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:diamond_block"));

        WorldComparison c = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);

        assertEquals(1, c.count(ComparisonStatus.CONFLICT));
        assertEquals(0, c.needingWork());
        assertTrue(c.entries().get(0).isConflict());
    }

    @Test
    @DisplayName("a replaceable block such as grass is treated as missing")
    void replaceableIsMissing() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        world.blocks.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:short_grass"));

        WorldComparison c = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);

        assertEquals(1, c.count(ComparisonStatus.MISSING));
    }

    @Test
    @DisplayName("an unloaded chunk is unknown, not missing")
    void unloadedChunk() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        world.unloaded.add(new BlockPos3(0, 0, 0));

        WorldComparison c = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);

        assertEquals(1, c.count(ComparisonStatus.UNKNOWN));
        assertEquals(0, c.needingWork());
    }

    @Test
    @DisplayName("the origin shifts every position")
    void originOffsets() {
        Schematic s = schematic(Map.of(
            new BlockPos3(0, 0, 0), "minecraft:stone",
            new BlockPos3(2, 0, 0), "minecraft:stone"));
        world.blocks.put(new BlockPos3(100, 64, 200), BlockStateSpec.parse("minecraft:stone"));

        WorldComparison c = WorldComparison.compare(s, world, new BlockPos3(100, 64, 200), traits);

        assertEquals(2, c.total());
        assertEquals(1, c.count(ComparisonStatus.CORRECT));
        assertEquals(1, c.count(ComparisonStatus.MISSING));
    }

    @Test
    @DisplayName("a schematic with no geometry compares to nothing")
    void noGeometry() {
        WorldComparison c = WorldComparison.compare(Schematic.empty("empty"), world, new BlockPos3(0, 0, 0), traits);
        assertEquals(0, c.total());
        assertFalse(c.needingWork() > 0);
    }

    @Test
    @DisplayName("matching ignores properties the schematic does not declare")
    void partialPropertyMatch() {
        // The schematic declares facing only; the world state has facing and half.
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:oak_stairs[facing=east]"));
        world.blocks.put(new BlockPos3(0, 0, 0),
            BlockStateSpec.parse("minecraft:oak_stairs[facing=east,half=bottom]"));

        WorldComparison c = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);

        assertEquals(1, c.count(ComparisonStatus.CORRECT));
    }
}
