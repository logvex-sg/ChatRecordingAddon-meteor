package com.logvex.addon.world.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The collection of {@link WorldProfile}s plus a JSON (de)serialiser.
 *
 * <p>One file holds every profile, each nested under its own key, so importing or exporting the whole
 * world database is a single document. Minecraft-free and defensive: a record that cannot be parsed
 * is skipped rather than failing the whole load, so one corrupt entry never loses the rest.
 */
public final class WorldProfileStore {
    /** Bump when the on-disk shape changes so old files can be migrated or rejected. */
    public static final int VERSION = 1;

    private final Map<String, WorldProfile> profiles = new LinkedHashMap<>();

    public WorldProfile profile(String key) {
        return profiles.get(key);
    }

    /** Returns the profile for {@code key}, creating it when absent. */
    public WorldProfile profileOrCreate(String key, String label) {
        WorldProfile profile = profiles.get(key);
        if (profile == null) {
            profile = new WorldProfile(key, label);
            profiles.put(key, profile);
        } else if (label != null && !label.isBlank()) {
            profile.label(label);
        }
        return profile;
    }

    public void put(WorldProfile profile) {
        if (profile != null) profiles.put(profile.key(), profile);
    }

    public boolean remove(String key) {
        return profiles.remove(key) != null;
    }

    public List<WorldProfile> all() {
        return new ArrayList<>(profiles.values());
    }

    public int size() {
        return profiles.size();
    }

    public void clear() {
        profiles.clear();
    }

    /** Serialises every profile to a plain map tree ready for JSON writing. */
    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        Map<String, Object> worlds = new LinkedHashMap<>();
        for (WorldProfile profile : profiles.values()) {
            Map<String, Object> world = new LinkedHashMap<>();
            world.put("label", profile.label());
            world.put("firstSeen", profile.firstSeen());
            world.put("lastSeen", profile.lastSeen());
            world.put("locations", profile.locations().toMaps());
            world.put("discoveries", profile.discoveries().toMaps());
            world.put("biomes", profile.biomes().toMaps());
            world.put("resources", profile.resources().toMaps());
            world.put("chunks", profile.chunks().toMaps());
            worlds.put(profile.key(), world);
        }
        root.put("worlds", worlds);
        return root;
    }

    /** Replaces every profile from a parsed map tree; a bad profile is skipped. */
    @SuppressWarnings("unchecked")
    public void fromMap(Map<?, ?> root) {
        profiles.clear();
        if (root == null) return;
        Object worldsRaw = root.get("worlds");
        if (!(worldsRaw instanceof Map<?, ?> worlds)) return;
        for (Map.Entry<?, ?> entry : worlds.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> world)) continue;
            try {
                WorldProfile profile = readProfile(String.valueOf(entry.getKey()), world);
                profiles.put(profile.key(), profile);
            } catch (RuntimeException e) {
                // A single unreadable world must not lose the others.
            }
        }
    }

    private static WorldProfile readProfile(String key, Map<?, ?> world) {
        WorldProfile profile = new WorldProfile(key, stringOf(world.get("label")));
        profile.setSeen(longOf(world.get("firstSeen")), longOf(world.get("lastSeen")));
        profile.locations().load(listOf(world.get("locations")));
        profile.discoveries().load(listOf(world.get("discoveries")));
        profile.biomes().load(listOf(world.get("biomes")));
        profile.resources().load(listOf(world.get("resources")));
        profile.chunks().load(listOf(world.get("chunks")));
        return profile;
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}
