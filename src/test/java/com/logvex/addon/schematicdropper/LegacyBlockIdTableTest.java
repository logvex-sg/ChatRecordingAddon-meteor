package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.mc.LegacyBlockIdTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The legacy id table resolves what it knows and refuses to guess what it does not. */
class LegacyBlockIdTableTest {
    private final LegacyBlockIdTable table = new LegacyBlockIdTable();

    @Test
    @DisplayName("metadata selects the correct plank and log variant")
    void variantsByMetadata() {
        assertEquals("minecraft:oak_planks", table.resolve(5, 0).id());
        assertEquals("minecraft:spruce_planks", table.resolve(5, 1).id());
        assertEquals("minecraft:birch_planks", table.resolve(5, 2).id());
        assertEquals("minecraft:spruce_log", table.resolve(17, 1).id());
        assertEquals("minecraft:dark_oak_log", table.resolve(162, 1).id());
    }

    @Test
    @DisplayName("coloured families map to the matching colour name")
    void colouredFamilies() {
        assertEquals("minecraft:white_wool", table.resolve(35, 0).id());
        assertEquals("minecraft:light_blue_wool", table.resolve(35, 3).id());
        assertEquals("minecraft:black_wool", table.resolve(35, 15).id());
        assertEquals("minecraft:orange_stained_glass", table.resolve(95, 1).id());
        assertEquals("minecraft:gray_terracotta", table.resolve(159, 7).id());
    }

    @Test
    @DisplayName("an unknown id is reported as unresolved rather than silently guessed")
    void unknownIdsReturnNull() {
        assertNull(table.resolve(9999, 0));
    }

    @Test
    @DisplayName("common building blocks resolve to a modern state")
    void commonBlocks() {
        BlockStateSpec stone = table.resolve(1, 0);
        assertNotNull(stone);
        assertEquals("minecraft:stone", stone.id());
        assertEquals("minecraft:quartz_pillar", table.resolve(155, 2).id());
        assertEquals("minecraft:stone_bricks", table.resolve(98, 0).id());
        assertEquals("minecraft:mossy_stone_bricks", table.resolve(98, 1).id());
    }
}
