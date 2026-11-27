package com.logvex.addon.terrain.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-memory terrain database: a {@link TerrainStore} plus the per-dimension counters the map and
 * the statistics engine report, and the tile index the browser asks for.
 *
 * <p>Minecraft-free on purpose. The capture layer fills it from real chunks; this class only decides
 * what is remembered, how it is grouped into tiles, and what a tile request resolves to. Keeping the
 * grouping here means the "which chunks are in this tile" rule is tested without a game, and the HTTP
 * layer stays a thin translation.
 */
public final class TerrainWorld {
    private final TerrainStore store = new TerrainStore();
    /** When each dimension was first and last observed, for "last updated" in the UI. */
    private final Map<String, long[]> observedSpan = new LinkedHashMap<>();

    public TerrainStore store() {
        return store;
    }

    /** Records one observed chunk, and updates the dimension's observation span. */
    public void put(ChunkTerrain chunk) {
        if (chunk == null) return;
        store.put(chunk);
        long[] span = observedSpan.computeIfAbsent(chunk.dimension(), d -> new long[]{chunk.observedAt(), chunk.observedAt()});
        span[0] = Math.min(span[0], chunk.observedAt());
        span[1] = Math.max(span[1], chunk.observedAt());
    }

    /** When a dimension was first observed, or 0 when it never was. */
    public long firstObserved(String dimension) {
        long[] span = observedSpan.get(dimension == null ? "" : dimension);
        return span == null ? 0L : span[0];
    }

    /** When a dimension was last observed, or 0 when it never was. */
    public long lastObserved(String dimension) {
        long[] span = observedSpan.get(dimension == null ? "" : dimension);
        return span == null ? 0L : span[1];
    }

    /**
     * The tile coordinates that hold at least one observed chunk in a dimension.
     *
     * <p>The browser asks for this once and then fetches only the tiles that exist, so a large area
     * with a few visited corners costs a few requests rather than one per tile in the bounding box.
     */
    public List<int[]> occupiedTiles(String dimension) {
        Map<Long, int[]> tiles = new LinkedHashMap<>();
        for (ChunkTerrain chunk : store.chunks(dimension)) {
            int tx = TerrainCodec.tileOf(chunk.chunkX());
            int tz = TerrainCodec.tileOf(chunk.chunkZ());
            tiles.putIfAbsent(TerrainStore.key(tx, tz), new int[]{tx, tz});
        }
        return List.copyOf(tiles.values());
    }

    /** Encodes one tile for the browser; null when no chunk in that tile was ever observed. */
    public byte[] tile(String dimension, int tileX, int tileZ) {
        int originX = TerrainCodec.tileOrigin(tileX);
        int originZ = TerrainCodec.tileOrigin(tileZ);
        List<ChunkTerrain> chunks = store.chunksIn(dimension, originX, originZ,
            originX + TerrainCodec.TILE_CHUNKS - 1, originZ + TerrainCodec.TILE_CHUNKS - 1);
        if (chunks.isEmpty()) return null;
        return TerrainCodec.encodeTile(dimension, originX, originZ, chunks);
    }

    /**
     * The newest observation time among the chunks in one tile, or 0 when the tile is empty.
     *
     * <p>This is the tile's content revision. The browser needs it because a tile can gain chunks
     * or have its surface re-read without its coordinates changing: a signature built from the tile
     * list alone stays identical while the picture underneath it goes stale, and the map then never
     * refetches. Comparing this value is what tells "nothing changed" apart from "same tile, new
     * contents".
     */
    public long tileRevision(String dimension, int tileX, int tileZ) {
        int originX = TerrainCodec.tileOrigin(tileX);
        int originZ = TerrainCodec.tileOrigin(tileZ);
        long newest = 0L;
        for (ChunkTerrain chunk : store.chunksIn(dimension, originX, originZ,
            originX + TerrainCodec.TILE_CHUNKS - 1, originZ + TerrainCodec.TILE_CHUNKS - 1)) {
            newest = Math.max(newest, chunk.observedAt());
        }
        return newest;
    }

    /** How many tiles are occupied in a dimension. */
    public int tileCount(String dimension) {
        return occupiedTiles(dimension).size();
    }

    /** The number of observed chunks across every dimension. */
    public int chunkCount() {
        return store.size();
    }

    /** The observed area in blocks across every dimension. */
    public long mappedBlocks() {
        long total = 0;
        for (String dimension : store.dimensions()) total += store.mappedBlocks(dimension);
        return total;
    }

    /** A rough size of the terrain database in bytes, for the statistics engine. */
    public long estimatedBytes() {
        // 256 columns * (2 height + 2 block index + 1 biome index) plus a small per-chunk header.
        return (long) store.size() * (256L * 5L + 64L);
    }
}
