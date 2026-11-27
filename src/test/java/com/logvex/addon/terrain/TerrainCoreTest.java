package com.logvex.addon.terrain;

import com.logvex.addon.terrain.core.ChunkTerrain;
import com.logvex.addon.terrain.core.TerrainCodec;
import com.logvex.addon.terrain.core.TerrainStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terrain core: what was observed, what was not, and whether it survives the wire.
 *
 * <p>The point of these tests is the "unexplored stays unexplored" rule. A surface that was never
 * received must come back as unknown through the store, through the binary codec and after a
 * round trip — never as a zero height, which a renderer would draw as flat ground at sea level.
 */
class TerrainCoreTest {

    /** A fully observed chunk at a flat height, with one biome and one block id. */
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

    /** A chunk where only the first {@code known} columns were observed. */
    private static ChunkTerrain partial(int chunkX, int chunkZ, int known) {
        int[] heights = new int[256];
        String[] blocks = new String[256];
        String[] biomes = new String[256];
        for (int i = 0; i < 256; i++) {
            if (i < known) {
                heights[i] = 64 + i;
                blocks[i] = "minecraft:dirt";
                biomes[i] = "minecraft:forest";
            } else {
                heights[i] = ChunkTerrain.UNKNOWN;
            }
        }
        return ChunkTerrain.of("minecraft:overworld", chunkX, chunkZ, 1000L, heights, blocks, biomes);
    }

    @Test
    @DisplayName("an unobserved column reads as unknown, not as a height")
    void unobservedColumnsAreUnknown() {
        ChunkTerrain chunk = partial(0, 0, 10);

        assertEquals(10, chunk.knownColumns());
        assertFalse(chunk.complete());
        assertEquals(ChunkTerrain.UNKNOWN, chunk.heightAt(10, 0));
        assertEquals("", chunk.blockAt(10, 0));
        assertEquals("", chunk.biomeAt(10, 0));

        // The columns that were observed keep their values.
        assertEquals(64, chunk.heightAt(0, 0));
        assertEquals("minecraft:dirt", chunk.blockAt(0, 0));
        assertEquals("minecraft:forest", chunk.biomeAt(0, 0));
    }

    @Test
    @DisplayName("a chunk built from a wrong column count is refused")
    void wrongColumnCountIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> ChunkTerrain.of("minecraft:overworld", 0, 0, 0L, new int[10], null, null));
    }

    @Test
    @DisplayName("the store returns null for a chunk that was never observed")
    void storeKeepsAbsenceAsAbsence() {
        TerrainStore store = new TerrainStore();
        store.put(flat(0, 0, 70, 100L));

        assertNotNull(store.get("minecraft:overworld", 0, 0));
        assertNull(store.get("minecraft:overworld", 1, 0), "a neighbour was never observed");
        assertNull(store.get("minecraft:the_nether", 0, 0), "another dimension is a separate store");
        assertFalse(store.has("minecraft:overworld", 5, 5));
    }

    @Test
    @DisplayName("storing the same chunk twice replaces it rather than duplicating it")
    void storeReplacesRatherThanDuplicates() {
        TerrainStore store = new TerrainStore();
        store.put(flat(3, 4, 70, 100L));
        store.put(flat(3, 4, 80, 200L));

        assertEquals(1, store.size());
        assertEquals(80, store.get("minecraft:overworld", 3, 4).heightAt(0, 0));
        assertEquals(200L, store.get("minecraft:overworld", 3, 4).observedAt());
    }

    @Test
    @DisplayName("eviction drops the oldest observation and keeps the newest")
    void evictionDropsOldestFirst() {
        TerrainStore store = new TerrainStore();
        store.setCapacity(2);
        store.put(flat(0, 0, 60, 100L));
        store.put(flat(1, 0, 61, 200L));
        store.put(flat(2, 0, 62, 300L));

        assertEquals(2, store.size());
        assertNull(store.get("minecraft:overworld", 0, 0), "the oldest chunk was evicted");
        assertNotNull(store.get("minecraft:overworld", 1, 0));
        assertNotNull(store.get("minecraft:overworld", 2, 0));
    }

    @Test
    @DisplayName("dimensions are counted separately and a clear only affects one")
    void dimensionsAreIndependent() {
        TerrainStore store = new TerrainStore();
        store.put(flat(0, 0, 70, 1L));
        store.put(ChunkTerrain.of("minecraft:the_nether", 0, 0, 1L,
            filled(50), filled("minecraft:netherrack"), filled("minecraft:nether_wastes")));

        assertEquals(1, store.count("minecraft:overworld"));
        assertEquals(1, store.count("minecraft:the_nether"));
        assertEquals(2, store.size());
        assertEquals(List.of("minecraft:overworld", "minecraft:the_nether"), store.dimensions());

        store.clear("minecraft:the_nether");
        assertEquals(1, store.size());
        assertNotNull(store.get("minecraft:overworld", 0, 0));
    }

    @Test
    @DisplayName("bounds cover the observed chunks only")
    void boundsCoverObservedChunks() {
        TerrainStore store = new TerrainStore();
        store.put(flat(-2, 5, 70, 1L));
        store.put(flat(3, -1, 70, 1L));

        assertArrayEquals(new int[]{-2, -1, 3, 5}, store.bounds("minecraft:overworld"));
        assertNull(store.bounds("minecraft:the_end"));
    }

    @Test
    @DisplayName("a chunk survives a binary tile round trip with its unknowns intact")
    void codecRoundTripsAndPreservesUnknowns() {
        ChunkTerrain chunk = partial(7, -3, 40);
        byte[] tile = TerrainCodec.encodeTile("minecraft:overworld", 7, -3, List.of(chunk));

        TerrainCodec.Tile decoded = TerrainCodec.decodeTile(tile);
        assertEquals(1, decoded.chunks().size());
        assertEquals("minecraft:overworld", decoded.dimension());

        ChunkTerrain back = decoded.chunks().get(0);
        assertEquals(7, back.chunkX());
        assertEquals(-3, back.chunkZ());
        assertEquals(40, back.knownColumns());
        assertEquals(64, back.heightAt(0, 0));
        assertEquals(ChunkTerrain.UNKNOWN, back.heightAt(0, 3), "the 40th column is (8,2); (0,3) is past it");
        assertEquals("", back.blockAt(15, 15));
    }

    @Test
    @DisplayName("a tile carries only the chunks that belong to it")
    void tileIgnoresChunksOutsideItsSquare() {
        List<ChunkTerrain> chunks = new ArrayList<>();
        chunks.add(flat(0, 0, 70, 1L));   // inside an 8x8 tile at origin 0
        chunks.add(flat(7, 7, 70, 1L));   // inside
        chunks.add(flat(8, 0, 70, 1L));   // outside: belongs to the next tile

        byte[] tile = TerrainCodec.encodeTile("minecraft:overworld", 0, 0, chunks);
        assertEquals(2, TerrainCodec.decodeTile(tile).chunks().size());
    }

    @Test
    @DisplayName("negative coordinates tile downwards, not towards zero")
    void negativeCoordinatesFloorCorrectly() {
        assertEquals(-1, TerrainCodec.tileOf(-1));
        assertEquals(-1, TerrainCodec.tileOf(-8));
        assertEquals(-2, TerrainCodec.tileOf(-9));
        assertEquals(0, TerrainCodec.tileOf(0));
        assertEquals(1, TerrainCodec.tileOf(8));
        assertEquals(-8, TerrainCodec.tileOrigin(-1));
    }

    @Test
    @DisplayName("a corrupt or foreign body is rejected rather than decoded into nonsense")
    void corruptBodiesAreRejected() {
        assertFalse(TerrainCodec.looksLikeTile(new byte[]{1, 2, 3, 4}));
        assertFalse(TerrainCodec.looksLikeTile(null));
        assertThrows(IllegalArgumentException.class, () -> TerrainCodec.decodeTile(new byte[]{9, 9, 9}));
    }

    @Test
    @DisplayName("a tile compresses a full chunk far below its raw size")
    void tilesAreCompressed() {
        List<ChunkTerrain> chunks = new ArrayList<>();
        for (int i = 0; i < 8; i++) chunks.add(flat(i, 0, 70, 1L));

        byte[] tile = TerrainCodec.encodeTile("minecraft:overworld", 0, 0, chunks);
        // Eight full chunks are ~4 KB of heights plus palettes before deflate; the payload must be
        // smaller than the heights alone, or compression is not doing its job.
        assertTrue(tile.length < 8 * 256 * 2, "tile was " + tile.length + " bytes");
        assertEquals(8, TerrainCodec.peekChunkCount(tile));
    }

    private static int[] filled(int value) {
        int[] out = new int[256];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static String[] filled(String value) {
        String[] out = new String[256];
        java.util.Arrays.fill(out, value);
        return out;
    }
}
