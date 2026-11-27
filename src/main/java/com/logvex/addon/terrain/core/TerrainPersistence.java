package com.logvex.addon.terrain.core;


import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores observed terrain on disk as one binary tile file per tile, and reads it back.
 *
 * <p>A single JSON file was the wrong shape here: a long session is thousands of chunks, and
 * rewriting all of them on every save would cost seconds and risk a truncated file. Tiles are
 * written individually and atomically (temp file, then move), so a crash mid-save loses at most the
 * tile being written and never the whole map.
 *
 * <p>The file name carries the dimension and tile coordinate, which means loading is a directory
 * listing rather than parsing a manifest that could disagree with the files. A file that cannot be
 * read is skipped: a corrupt tile must never stop the client from starting.
 *
 * <p>Runs only on the terrain writer thread; never call it from the render or network thread.
 */
public final class TerrainPersistence {
    private static final String SUFFIX = ".lctile";

    private TerrainPersistence() {
    }

    /** Writes every tile of every dimension, replacing the files that are there. */
    public static boolean save(Path root, TerrainWorld world) {
        if (root == null || world == null) return false;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            return false;
        }
        boolean ok = true;
        List<Path> written = new ArrayList<>();
        for (String dimension : world.store().dimensions()) {
            for (int[] tile : world.occupiedTiles(dimension)) {
                byte[] body = world.tile(dimension, tile[0], tile[1]);
                if (body == null) continue;
                Path file = root.resolve(fileName(dimension, tile[0], tile[1]));
                if (writeAtomically(file, body)) written.add(file);
                else ok = false;
            }
        }
        // Remove tiles that no longer hold anything, so an evicted chunk does not linger on disk.
        deleteStale(root, written);
        return ok;
    }

    /** Loads every readable tile under {@code root} into {@code world}. */
    public static int load(Path root, TerrainWorld world) {
        if (root == null || world == null || !Files.isDirectory(root)) return 0;
        int loaded = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*" + SUFFIX)) {
            for (Path file : stream) {
                byte[] body;
                try {
                    body = Files.readAllBytes(file);
                } catch (IOException e) {
                    continue;
                }
                if (!TerrainCodec.looksLikeTile(body)) continue;
                try {
                    TerrainCodec.Tile tile = TerrainCodec.decodeTile(body);
                    for (ChunkTerrain chunk : tile.chunks()) world.put(chunk);
                    loaded++;
                } catch (RuntimeException e) {
                    // A tile this build cannot read is skipped; the rest of the map still loads.
                }
            }
        } catch (IOException e) {
            return loaded;
        }
        return loaded;
    }

    /** The file a tile is stored in. The dimension is sanitised so it cannot escape the directory. */
    public static String fileName(String dimension, int tileX, int tileZ) {
        String safe = dimension == null ? "" : dimension.replaceAll("[^a-zA-Z0-9_.-]", "_");
        // Collapsing dot runs and stripping leading dots is what actually blocks traversal: a name
        // like "../../etc/passwd" survives the character filter as ".._.._etc_passwd", which has no
        // separator but still reads as a relative path to anything that later normalises it.
        safe = safe.replaceAll("\\.{2,}", ".").replaceAll("^\\.+", "");
        if (safe.isEmpty()) safe = "unknown";
        return safe + "." + tileX + "." + tileZ + SUFFIX;
    }

    private static boolean writeAtomically(Path file, byte[] body) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(tmp, body);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Nothing more can be done; the next save tries again.
            }
            return false;
        }
    }

    private static void deleteStale(Path root, List<Path> written) {
        Map<String, Boolean> keep = new LinkedHashMap<>();
        for (Path path : written) keep.put(path.getFileName().toString(), Boolean.TRUE);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*" + SUFFIX)) {
            for (Path file : stream) {
                if (keep.containsKey(file.getFileName().toString())) continue;
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // A tile we could not delete is simply rewritten next time.
                }
            }
        } catch (IOException ignored) {
            // Listing failing is not a save failure.
        }
    }

    /** The total size of the stored tiles, for the statistics engine. */
    public static long storedBytes(Path root) {
        if (root == null || !Files.isDirectory(root)) return 0L;
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*" + SUFFIX)) {
            for (Path file : stream) {
                try {
                    total += Files.size(file);
                } catch (IOException ignored) {
                    // A file that vanished between listing and measuring contributes nothing.
                }
            }
        } catch (IOException e) {
            return total;
        }
        return total;
    }

    /** The number of tile files on disk. */
    public static int tileFileCount(Path root) {
        if (root == null || !Files.isDirectory(root)) return 0;
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*" + SUFFIX)) {
            for (Path ignored : stream) count++;
        } catch (IOException e) {
            return count;
        }
        return count;
    }

    /** Drops every stored tile; used by the map's "clear terrain" control. */
    public static boolean clear(Path root) {
        if (root == null || !Files.isDirectory(root)) return false;
        boolean ok = true;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*" + SUFFIX)) {
            for (Path file : stream) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    ok = false;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return ok;
    }

    /** Convenience for tests: the store a loaded directory produced. */
    static TerrainStore storeOf(TerrainWorld world) {
        return world.store();
    }
}
