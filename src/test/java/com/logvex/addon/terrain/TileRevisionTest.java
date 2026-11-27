package com.logvex.addon.terrain;

import com.logvex.addon.terrain.core.ChunkTerrain;
import com.logvex.addon.terrain.core.TerrainWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tile's content revision must change whenever the terrain inside it changes.
 *
 * <p>This is the browser's only way to tell "same tile, same picture" from "same tile, new
 * contents". The client re-reads a chunk it has held for 30 s and captures new chunks as the player
 * walks, so a tile whose coordinates never move can still go stale. When the revision did not exist
 * the map keyed its redraw on the tile list alone, never refetched, and appeared frozen — so these
 * tests pin the property the map depends on rather than the field itself.
 */
class TileRevisionTest {

    private static ChunkTerrain flat(int chunkX, int chunkZ, int height, long observedAt) {
        int[] heights = new int[256];
        String[] blocks = new String[256];
        String[] biomes = new String[256];
        for (int i = 0; i < 256; i++) {
            heights[i] = height;
            blocks[i] = "minecraft:stone";
            biomes[i] = "minecraft:plains";
        }
        return ChunkTerrain.of("minecraft:overworld", chunkX, chunkZ, observedAt, heights, blocks, biomes);
    }

    @Test
    @DisplayName("an empty tile has no revision")
    void anEmptyTileHasNoRevision() {
        TerrainWorld world = new TerrainWorld();
        assertEquals(0L, world.tileRevision("minecraft:overworld", 0, 0));
    }

    @Test
    @DisplayName("the revision is the newest observation in the tile")
    void theRevisionIsTheNewestObservation() {
        TerrainWorld world = new TerrainWorld();
        world.put(flat(0, 0, 64, 1_000L));
        world.put(flat(1, 0, 65, 5_000L));

        assertEquals(5_000L, world.tileRevision("minecraft:overworld", 0, 0));
    }

    @Test
    @DisplayName("re-reading a chunk in the same tile raises the revision")
    void reReadingAChunkRaisesTheRevision() {
        TerrainWorld world = new TerrainWorld();
        world.put(flat(0, 0, 64, 1_000L));
        long before = world.tileRevision("minecraft:overworld", 0, 0);

        world.put(flat(0, 0, 70, 2_000L));
        long after = world.tileRevision("minecraft:overworld", 0, 0);

        assertTrue(after > before, "a re-read chunk must raise the tile revision");
        assertEquals(2_000L, after);
    }

    @Test
    @DisplayName("capturing a new chunk in the same tile raises the revision")
    void capturingANewChunkRaisesTheRevision() {
        TerrainWorld world = new TerrainWorld();
        world.put(flat(0, 0, 64, 1_000L));
        long before = world.tileRevision("minecraft:overworld", 0, 0);

        // Chunk (3, 3) still falls inside tile (0, 0), so the tile list is unchanged.
        world.put(flat(3, 3, 64, 1_500L));

        assertTrue(world.tileRevision("minecraft:overworld", 0, 0) > before,
            "a new chunk inside an existing tile must raise its revision");
    }

    @Test
    @DisplayName("an untouched tile keeps its revision, so no needless refetch happens")
    void anUntouchedTileKeepsItsRevision() {
        TerrainWorld world = new TerrainWorld();
        world.put(flat(0, 0, 64, 1_000L));
        world.put(flat(0, 1, 64, 1_000L));
        long first = world.tileRevision("minecraft:overworld", 0, 0);

        // Reading twice must not itself look like a change.
        assertEquals(first, world.tileRevision("minecraft:overworld", 0, 0));
    }

    @Test
    @DisplayName("a tile in another dimension has its own revision")
    void anotherDimensionHasItsOwnRevision() {
        TerrainWorld world = new TerrainWorld();
        world.put(flat(0, 0, 64, 1_000L));

        assertEquals(0L, world.tileRevision("minecraft:the_nether", 0, 0));
        assertTrue(world.tileRevision("minecraft:overworld", 0, 0) > 0);
    }
}
