package com.logvex.addon.world.core;

import com.logvex.addon.framework.utils.misc.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the world database as a single JSON file next to the logs.
 *
 * <p>Writes are atomic (a temp file then a move) and a file that cannot be read is treated as empty
 * rather than fatal: a corrupt world index must never stop the client from starting. Minecraft-free,
 * so the store can be exercised in a unit test with a temp directory.
 */
public final class WorldStore {
    private static final String FILE_NAME = "world-intelligence.json";

    private WorldStore() {
    }

    public static Path fileIn(Path root) {
        return root == null ? null : root.resolve(FILE_NAME);
    }

    /** Loads the database into {@code intelligence}, replacing whatever it held. */
    @SuppressWarnings("unchecked")
    public static void load(Path root, WorldIntelligence intelligence) {
        if (root == null || intelligence == null) return;
        Path file = fileIn(root);
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) return;
            Map<String, Object> parsed = Json.parse(text, Map.class);
            if (parsed != null) intelligence.importMap(parsed);
        } catch (IOException | RuntimeException e) {
            // A corrupt or unreadable file is ignored; the database simply starts empty.
        }
    }

    /** Writes the database atomically. Returns false when the write could not be completed. */
    public static boolean save(Path root, WorldIntelligence intelligence) {
        if (root == null || intelligence == null) return false;
        Path file = fileIn(root);
        if (file == null) return false;
        try {
            Files.createDirectories(root);
            String json = Json.write(intelligence.exportMap()).toString();
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Writes an arbitrary export map to {@code file}, used by the GUI's export action. */
    public static boolean exportTo(Path file, Map<String, Object> map) {
        if (file == null || map == null) return false;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(map).toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Reads an export file back into a map, or {@code null} when it cannot be read. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readExport(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) return null;
            return Json.parse(text, Map.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Convenience for tests and tools: the raw list of stored world keys in a map tree. */
    @SuppressWarnings("unchecked")
    public static List<String> worldKeys(Map<String, Object> map) {
        if (map == null) return List.of();
        Object worlds = map.get("worlds");
        if (!(worlds instanceof Map<?, ?> worldMap)) return List.of();
        return worldMap.keySet().stream().map(String::valueOf).toList();
    }
}
