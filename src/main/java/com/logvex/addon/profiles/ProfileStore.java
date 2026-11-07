package com.logvex.addon.profiles;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.logvex.addon.framework.utils.misc.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes {@link BotProfile}s as a single JSON file.
 *
 * <p>The file is a plain object of profile-name to setting-map, with a synthetic {@code version}
 * field. Writes are atomic (temp file plus move) and never throw: a failed save is reported through
 * the return value, so a full disk cannot interrupt a tick. A missing or corrupt file loads as
 * empty rather than fatal, and a builtin profile name is never overwritten from disk, so a user
 * cannot accidentally clobber a shipped default with a stale file.
 */
public final class ProfileStore {
    private static final String FILE_NAME = "profiles.json";
    private static final int VERSION = 1;

    private ProfileStore() {
    }

    /** The profiles file inside {@code root}. */
    public static Path fileIn(Path root) {
        return root == null ? null : root.resolve(FILE_NAME);
    }

    /** Loads user profiles from disk into {@code into}. Builtins already present are kept. */
    public static List<BotProfile> load(Path root) {
        List<BotProfile> out = new ArrayList<>();
        if (root == null) return out;
        Path file = fileIn(root);
        if (file == null || !Files.isRegularFile(file)) return out;

        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement parsed = Json.parse(text, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) return out;
            JsonObject rootObj = parsed.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : rootObj.entrySet()) {
                if ("version".equals(entry.getKey())) continue;
                if (!entry.getValue().isJsonObject()) continue;
                BotProfile profile = new BotProfile(entry.getKey());
                for (Map.Entry<String, JsonElement> value : entry.getValue().getAsJsonObject().entrySet()) {
                    profile.put(value.getKey(), value.getValue());
                }
                out.add(profile);
            }
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** Writes {@code profiles} atomically. Returns false when the write could not be completed. */
    public static boolean save(Path root, List<BotProfile> profiles) {
        if (root == null || profiles == null) return false;
        Path file = fileIn(root);
        if (file == null) return false;

        JsonObject rootObj = new JsonObject();
        rootObj.addProperty("version", VERSION);
        for (BotProfile profile : profiles) {
            if (profile.name().isBlank() || profile.builtin()) continue;
            JsonObject values = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : profile.values().entrySet()) {
                values.add(entry.getKey(), entry.getValue());
            }
            rootObj.add(profile.name(), values);
        }

        try {
            Files.createDirectories(root);
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, rootObj.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
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

    /** A stable key for a profile name, so lookups ignore case and surrounding space. */
    public static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
