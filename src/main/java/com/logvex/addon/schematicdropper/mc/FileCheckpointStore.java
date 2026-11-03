package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.Checkpoint;
import com.logvex.addon.schematicdropper.core.CheckpointStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * File-backed checkpoint storage: one JSON file per schematic under {@code <config>/schematicdropper/checkpoints}.
 *
 * <p>Writes go to a temporary file and are then moved into place, so an interrupted save can never
 * leave a half-written checkpoint. A file that cannot be parsed is treated as absent rather than
 * crashing the load, which is the safe behaviour: the worst case is that the build is redone.
 */
public final class FileCheckpointStore implements CheckpointStore {
    private final Path directory;

    public FileCheckpointStore(Path directory) {
        this.directory = directory;
    }

    @Override
    public void save(Checkpoint checkpoint) {
        if (checkpoint == null) return;
        try {
            Files.createDirectories(directory);
            Path target = fileFor(checkpoint.schematicName());
            Path temp = Files.createTempFile(directory, "checkpoint", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write(toJson(checkpoint).toString());
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save checkpoint: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Checkpoint> load(String schematicName) {
        Path file = fileFor(schematicName);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) return Optional.empty();
            return Optional.ofNullable(fromJson(parsed.getAsJsonObject()));
        } catch (Exception e) {
            // A corrupt checkpoint is not fatal; the build simply restarts from the world state.
            return Optional.empty();
        }
    }

    @Override
    public List<Checkpoint> list() {
        List<Checkpoint> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) return out;
        try (var stream = Files.list(directory)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try (Reader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        Checkpoint checkpoint = fromJson(parsed.getAsJsonObject());
                        if (checkpoint != null) out.add(checkpoint);
                    }
                } catch (Exception ignored) {
                    // Skip unreadable files rather than failing the whole listing.
                }
            });
        } catch (IOException ignored) {
            return out;
        }
        out.sort(Comparator.comparingLong(Checkpoint::updatedAt).reversed());
        return out;
    }

    @Override
    public void delete(String schematicName) {
        try {
            Files.deleteIfExists(fileFor(schematicName));
        } catch (IOException ignored) {
            // Deleting a checkpoint that cannot be removed is not worth interrupting a build.
        }
    }

    public Path directory() {
        return directory;
    }

    private Path fileFor(String schematicName) {
        String safe = (schematicName == null || schematicName.isBlank() ? "build" : schematicName)
            .replaceAll("[^A-Za-z0-9._-]", "_");
        return directory.resolve(safe + ".json");
    }

    // ------------------------------------------------------------- serialisation

    static JsonObject toJson(Checkpoint checkpoint) {
        JsonObject root = new JsonObject();
        root.addProperty("version", checkpoint.version());
        root.addProperty("schematicName", checkpoint.schematicName());
        root.addProperty("fingerprint", checkpoint.schematicFingerprint());
        root.addProperty("originX", checkpoint.origin().x());
        root.addProperty("originY", checkpoint.origin().y());
        root.addProperty("originZ", checkpoint.origin().z());
        root.addProperty("createdAt", checkpoint.createdAt());
        root.addProperty("updatedAt", checkpoint.updatedAt());
        root.addProperty("totalBlocks", checkpoint.totalBlocks());
        root.addProperty("materialsPlaced", checkpoint.materialsPlaced());
        root.add("completed", positions(checkpoint.completed()));
        root.add("failed", positions(checkpoint.failed()));
        return root;
    }

    static Checkpoint fromJson(JsonObject root) {
        if (root == null) return null;
        int version = root.has("version") ? root.get("version").getAsInt() : -1;
        BlockPos3 origin = new BlockPos3(
            root.has("originX") ? root.get("originX").getAsInt() : 0,
            root.has("originY") ? root.get("originY").getAsInt() : 0,
            root.has("originZ") ? root.get("originZ").getAsInt() : 0);
        return new Checkpoint(
            version,
            root.has("schematicName") ? root.get("schematicName").getAsString() : "",
            root.has("fingerprint") ? root.get("fingerprint").getAsString() : "",
            origin,
            root.has("createdAt") ? root.get("createdAt").getAsLong() : 0,
            root.has("updatedAt") ? root.get("updatedAt").getAsLong() : 0,
            positions(root.getAsJsonArray("completed")),
            positions(root.getAsJsonArray("failed")),
            root.has("totalBlocks") ? root.get("totalBlocks").getAsInt() : 0,
            root.has("materialsPlaced") ? root.get("materialsPlaced").getAsLong() : 0);
    }

    private static JsonArray positions(Map<BlockPos3, String> map) {
        JsonArray array = new JsonArray();
        for (Map.Entry<BlockPos3, String> e : map.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", e.getKey().x());
            entry.addProperty("y", e.getKey().y());
            entry.addProperty("z", e.getKey().z());
            entry.addProperty("state", e.getValue());
            array.add(entry);
        }
        return array;
    }

    private static Map<BlockPos3, String> positions(JsonArray array) {
        Map<BlockPos3, String> out = new LinkedHashMap<>();
        if (array == null) return out;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            BlockPos3 pos = new BlockPos3(entry.get("x").getAsInt(), entry.get("y").getAsInt(),
                entry.get("z").getAsInt());
            out.put(pos, entry.has("state") ? entry.get("state").getAsString() : "");
        }
        return out;
    }
}
