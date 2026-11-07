package com.logvex.addon.profiles;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.Settings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of saved bot profiles plus the shipped builtins, and the logic to apply one to a
 * {@link Settings} collection.
 *
 * <p>Applying a profile walks the live settings and, for each setting whose name the profile
 * contains, sets it from the stored JSON. A name the profile does not contain, or one that no longer
 * exists, is skipped — so adding or removing a setting never breaks an old profile, and applying a
 * profile never silently wipes a value it does not mention. The builtin {@code Default} profile
 * resets every setting to its declared default.
 *
 * <p>Minecraft-free: it only reads and writes {@link Setting} values, so it is unit testable.
 */
public final class ProfileManager {
    /** The name of the builtin profile that restores every setting to its default. */
    public static final String DEFAULT_NAME = "Default";

    private final Map<String, BotProfile> profiles = new LinkedHashMap<>();

    public ProfileManager() {
        for (BotProfile builtin : builtins()) {
            profiles.put(ProfileStore.key(builtin.name()), builtin);
        }
    }

    /** The shipped default profiles. Each sets only the settings it means to change. */
    public static List<BotProfile> builtins() {
        List<BotProfile> out = new ArrayList<>();
        out.add(new BotProfile(DEFAULT_NAME, true));

        out.add(new BotProfile("Performance Saver", true)
            .put("low-performance-mode", bool(true))
            .put("gather-radius", num(16))
            .put("max-scan-results", num(128))
            .put("actions-per-tick", num(1))
            .put("action-cooldown", num(250)));

        out.add(new BotProfile("Fast Gatherer", true)
            .put("low-performance-mode", bool(false))
            .put("gather-radius", num(64))
            .put("max-scan-results", num(1024))
            .put("actions-per-tick", num(4))
            .put("action-cooldown", num(0)));

        out.add(new BotProfile("Careful", true)
            .put("health-threshold", num(14))
            .put("food-threshold", num(14))
            .put("retry-limit", num(7))
            .put("task-timeout", num(300))
            .put("pause-on-danger", bool(true)));

        return out;
    }

    private static JsonElement bool(boolean value) {
        return new JsonPrimitive(value);
    }

    private static JsonElement num(Number value) {
        return new JsonPrimitive(value);
    }

    /** Loads user profiles from {@code root}, replacing any previously loaded user profiles. */
    public void load(Path root) {
        profiles.values().removeIf(profile -> !profile.builtin());
        for (BotProfile profile : ProfileStore.load(root)) {
            if (profile.name().isBlank()) continue;
            String key = ProfileStore.key(profile.name());
            if (profiles.containsKey(key) && profiles.get(key).builtin()) continue;
            profiles.put(key, profile);
        }
    }

    /** Persists the user profiles (builtins are not written). */
    public boolean save(Path root) {
        return ProfileStore.save(root, userProfiles());
    }

    public List<BotProfile> all() {
        return new ArrayList<>(profiles.values());
    }

    public List<BotProfile> userProfiles() {
        List<BotProfile> out = new ArrayList<>();
        for (BotProfile profile : profiles.values()) {
            if (!profile.builtin()) out.add(profile);
        }
        return out;
    }

    public BotProfile get(String name) {
        return profiles.get(ProfileStore.key(name));
    }

    public boolean exists(String name) {
        return profiles.containsKey(ProfileStore.key(name));
    }

    /** Creates or replaces a user profile with {@code name}, capturing the current settings. */
    public BotProfile capture(String name, Settings settings) {
        if (name == null || name.isBlank() || settings == null) return null;
        String trimmed = name.trim();
        BotProfile existing = profiles.get(ProfileStore.key(trimmed));
        if (existing != null && existing.builtin()) return null; // never overwrite a builtin

        BotProfile profile = new BotProfile(trimmed);
        for (Setting<?> setting : settings.all()) {
            if (setting.wasChanged()) profile.put(setting.name, setting.toJson());
        }
        profiles.put(ProfileStore.key(trimmed), profile);
        return profile;
    }

    /** Removes a user profile. Builtins cannot be removed. */
    public boolean remove(String name) {
        BotProfile profile = profiles.get(ProfileStore.key(name));
        if (profile == null || profile.builtin()) return false;
        profiles.remove(ProfileStore.key(name));
        return true;
    }

    /**
     * Applies {@code profile} to {@code settings}.
     *
     * @return the number of settings that were changed
     */
    public int apply(BotProfile profile, Settings settings) {
        if (profile == null || settings == null) return 0;

        if (profile.builtin() && DEFAULT_NAME.equalsIgnoreCase(profile.name()) && profile.size() == 0) {
            int changed = 0;
            for (Setting<?> setting : settings.all()) {
                if (setting.wasChanged()) {
                    setting.reset();
                    changed++;
                }
            }
            return changed;
        }

        int changed = 0;
        for (Setting<?> setting : settings.all()) {
            JsonElement value = profile.get(setting.name);
            if (value == null) continue;
            Object before = setting.toJson();
            setting.fromJson(value);
            if (!setting.toJson().equals(before)) changed++;
        }
        return changed;
    }
}
