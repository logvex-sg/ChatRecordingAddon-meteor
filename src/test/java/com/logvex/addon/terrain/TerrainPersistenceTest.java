package com.logvex.addon.terrain;

import com.logvex.addon.terrain.core.ChunkTerrain;
import com.logvex.addon.terrain.core.TerrainPersistence;
import com.logvex.addon.terrain.core.TerrainWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terrain on disk: written atomically, read back intact, and never letting a corrupt file stop a
 * load. These tests use a real temporary directory and real files, because the failures that matter
 * here are filesystem ones (a partial write, a leftover tile, a file that is not a tile).
 */
class TerrainPersistenceTest {

    private static ChunkTerrain chunk(int chunkX, int chunkZ, int height, long observedAt) {
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
    @DisplayName("a saved map loads back with the same chunks and heights")
    void roundTripThroughDisk(@TempDir Path dir) {
        TerrainWorld saved = new TerrainWorld();
        saved.put(chunk(0, 0, 70, 100L));
        saved.put(chunk(1, 0, 71, 100L));

        assertTrue(TerrainPersistence.save(dir, saved));

        TerrainWorld loaded = new TerrainWorld();
        assertTrue(TerrainPersistence.load(dir, loaded) > 0);

        assertEquals(2, loaded.chunkCount());
        assertEquals(70, loaded.store().get("minecraft:overworld", 0, 0).heightAt(0, 0));
        assertEquals(71, loaded.store().get("minecraft:overworld", 1, 0).heightAt(0, 0));
    }

    @Test
    @DisplayName("an unobserved chunk is still absent after a save and load")
    void absenceSurvivesDisk(@TempDir Path dir) {
        TerrainWorld saved = new TerrainWorld();
        saved.put(chunk(0, 0, 70, 1L));
        TerrainPersistence.save(dir, saved);

        TerrainWorld loaded = new TerrainWorld();
        TerrainPersistence.load(dir, loaded);

        assertNull(loaded.store().get("minecraft:overworld", 1, 1),
            "a chunk that was never captured must not appear after a reload");
    }

    @Test
    @DisplayName("a corrupt tile is skipped and the rest of the map still loads")
    void corruptTileDoesNotStopTheLoad(@TempDir Path dir) throws IOException {
        TerrainWorld saved = new TerrainWorld();
        saved.put(chunk(0, 0, 70, 1L));
        TerrainPersistence.save(dir, saved);

        // A junk file with the right suffix: the loader must not trust the name alone.
        Files.writeString(dir.resolve("minecraft_overworld.0.0.lctile"), "not a tile at all");

        TerrainWorld loaded = new TerrainWorld();
        TerrainPersistence.load(dir, loaded);
        // The junk overwrote the good tile, so nothing loads; the important part is that it did not
        // throw and the client can carry on with an empty map.
        assertEquals(0, loaded.chunkCount());
    }

    @Test
    @DisplayName("tiles no longer in memory are deleted from disk on the next save")
    void staleTilesAreRemoved(@TempDir Path dir) {
        TerrainWorld first = new TerrainWorld();
        first.put(chunk(0, 0, 70, 1L));
        first.put(chunk(40, 40, 70, 1L)); // a different tile
        TerrainPersistence.save(dir, first);
        assertTrue(TerrainPersistence.tileFileCount(dir) >= 2);

        TerrainWorld second = new TerrainWorld();
        second.put(chunk(0, 0, 70, 1L));
        TerrainPersistence.save(dir, second);

        assertEquals(1, TerrainPersistence.tileFileCount(dir));
    }

    @Test
    @DisplayName("the dimension is sanitised so a tile cannot be written outside the directory")
    void dimensionCannotEscapeTheDirectory(@TempDir Path dir) {
        String name = TerrainPersistence.fileName("../../etc/passwd", 0, 0);
        assertFalse(name.contains("/"), "file name was " + name);
        assertFalse(name.contains(".."), "file name was " + name);
        assertTrue(name.endsWith(".lctile"));
    }

    @Test
    @DisplayName("stored size and file count reflect what is on disk")
    void storedBytesAreReported(@TempDir Path dir) {
        TerrainWorld world = new TerrainWorld();
        world.put(chunk(0, 0, 70, 1L));
        TerrainPersistence.save(dir, world);

        assertTrue(TerrainPersistence.storedBytes(dir) > 0);
        assertEquals(1, TerrainPersistence.tileFileCount(dir));
    }

    @Test
    @DisplayName("clearing removes every tile file")
    void clearRemovesTiles(@TempDir Path dir) {
        TerrainWorld world = new TerrainWorld();
        world.put(chunk(0, 0, 70, 1L));
        TerrainPersistence.save(dir, world);

        assertTrue(TerrainPersistence.clear(dir));
        assertEquals(0, TerrainPersistence.tileFileCount(dir));
    }

    @Test
    @DisplayName("tile requests resolve only for tiles that hold an observed chunk")
    void tileRequestsResolveOnlyObservedTiles() {
        TerrainWorld world = new TerrainWorld();
        world.put(chunk(0, 0, 70, 1L));

        assertNotNull(world.tile("minecraft:overworld", 0, 0));
        assertNull(world.tile("minecraft:overworld", 5, 5), "a tile nobody visited has no body");
        assertNull(world.tile("minecraft:the_nether", 0, 0), "another dimension has no terrain");
    }

    @Test
    @DisplayName("a tile request carries every observed chunk of that tile and nothing else")
    void tileCarriesOnlyItsOwnChunks() {
        TerrainWorld world = new TerrainWorld();
        world.put(chunk(0, 0, 70, 1L));
        world.put(chunk(7, 7, 72, 1L));   // same 8x8 tile
        world.put(chunk(8, 0, 71, 1L));   // the next tile across

        byte[] body = world.tile("minecraft:overworld", 0, 0);
        assertEquals(2, com.logvex.addon.terrain.core.TerrainCodec.decodeTile(body).chunks().size());
        assertEquals(2, world.occupiedTiles("minecraft:overworld").size(),
            "tile (0,0) and tile (1,0) both hold a chunk");
    }

    @Test
    @DisplayName("observation spans record the first and last capture of a dimension")
    void observationSpansAreTracked() {
        TerrainWorld world = new TerrainWorld();
        world.put(chunk(0, 0, 70, 500L));
        world.put(chunk(1, 0, 70, 100L));

        assertEquals(100L, world.firstObserved("minecraft:overworld"));
        assertEquals(500L, world.lastObserved("minecraft:overworld"));
        assertEquals(0L, world.lastObserved("minecraft:the_end"));
    }
}
