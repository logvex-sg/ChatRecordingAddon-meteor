package com.logvex.addon.world.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The coordinator over every {@link WorldProfile}: it knows which world is current and records
 * discoveries into it.
 *
 * <p>Minecraft-free by design. The Minecraft-facing service feeds it observations (the player's
 * position, the biome, the chunks entered, the blocks and structures seen) and it keeps them
 * partitioned per world, so data from one server never leaks into another. It also answers the
 * "do I already know a place like this?" question the exploration logic asks before wandering off.
 */
public final class WorldIntelligence {
    private final WorldProfileStore store = new WorldProfileStore();
    private String currentKey = "";

    public WorldProfileStore store() {
        return store;
    }

    /** Switches the active profile, creating it when needed, and stamps a visit. */
    public WorldProfile enterWorld(String serverAddress, String worldName, String label, long now) {
        String key = WorldProfile.buildKey(serverAddress, worldName);
        currentKey = key;
        WorldProfile profile = store.profileOrCreate(key, label);
        profile.touch(now);
        return profile;
    }

    public String currentKey() {
        return currentKey;
    }

    /** The active profile, or {@code null} when no world is entered. */
    public WorldProfile current() {
        return currentKey.isEmpty() ? null : store.profile(currentKey);
    }

    // ------------------------------------------------------------------ recording

    /** Records a named place, e.g. a base or a structure, into the current world. */
    public WorldPoint recordLocation(String dimension, int x, int y, int z, String name,
                                     LocationCategory category, long now) {
        WorldProfile profile = current();
        if (profile == null) return null;
        profile.touch(now);
        WorldPoint point = profile.locations().record(dimension, x, y, z, name, category, now);
        profile.discoveries().add(DiscoveryLog.Kind.LOCATION, dimension, x, y, z, name, now);
        return point;
    }

    /** Records a structure discovery (a village, a stronghold, a ruined portal, ...). */
    public void recordStructure(String dimension, int x, int y, int z, String structure, long now) {
        WorldProfile profile = current();
        if (profile == null) return;
        profile.touch(now);
        profile.locations().record(dimension, x, y, z, structure, LocationCategory.STRUCTURE, now);
        profile.discoveries().add(DiscoveryLog.Kind.STRUCTURE, dimension, x, y, z, structure, now);
    }

    /** Records the biome at a coordinate, counting distinct biomes visited. */
    public void recordBiome(String dimension, int x, int y, int z, String biome, long now) {
        WorldProfile profile = current();
        if (profile == null) return;
        profile.touch(now);
        boolean first = profile.biomes().count(biome) == 0;
        profile.biomes().observe(biome, x, y, z, now);
        if (first) profile.discoveries().add(DiscoveryLog.Kind.BIOME, dimension, x, y, z, biome, now);
    }

    /** Records an observed resource block, e.g. a diamond ore. */
    public void recordResource(String dimension, int x, int y, int z, String blockId, long now) {
        WorldProfile profile = current();
        if (profile == null) return;
        profile.touch(now);
        boolean first = profile.resources().count(blockId) == 0;
        profile.resources().observe(blockId, x, y, z, now);
        if (first) profile.discoveries().add(DiscoveryLog.Kind.RESOURCE, dimension, x, y, z, blockId, now);
    }

    /** Records that a chunk has been entered. Returns true when it was the first visit. */
    public boolean recordChunk(String dimension, int chunkX, int chunkZ, long now) {
        WorldProfile profile = current();
        if (profile == null) return false;
        profile.touch(now);
        boolean first = !profile.chunks().visited(dimension, chunkX, chunkZ);
        profile.chunks().visit(dimension, chunkX, chunkZ, now);
        if (first) {
            profile.discoveries().add(DiscoveryLog.Kind.CHUNK, dimension, chunkX << 4, 0, chunkZ << 4,
                "chunk " + chunkX + ", " + chunkZ, now);
        }
        return first;
    }

    // ------------------------------------------------------------------ queries

    /** Known places matching a query in the current world. */
    public List<WorldPoint> search(String query, LocationCategory category, String dimension) {
        WorldProfile profile = current();
        return profile == null ? List.of() : profile.locations().search(query, category, dimension);
    }

    /** The nearest known places to a coordinate in the current world. */
    public List<WorldPoint> nearest(String dimension, int x, int y, int z, int limit) {
        WorldProfile profile = current();
        return profile == null ? List.of() : profile.locations().nearest(dimension, x, y, z, limit);
    }

    /**
     * The nearest known place of {@code category} within {@code radius}, or {@code null}.
     *
     * <p>This is the "check what I already know before exploring" primitive: the caller asks for a
     * nearby base or structure and only explores when nothing is known.
     */
    public WorldPoint nearestKnown(String dimension, int x, int y, int z, LocationCategory category,
                                   double radius) {
        WorldPoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (WorldPoint point : nearest(dimension, x, y, z, 0)) {
            if (category != null && point.category() != category) continue;
            double dx = point.x() - x;
            double dy = point.y() - y;
            double dz = point.z() - z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= radius && distance < bestDistance) {
                bestDistance = distance;
                best = point;
            }
        }
        return best;
    }

    /** A snapshot for the GUI: every profile summarised, newest first. */
    public List<WorldProfile> profiles() {
        List<WorldProfile> out = new ArrayList<>(store.all());
        out.sort((a, b) -> Long.compare(b.lastSeen(), a.lastSeen()));
        return out;
    }

    // ------------------------------------------------------------------ import / export

    /** Exports the whole database as a plain map tree. */
    public java.util.Map<String, Object> exportMap() {
        return store.toMap();
    }

    /** Replaces the database from a previously exported map tree. */
    public void importMap(java.util.Map<?, ?> root) {
        store.fromMap(root);
    }
}
