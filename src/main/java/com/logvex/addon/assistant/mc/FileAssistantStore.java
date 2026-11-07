package com.logvex.addon.assistant.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantMemoryStore;
import com.logvex.addon.framework.Addon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A JSON file backing for {@link AssistantMemory}.
 *
 * <p>Writes are atomic (temp file plus move) and never throw to the caller: a failed save is
 * reported through {@link #lastError()} instead of interrupting a tick. The whole document is
 * serialised with an explicit {@code version} field so a future build can migrate it, and a
 * document from a newer build is refused rather than silently downgraded.
 */
public final class FileAssistantStore implements AssistantMemoryStore {
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private volatile String lastError = "";

    public FileAssistantStore(Path file) {
        this.file = file;
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public Optional<AssistantMemory> load() {
        if (!Files.exists(file)) return Optional.empty();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Object> raw = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) raw.put(entry.getKey(), entry.getValue());

            AssistantMemory memory = new AssistantMemory();
            if (!memory.migrate(raw)) {
                lastError = "The stored memory is from a newer build and was not loaded.";
                return Optional.empty();
            }
            readInto(memory, root);
            lastError = "";
            return Optional.of(memory);
        } catch (Exception e) {
            lastError = "Could not read the stored memory: " + e.getMessage();
            return Optional.empty();
        }
    }

    @Override
    public void save(AssistantMemory memory) {
        if (memory == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(writeFrom(memory)), StandardCharsets.UTF_8);
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

    // ------------------------------------------------------------------ read

    private static void readInto(AssistantMemory memory, JsonObject root) {
        if (root.has("updatedAt") && root.get("updatedAt").isJsonPrimitive()) {
            memory.setUpdatedAt(root.get("updatedAt").getAsLong());
        }
        if (root.has("facts") && root.get("facts").isJsonObject()) {
            JsonObject facts = root.getAsJsonObject("facts");
            for (String key : facts.keySet()) {
                JsonObject fact = facts.getAsJsonObject(key);
                memory.rememberFact(key, str(fact, "value"), longOr(fact, "updated", 0L));
            }
        }
        if (root.has("playerFacts") && root.get("playerFacts").isJsonObject()) {
            JsonObject players = root.getAsJsonObject("playerFacts");
            for (String player : players.keySet()) {
                if (!players.get(player).isJsonObject()) continue;
                JsonObject facts = players.getAsJsonObject(player);
                for (String key : facts.keySet()) {
                    JsonObject fact = facts.getAsJsonObject(key);
                    memory.rememberPlayerFact(player, key, str(fact, "value"), longOr(fact, "updated", 0L));
                }
            }
        }
        if (root.has("history") && root.get("history").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("history")) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                memory.recordConversation(str(entry, "player"), str(entry, "question"),
                    str(entry, "answer"), longOr(entry, "ts", 0L));
            }
        }
        if (root.has("counters") && root.get("counters").isJsonObject()) {
            JsonObject counters = root.getAsJsonObject("counters");
            for (String name : counters.keySet()) {
                memory.incrementCounter(name, counters.get(name).getAsLong());
            }
        }
    }

    // ------------------------------------------------------------------ write

    private static JsonObject writeFrom(AssistantMemory memory) {
        JsonObject root = new JsonObject();
        root.addProperty("version", AssistantMemory.CURRENT_VERSION);
        root.addProperty("updatedAt", memory.updatedAt());

        JsonObject facts = new JsonObject();
        for (AssistantMemory.Fact fact : memory.facts()) {
            JsonObject o = new JsonObject();
            o.addProperty("value", fact.value());
            o.addProperty("updated", fact.updatedAt());
            facts.add(fact.key(), o);
        }
        root.add("facts", facts);

        JsonObject playerFacts = new JsonObject();
        for (String player : memory.knownPlayers()) {
            JsonObject perPlayer = new JsonObject();
            for (AssistantMemory.Fact fact : memory.playerFacts(player)) {
                JsonObject o = new JsonObject();
                o.addProperty("value", fact.value());
                o.addProperty("updated", fact.updatedAt());
                perPlayer.add(fact.key(), o);
            }
            playerFacts.add(player, perPlayer);
        }
        root.add("playerFacts", playerFacts);

        JsonArray history = new JsonArray();
        for (AssistantMemory.ConversationEntry entry : memory.allHistory()) {
            JsonObject o = new JsonObject();
            o.addProperty("ts", entry.timestamp());
            o.addProperty("player", entry.player());
            o.addProperty("question", entry.question());
            o.addProperty("answer", entry.answer());
            history.add(o);
        }
        root.add("history", history);

        JsonObject counters = new JsonObject();
        for (Map.Entry<String, Long> entry : memory.counters().entrySet()) {
            counters.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("counters", counters);
        return root;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static long longOr(JsonObject o, String key, long fallback) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** The default memory file under the addon's data folder. */
    public static Path defaultPath() {
        return Addon.FOLDER().toPath().resolve("logaddon").resolve("assistant-memory.json");
    }
}
