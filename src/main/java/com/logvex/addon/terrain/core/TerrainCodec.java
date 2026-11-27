package com.logvex.addon.terrain.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Encodes observed chunks into one compressed binary tile, and decodes it again.
 *
 * <p>A tile is a square group of chunks (see {@link #TILE_CHUNKS}) sent as a single body, because one
 * HTTP request per chunk would be hundreds of round trips for a small area. Only chunks that exist are
 * written, so a hole in the tile is a hole on the map: unexplored terrain stays unexplored, and the
 * browser never has to guess a height for it.
 *
 * <p>The layout is little-endian to match {@code DataView} in the browser, and the payload is
 * deflated because a surface is mostly repeated heights and a handful of block ids. A 8x8 chunk tile
 * that would be ~150 KB as JSON is typically 3-8 KB here.
 *
 * <p>Minecraft-free and dependency-free, so a round trip is a plain unit test.
 */
public final class TerrainCodec {
    /** Chunks per tile edge; 8 means one tile covers 128x128 blocks. */
    public static final int TILE_CHUNKS = 8;
    /** "LCTM" — Log Client Terrain Map. */
    public static final int MAGIC = 0x4C43544D;
    public static final byte VERSION = 1;

    private TerrainCodec() {
    }

    /**
     * Encodes the chunks of one tile.
     *
     * @param originChunkX the tile's first chunk X, so a decoder can place it without guessing
     * @param originChunkZ the tile's first chunk Z
     * @param chunks       the observed chunks belonging to this tile; others are ignored
     */
    public static byte[] encodeTile(String dimension, int originChunkX, int originChunkZ, List<ChunkTerrain> chunks) {
        List<ChunkTerrain> inside = new ArrayList<>();
        for (ChunkTerrain chunk : chunks) {
            int gx = chunk.chunkX() - originChunkX;
            int gz = chunk.chunkZ() - originChunkZ;
            if (gx < 0 || gx >= TILE_CHUNKS || gz < 0 || gz >= TILE_CHUNKS) continue;
            inside.add(chunk);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        writeInt(out, MAGIC);
        out.write(VERSION);
        out.write(0); // reserved, keeps every following field 2-byte aligned
        writeShort(out, inside.size());
        writeShort(out, TILE_CHUNKS);
        writeInt(out, originChunkX);
        writeInt(out, originChunkZ);
        byte[] dimensionBytes = (dimension == null ? "" : dimension).getBytes(StandardCharsets.UTF_8);
        out.write(dimensionBytes.length);
        out.write(dimensionBytes, 0, dimensionBytes.length);

        for (ChunkTerrain chunk : inside) {
            writeShort(out, chunk.chunkX() - originChunkX);
            writeShort(out, chunk.chunkZ() - originChunkZ);
            writeLong(out, chunk.observedAt());
            writePalette(out, chunk.blockPalette(), true);
            writePalette(out, chunk.biomePalette(), false);
            for (short height : chunk.heightsArray()) writeShort(out, height);
            for (short index : chunk.blockIndicesArray()) writeShort(out, index);
            for (byte index : chunk.biomeIndicesArray()) out.write(index);
        }
        return deflate(out.toByteArray());
    }

    /** Decodes a tile produced by {@link #encodeTile}; returns the chunks it carries. */
    public static Tile decodeTile(byte[] body) {
        ByteBuffer in = ByteBuffer.wrap(inflate(body)).order(ByteOrder.LITTLE_ENDIAN);
        if (in.remaining() < 16) throw new IllegalArgumentException("tile too short");
        int magic = in.getInt();
        if (magic != MAGIC) throw new IllegalArgumentException("not a terrain tile");
        byte version = in.get();
        if (version != VERSION) throw new IllegalArgumentException("unsupported tile version " + version);
        in.get(); // reserved
        int count = in.getShort() & 0xFFFF;
        int tileChunks = in.getShort() & 0xFFFF;
        int originChunkX = in.getInt();
        int originChunkZ = in.getInt();
        int dimensionLength = in.get() & 0xFF;
        byte[] dimensionBytes = new byte[dimensionLength];
        in.get(dimensionBytes);
        String dimension = new String(dimensionBytes, StandardCharsets.UTF_8);

        List<ChunkTerrain> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int gx = in.getShort();
            int gz = in.getShort();
            long observedAt = in.getLong();
            String[] blockPalette = readPalette(in, true);
            String[] biomePalette = readPalette(in, false);

            short[] heights = new short[ChunkTerrain.SIZE * ChunkTerrain.SIZE];
            for (int c = 0; c < heights.length; c++) heights[c] = in.getShort();
            short[] blockIndices = new short[heights.length];
            for (int c = 0; c < blockIndices.length; c++) blockIndices[c] = in.getShort();
            byte[] biomeIndices = new byte[heights.length];
            in.get(biomeIndices);

            chunks.add(ChunkTerrain.fromParts(dimension, originChunkX + gx, originChunkZ + gz, observedAt,
                heights, blockIndices, biomeIndices, blockPalette, biomePalette));
        }
        return new Tile(dimension, originChunkX, originChunkZ, tileChunks, chunks);
    }

    /** A decoded tile: where it sits and which chunks it carried. */
    public record Tile(String dimension, int originChunkX, int originChunkZ, int tileChunks,
                       List<ChunkTerrain> chunks) {
    }

    /** The tile coordinate that holds a chunk; floors, so negative coordinates tile correctly. */
    public static int tileOf(int chunkCoord) {
        return Math.floorDiv(chunkCoord, TILE_CHUNKS);
    }

    /** The first chunk coordinate of a tile. */
    public static int tileOrigin(int tileCoord) {
        return tileCoord * TILE_CHUNKS;
    }

    private static void writePalette(ByteArrayOutputStream out, String[] palette, boolean wide) {
        if (wide) writeShort(out, palette.length);
        else out.write(palette.length);
        for (String entry : palette) {
            byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
            if (wide) writeShort(out, bytes.length);
            else out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
    }

    private static String[] readPalette(ByteBuffer in, boolean wide) {
        int length = wide ? (in.getShort() & 0xFFFF) : (in.get() & 0xFF);
        String[] palette = new String[length];
        for (int i = 0; i < length; i++) {
            int size = wide ? (in.getShort() & 0xFFFF) : (in.get() & 0xFF);
            byte[] bytes = new byte[size];
            in.get(bytes);
            palette[i] = new String(bytes, StandardCharsets.UTF_8);
        }
        return palette;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 0; shift < 64; shift += 8) out.write((int) ((value >>> shift) & 0xFF));
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                if (n == 0 && deflater.needsInput()) break;
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] body) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(body);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, body.length * 4));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n;
                try {
                    n = inflater.inflate(buffer);
                } catch (DataFormatException e) {
                    throw new IllegalArgumentException("corrupt terrain tile", e);
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                    throw new IllegalArgumentException("corrupt terrain tile");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /** Convenience for tests and callers that hold one chunk: encode it as a single-chunk tile. */
    public static byte[] encodeSingle(ChunkTerrain chunk) {
        return encodeTile(chunk.dimension(), chunk.chunkX(), chunk.chunkZ(), List.of(chunk));
    }

    /** Reads a tile body's chunk count without decoding every column, for a cheap index. */
    public static int peekChunkCount(byte[] body) {
        ByteBuffer in = ByteBuffer.wrap(inflate(body)).order(ByteOrder.LITTLE_ENDIAN);
        if (in.remaining() < 16 || in.getInt() != MAGIC) return 0;
        in.get(); // version
        in.get(); // reserved
        return in.getShort() & 0xFFFF;
    }

    /** True when the body is a tile this codec understands; used to reject junk before decoding. */
    public static boolean looksLikeTile(byte[] body) {
        if (body == null || body.length < 4) return false;
        try {
            ByteBuffer in = ByteBuffer.wrap(inflate(body)).order(ByteOrder.LITTLE_ENDIAN);
            return in.remaining() >= 4 && in.getInt() == MAGIC;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
