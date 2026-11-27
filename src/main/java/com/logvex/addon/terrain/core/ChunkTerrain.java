package com.logvex.addon.terrain.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The observed surface of one 16x16 chunk: for every column, the height of the top solid block, the
 * block at that height and the biome there.
 *
 * <p>This is a record of what the client was actually given, never an estimate. A column the client
 * has not loaded is stored as unknown ({@link #UNKNOWN}), so a chunk that was only half received
 * renders half empty rather than inventing the rest. Minecraft-free, so the map maths and the wire
 * format are tested without a game.
 *
 * <p>Columns are stored as palettes plus indices rather than as strings: a chunk is 256 columns and
 * almost all of them share a handful of block ids, so a palette turns ~10 KB of names into a few
 * hundred bytes. That is what makes a whole region small enough to send to the browser.
 */
public final class ChunkTerrain {
    /** Chunks are 16x16 blocks. */
    public static final int SIZE = 16;
    /** A column with no observed surface. */
    public static final int UNKNOWN = -1;

    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final long observedAt;

    /** Surface height per column, row-major {@code z * 16 + x}; {@link #UNKNOWN} when not observed. */
    private final short[] heights;
    private final short[] blockIndices;
    private final byte[] biomeIndices;
    private final String[] blockPalette;
    private final String[] biomePalette;
    private final int knownColumns;

    private ChunkTerrain(String dimension, int chunkX, int chunkZ, long observedAt,
                         short[] heights, short[] blockIndices, byte[] biomeIndices,
                         String[] blockPalette, String[] biomePalette, int knownColumns) {
        this.dimension = dimension == null ? "" : dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.observedAt = observedAt;
        this.heights = heights;
        this.blockIndices = blockIndices;
        this.biomeIndices = biomeIndices;
        this.blockPalette = blockPalette;
        this.biomePalette = biomePalette;
        this.knownColumns = knownColumns;
    }

    /**
     * Builds a chunk from per-column observations.
     *
     * @param heights 256 surface heights, row-major, {@link #UNKNOWN} where nothing was seen
     * @param blocks  256 surface block ids, or null entries where nothing was seen
     * @param biomes  256 biome ids, or null entries where nothing was seen
     */
    public static ChunkTerrain of(String dimension, int chunkX, int chunkZ, long observedAt,
                                  int[] heights, String[] blocks, String[] biomes) {
        if (heights == null || heights.length != SIZE * SIZE) {
            throw new IllegalArgumentException("heights must hold " + (SIZE * SIZE) + " columns");
        }
        Palette blockPalette = new Palette();
        Palette biomePalette = new Palette();
        short[] blockIndices = new short[SIZE * SIZE];
        byte[] biomeIndices = new byte[SIZE * SIZE];
        short[] stored = new short[SIZE * SIZE];
        int known = 0;

        for (int i = 0; i < heights.length; i++) {
            if (heights[i] == UNKNOWN) {
                stored[i] = UNKNOWN;
                blockIndices[i] = -1;
                biomeIndices[i] = -1;
                continue;
            }
            known++;
            stored[i] = (short) heights[i];
            blockIndices[i] = (short) blockPalette.indexOrAdd(blocks == null ? "" : blocks[i]);
            biomeIndices[i] = (byte) biomePalette.indexOrAdd(biomes == null ? "" : biomes[i]);
        }
        return new ChunkTerrain(dimension, chunkX, chunkZ, observedAt, stored, blockIndices, biomeIndices,
            blockPalette.toArray(), biomePalette.toArray(), known);
    }

    /** Rebuilds a chunk from decoded parts; used by the codec, which has already validated lengths. */
    static ChunkTerrain fromParts(String dimension, int chunkX, int chunkZ, long observedAt,
                                  short[] heights, short[] blockIndices, byte[] biomeIndices,
                                  String[] blockPalette, String[] biomePalette) {
        int known = 0;
        for (short h : heights) if (h != UNKNOWN) known++;
        return new ChunkTerrain(dimension, chunkX, chunkZ, observedAt, heights, blockIndices, biomeIndices,
            blockPalette, biomePalette, known);
    }

    public String dimension() {
        return dimension;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public long observedAt() {
        return observedAt;
    }

    /** How many of the 256 columns were actually observed. */
    public int knownColumns() {
        return knownColumns;
    }

    /** True when every column in the chunk was observed. */
    public boolean complete() {
        return knownColumns == SIZE * SIZE;
    }

    /** The surface height at a column inside the chunk, or {@link #UNKNOWN}. */
    public int heightAt(int localX, int localZ) {
        int i = index(localX, localZ);
        return i < 0 ? UNKNOWN : heights[i];
    }

    /** The surface block id at a column inside the chunk, or "" when unknown. */
    public String blockAt(int localX, int localZ) {
        int i = index(localX, localZ);
        if (i < 0 || blockIndices[i] < 0) return "";
        int p = blockIndices[i];
        return p < blockPalette.length ? blockPalette[p] : "";
    }

    /** The biome id at a column inside the chunk, or "" when unknown. */
    public String biomeAt(int localX, int localZ) {
        int i = index(localX, localZ);
        if (i < 0 || biomeIndices[i] < 0) return "";
        int p = biomeIndices[i];
        return p < biomePalette.length ? biomePalette[p] : "";
    }

    private static int index(int localX, int localZ) {
        if (localX < 0 || localX >= SIZE || localZ < 0 || localZ >= SIZE) return -1;
        return localZ * SIZE + localX;
    }

    short[] heightsArray() {
        return heights;
    }

    short[] blockIndicesArray() {
        return blockIndices;
    }

    byte[] biomeIndicesArray() {
        return biomeIndices;
    }

    String[] blockPalette() {
        return blockPalette;
    }

    String[] biomePalette() {
        return biomePalette;
    }

    /** The distinct block ids in this chunk, for the map's colour legend. */
    public List<String> blockIds() {
        return List.of(blockPalette);
    }

    /** The distinct biome ids in this chunk. */
    public List<String> biomeIds() {
        return List.of(biomePalette);
    }

    /** A small palette builder: first-seen order, so the same input always encodes identically. */
    private static final class Palette {
        private final Map<String, Integer> indices = new LinkedHashMap<>();
        private final List<String> values = new ArrayList<>();

        int indexOrAdd(String value) {
            String key = value == null ? "" : value;
            Integer existing = indices.get(key);
            if (existing != null) return existing;
            int next = values.size();
            if (next > Short.MAX_VALUE) return 0; // A palette this large cannot happen for one chunk.
            indices.put(key, next);
            values.add(key);
            return next;
        }

        String[] toArray() {
            return values.toArray(new String[0]);
        }
    }
}
