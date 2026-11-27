package com.logvex.addon.terrain.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every chunk surface the client has observed, partitioned by dimension and bounded in size.
 *
 * <p>This is the terrain the browser map draws. It holds only what was actually received — a chunk
 * never observed has no entry at all, which is how "unexplored" is represented: absence, not a
 * guess. The store is Minecraft-free so eviction, queries and the tile format are all testable.
 *
 * <p>Eviction is oldest-observed first, because a chunk seen an hour ago is a worse picture of the
 * world than one seen a minute ago. The cap is per store, not per dimension, so a long session in
 * one dimension cannot silently push another out; callers that care use {@link #count(String)}.
 */
public final class TerrainStore {
    /** How many chunks are kept when no limit is configured. */
    public static final int DEFAULT_CAPACITY = 20_000;

    private final Map<String, Map<Long, ChunkTerrain>> byDimension = new LinkedHashMap<>();
    private int capacity = DEFAULT_CAPACITY;
    private long insertions;

    /** Packs a chunk coordinate into one key, so a map key is a primitive rather than a string. */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    /** Stores a chunk, replacing any previous observation of the same coordinates. */
    public void put(ChunkTerrain chunk) {
        if (chunk == null) return;
        Map<Long, ChunkTerrain> dimension = byDimension.computeIfAbsent(chunk.dimension(), d -> new LinkedHashMap<>());
        dimension.put(key(chunk.chunkX(), chunk.chunkZ()), chunk);
        insertions++;
        while (size() > capacity) evictOldest();
    }

    /** The chunk at those coordinates, or null when it was never observed. */
    public ChunkTerrain get(String dimension, int chunkX, int chunkZ) {
        Map<Long, ChunkTerrain> dimensionMap = byDimension.get(dimension == null ? "" : dimension);
        return dimensionMap == null ? null : dimensionMap.get(key(chunkX, chunkZ));
    }

    /** True when this exact chunk has been observed. */
    public boolean has(String dimension, int chunkX, int chunkZ) {
        return get(dimension, chunkX, chunkZ) != null;
    }

    /** Every dimension that has at least one observed chunk. */
    public List<String> dimensions() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<Long, ChunkTerrain>> entry : byDimension.entrySet()) {
            if (!entry.getValue().isEmpty()) out.add(entry.getKey());
        }
        return out;
    }

    /** Every observed chunk in one dimension. */
    public Collection<ChunkTerrain> chunks(String dimension) {
        Map<Long, ChunkTerrain> dimensionMap = byDimension.get(dimension == null ? "" : dimension);
        return dimensionMap == null ? List.of() : List.copyOf(dimensionMap.values());
    }

    /** Observed chunks in one dimension whose chunk coordinates fall inside a chunk-space box. */
    public List<ChunkTerrain> chunksIn(String dimension, int minChunkX, int minChunkZ,
                                       int maxChunkX, int maxChunkZ) {
        List<ChunkTerrain> out = new ArrayList<>();
        for (ChunkTerrain chunk : chunks(dimension)) {
            if (chunk.chunkX() < minChunkX || chunk.chunkX() > maxChunkX) continue;
            if (chunk.chunkZ() < minChunkZ || chunk.chunkZ() > maxChunkZ) continue;
            out.add(chunk);
        }
        return out;
    }

    /** How many chunks are stored for one dimension. */
    public int count(String dimension) {
        Map<Long, ChunkTerrain> dimensionMap = byDimension.get(dimension == null ? "" : dimension);
        return dimensionMap == null ? 0 : dimensionMap.size();
    }

    /** How many chunks are stored in total. */
    public int size() {
        int total = 0;
        for (Map<Long, ChunkTerrain> dimension : byDimension.values()) total += dimension.size();
        return total;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Bumped on every store, so a reader can tell whether anything changed since last poll. */
    public long revision() {
        return insertions;
    }

    public int capacity() {
        return capacity;
    }

    /** Sets the total chunk cap; {@code 0} means unlimited, which is never a good idea on disk. */
    public void setCapacity(int capacity) {
        this.capacity = Math.max(0, capacity);
        while (capacity > 0 && size() > capacity) evictOldest();
    }

    /** Drops every observed chunk. */
    public void clear() {
        byDimension.clear();
        insertions++;
    }

    /** Drops every observed chunk in one dimension. */
    public void clear(String dimension) {
        Map<Long, ChunkTerrain> removed = byDimension.remove(dimension == null ? "" : dimension);
        if (removed != null) insertions++;
    }

    /** The observed area in blocks, counting each chunk as a full 16x16 square. */
    public long mappedBlocks(String dimension) {
        return (long) count(dimension) * ChunkTerrain.SIZE * ChunkTerrain.SIZE;
    }

    /**
     * The bounding box of observed chunks in one dimension, as
     * {@code {minChunkX, minChunkZ, maxChunkX, maxChunkZ}}, or null when nothing is stored.
     */
    public int[] bounds(String dimension) {
        Collection<ChunkTerrain> chunks = chunks(dimension);
        if (chunks.isEmpty()) return null;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ChunkTerrain chunk : chunks) {
            minX = Math.min(minX, chunk.chunkX());
            minZ = Math.min(minZ, chunk.chunkZ());
            maxX = Math.max(maxX, chunk.chunkX());
            maxZ = Math.max(maxZ, chunk.chunkZ());
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    /** Counts observed chunks per biome in one dimension, for the biome overlay and statistics. */
    public Map<String, Integer> biomeCounts(String dimension) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ChunkTerrain chunk : chunks(dimension)) {
            for (String biome : chunk.biomeIds()) {
                if (biome.isEmpty()) continue;
                counts.merge(biome, 1, Integer::sum);
            }
        }
        return counts;
    }

    private void evictOldest() {
        ChunkTerrain oldest = null;
        String oldestDimension = null;
        long oldestKey = 0L;
        for (Map.Entry<String, Map<Long, ChunkTerrain>> entry : byDimension.entrySet()) {
            for (Map.Entry<Long, ChunkTerrain> chunkEntry : entry.getValue().entrySet()) {
                ChunkTerrain candidate = chunkEntry.getValue();
                if (oldest == null || candidate.observedAt() < oldest.observedAt()) {
                    oldest = candidate;
                    oldestDimension = entry.getKey();
                    oldestKey = chunkEntry.getKey();
                }
            }
        }
        if (oldest == null) return;
        Map<Long, ChunkTerrain> dimension = byDimension.get(oldestDimension);
        if (dimension != null) {
            dimension.remove(oldestKey);
            if (dimension.isEmpty()) byDimension.remove(oldestDimension);
        }
    }
}
