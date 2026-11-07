package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.BotMemory;
import com.logvex.addon.bot.core.MemoryStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.logvex.addon.framework.LogClientContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A JSON file backing for {@link BotMemory}.
 *
 * <p>Writes are atomic (temp file plus move) and never throw to the caller: a failed save is
 * reported through the return of {@link #lastError()} instead of interrupting a tick. The whole
 * document is serialised with an explicit {@code version} field so a future build can migrate it.
 */
public final class FileMemoryStore implements MemoryStore {
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private volatile String lastError = "";

    public FileMemoryStore(Path file) {
        this.file = file;
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public Optional<BotMemory> load() {
        if (!Files.exists(file)) return Optional.empty();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Object> raw = toMap(root);
            BotMemory memory = new BotMemory();
            if (!memory.migrate(raw)) {
                lastError = "The stored memory is from a newer build and was not loaded.";
                return Optional.empty();
            }
            readInto(memory, root);
            return Optional.of(memory);
        } catch (Exception e) {
            lastError = "Could not read the stored memory: " + e.getMessage();
            return Optional.empty();
        }
    }

    @Override
    public void save(BotMemory memory) {
        if (memory == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = writeFrom(memory);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(root), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastError = "";
        } catch (IOException e) {
            lastError = "Could not save the memory: " + e.getMessage();
        }
    }

    @Override
    public void clear() {
        try {
            Files.deleteIfExists(file);
            lastError = "";
        } catch (IOException e) {
            lastError = "Could not clear the memory: " + e.getMessage();
        }
    }

    private static Map<String, Object> toMap(JsonObject root) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) raw.put(entry.getKey(), entry.getValue());
        return raw;
    }

    private static void readInto(BotMemory memory, JsonObject root) {
        if (root.has("home") && root.get("home").isJsonPrimitive()) {
            memory.setHome(root.get("home").getAsString());
        }
        if (root.has("waypoints") && root.get("waypoints").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("waypoints")) {
                JsonObject w = element.getAsJsonObject();
                BotMemory.Waypoint waypoint = new BotMemory.Waypoint(
                    str(w, "name"), str(w, "dimension"),
                    w.has("x") ? w.get("x").getAsInt() : 0,
                    w.has("y") ? w.get("y").getAsInt() : 0,
                    w.has("z") ? w.get("z").getAsInt() : 0);
                waypoint.note(str(w, "note"));
                if (w.has("createdAt")) waypoint.setCreatedAt(w.get("createdAt").getAsLong());
                memory.putWaypoint(waypoint);
            }
        }
        if (root.has("places") && root.get("places").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("places")) {
                JsonObject p = element.getAsJsonObject();
                BotMemory.KnownPlace place = new BotMemory.KnownPlace(
                    str(p, "kind"), str(p, "label"), str(p, "dimension"),
                    p.has("x") ? p.get("x").getAsInt() : 0,
                    p.has("y") ? p.get("y").getAsInt() : 0,
                    p.has("z") ? p.get("z").getAsInt() : 0);
                if (p.has("seenAt")) place.setSeenAt(p.get("seenAt").getAsLong());
                memory.addPlace(place);
            }
        }
    }

    private static JsonObject writeFrom(BotMemory memory) {
        JsonObject root = new JsonObject();
        root.addProperty("version", BotMemory.CURRENT_VERSION);
        root.addProperty("home", memory.homeName());
        var waypoints = new com.google.gson.JsonArray();
        for (BotMemory.Waypoint w : memory.waypoints()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", w.name());
            o.addProperty("dimension", w.dimension());
            o.addProperty("x", w.x());
            o.addProperty("y", w.y());
            o.addProperty("z", w.z());
            o.addProperty("note", w.note());
            o.addProperty("createdAt", w.createdAt());
            waypoints.add(o);
        }
        root.add("waypoints", waypoints);

        var places = new com.google.gson.JsonArray();
        for (BotMemory.KnownPlace p : memory.places(null)) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", p.kind());
            o.addProperty("label", p.label());
            o.addProperty("dimension", p.dimension());
            o.addProperty("x", p.x());
            o.addProperty("y", p.y());
            o.addProperty("z", p.z());
            o.addProperty("seenAt", p.seenAt());
            places.add(o);
        }
        root.add("places", places);
        return root;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /** The default memory file under the addon's data folder. */
    public static Path defaultPath() {
        return LogClientContext.FOLDER().toPath().resolve("logclient").resolve("bot-memory.json");
    }
}
