package com.logvex.addon.framework.systems.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.logvex.addon.LogAddon;
import com.logvex.addon.framework.Addon;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.framework.systems.modules.Modules;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Persists module and HUD configuration to {@code <gamedir>/logaddon/config.json}.
 *
 * <p>Writes are atomic (a temp file is moved into place) and serialised on a single lock, so a
 * crash mid-save can never leave a half-written config behind. Reads are tolerant: a missing file,
 * a truncated file or a value that no longer matches its type is skipped rather than aborting the
 * load, so a stale config never wipes the user's settings or stops the addon from starting.
 *
 * <p>Unknown keys are preserved on save, which is what lets an older build read a config written
 * by a newer one without destroying the fields it does not understand.
 */
public final class ConfigManager {
    private static final ConfigManager INSTANCE = new ConfigManager();
    private static final String FILE_NAME = "config.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final Object lock = new Object();

    private volatile boolean dirty;
    private JsonObject unknown = new JsonObject();
    /** Overrides the storage location; used by tests so they never touch the game directory. */
    private Path overridePath;

    private ConfigManager() {
    }

    public static ConfigManager get() {
        return INSTANCE;
    }

    /** Marks the config as needing a save; the periodic saver writes it soon after. */
    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public Path file() {
        if (overridePath != null) return overridePath;
        return Addon.FOLDER().toPath().resolve("logaddon").resolve(FILE_NAME);
    }

    /** Redirects persistence to {@code path}; intended for tests. */
    public void setFile(Path path) {
        this.overridePath = path;
    }

    /** Writes the config when it has changed since the last save. */
    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        JsonObject root = toJson();
        synchronized (lock) {
            Path path = file();
            try {
                Files.createDirectories(path.getParent());
                Path temp = path.resolveSibling(FILE_NAME + ".tmp");
                try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    gson.toJson(root, writer);
                }
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty = false;
            } catch (IOException e) {
                LogAddon.LOG.error("Could not save the addon config to {}", path, e);
            }
        }
    }

    public void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return;

        JsonObject root;
        synchronized (lock) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement parsed = gson.fromJson(reader, JsonElement.class);
                root = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (IOException | RuntimeException e) {
                LogAddon.LOG.warn("Could not read the addon config at {}; keeping defaults", path, e);
                return;
            }
        }

        applyModules(root);
        applyHud(root);
        applyClient(root);

        // Keep every key the file contained. On the next save the recognised ones are rebuilt from
        // live state and the rest are copied back, so a config written by a newer build is not
        // silently truncated by an older one.
        unknown = root.deepCopy();
    }

    // ------------------------------------------------------------------ serialise

    private JsonObject toJson() {
        JsonObject root = new JsonObject();

        JsonObject modules = new JsonObject();
        for (Module module : Modules.get().getAll()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("active", module.isActive());
            entry.add("settings", settingsToJson(module.settings));
            modules.add(module.name, entry);
        }
        root.add("modules", modules);

        JsonObject hud = new JsonObject();
        for (HudElement element : Hud.get().elements()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("active", true);
            entry.addProperty("x", element.getX());
            entry.addProperty("y", element.getY());
            entry.add("settings", settingsToJson(element.settings));
            hud.add(element.info.name, entry);
        }
        root.add("hud", hud);

        JsonObject client = new JsonObject();
        client.addProperty("prefix", ClientConfig.get().prefix());
        client.addProperty("text-scale", Hud.get().textScale());
        client.addProperty("theme-preset", ClientConfig.get().themePreset().name());
        root.add("client", client);

        // Re-attach keys this build does not understand so a newer config is not truncated.
        for (Map.Entry<String, JsonElement> entry : unknown.entrySet()) {
            if (!root.has(entry.getKey())) root.add(entry.getKey(), entry.getValue());
        }
        return root;
    }

    private JsonObject settingsToJson(com.logvex.addon.framework.settings.Settings settings) {
        JsonObject out = new JsonObject();
        for (Setting<?> setting : settings.all()) {
            try {
                out.add(setting.name, setting.toJson());
            } catch (RuntimeException e) {
                LogAddon.LOG.warn("Could not serialise setting {}", setting.name, e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ apply

    private void applyModules(JsonObject root) {
        JsonObject modules = object(root, "modules");
        if (modules == null) return;

        for (Module module : Modules.get().getAll()) {
            JsonObject entry = modules.has(module.name) && modules.get(module.name).isJsonObject()
                ? modules.getAsJsonObject(module.name) : null;
            if (entry == null) continue;

            applySettings(module.settings, entry);

            boolean active = booleanOr(entry, "active", module.isActive());
            if (active != module.isActive()) module.toggle();
        }
    }

    private void applyHud(JsonObject root) {
        JsonObject hud = object(root, "hud");
        if (hud == null) return;

        for (String name : hud.keySet()) {
            HudElementInfo<?> info = Hud.get().info(name);
            if (info == null) continue;
            JsonElement raw = hud.get(name);
            if (raw == null || !raw.isJsonObject()) continue;
            JsonObject entry = raw.getAsJsonObject();

            HudElement element = findHud(info);
            if (element == null) {
                if (!booleanOr(entry, "active", false)) continue;
                element = Hud.get().add(info, integerOr(entry, "x", 4), integerOr(entry, "y", 4));
            }
            if (element == null) continue;
            if (entry.has("x") || entry.has("y")) {
                element.setPos(integerOr(entry, "x", element.getX()), integerOr(entry, "y", element.getY()));
            }
            applySettings(element.settings, entry);
        }
    }

    private void applyClient(JsonObject root) {
        JsonObject client = object(root, "client");
        if (client == null) return;
        if (client.has("prefix")) {
            ClientConfig.get().setPrefix(stringOr(client, "prefix", ClientConfig.get().prefix()));
        }
        if (client.has("text-scale")) {
            Hud.get().setTextScale(doubleOr(client, "text-scale", Hud.get().textScale()));
        }
        // The theme is applied after load so the whole GUI starts in the saved colour scheme.
        ClientConfig.get().setThemePreset(
            com.logvex.addon.framework.gui.GuiThemePreset.parse(stringOr(client, "theme-preset", null)));
        com.logvex.addon.framework.gui.GuiThemes.applyPreset(ClientConfig.get().themePreset());
    }

    private void applySettings(com.logvex.addon.framework.settings.Settings settings, JsonObject entry) {
        if (!entry.has("settings") || !entry.get("settings").isJsonObject()) return;
        JsonObject values = entry.getAsJsonObject("settings");
        for (Setting<?> setting : settings.all()) {
            if (!values.has(setting.name)) continue;
            try {
                setting.fromJson(values.get(setting.name));
            } catch (RuntimeException e) {
                LogAddon.LOG.warn("Ignoring invalid value for setting {}", setting.name, e);
            }
        }
    }

    private HudElement findHud(HudElementInfo<?> info) {
        for (HudElement element : Hud.get().elements()) {
            if (element.info == info) return element;
        }
        return null;
    }

    // ------------------------------------------------------------------ json helpers

    private static JsonObject object(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static boolean booleanOr(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int integerOr(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double doubleOr(JsonObject object, String key, double fallback) {
        try {
            return object.has(key) ? object.get(key).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
