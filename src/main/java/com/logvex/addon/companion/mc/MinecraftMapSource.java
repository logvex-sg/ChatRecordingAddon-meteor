package com.logvex.addon.companion.mc;

import com.logvex.addon.companion.core.CompanionMapSource;
import com.logvex.addon.terrain.core.TerrainCodec;
import com.logvex.addon.terrain.core.TerrainWorld;
import com.logvex.addon.terrain.core.TerrainPersistence;
import com.logvex.addon.terrain.mc.TerrainService;
import com.logvex.addon.telemetry.mc.TelemetryService;
import com.logvex.addon.world.core.LocationCategory;
import com.logvex.addon.world.core.WorldPoint;
import com.logvex.addon.world.mc.WorldService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the map and telemetry from the real client services to the companion router.
 *
 * <p>Every value comes from something the client actually recorded: terrain from
 * {@link TerrainService} (chunks it captured), waypoints from {@link WorldService} (places it
 * marked), telemetry from {@link TelemetryService} (measurements it took). Nothing here synthesises
 * a coordinate, an item or a sample; when a service has nothing, the answer says so.
 *
 * <p>Read-only by construction: the only mutating method is the waypoint add/remove, which delegates
 * to the same world database the in-game screen uses, so the browser and the game never disagree.
 */
public final class MinecraftMapSource implements CompanionMapSource {
    @Override
    public Map<String, Object> terrainMeta() {
        TerrainWorld terrain = TerrainService.get().world();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tileChunks", TerrainCodec.TILE_CHUNKS);
        out.put("tileBlocks", TerrainCodec.TILE_CHUNKS * 16);
        out.put("totalChunks", terrain.chunkCount());
        out.put("mappedBlocks", terrain.mappedBlocks());
        out.put("capturedThisSession", TerrainService.get().capturedThisSession());
        out.put("enabled", TerrainService.get().isEnabled());

        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (String dimension : terrain.store().dimensions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dimension", dimension);
            entry.put("chunks", terrain.store().count(dimension));
            entry.put("mappedBlocks", terrain.store().mappedBlocks(dimension));
            entry.put("firstObserved", terrain.firstObserved(dimension));
            entry.put("lastObserved", terrain.lastObserved(dimension));
            int[] bounds = terrain.store().bounds(dimension);
            if (bounds != null) {
                entry.put("minChunkX", bounds[0]);
                entry.put("minChunkZ", bounds[1]);
                entry.put("maxChunkX", bounds[2]);
                entry.put("maxChunkZ", bounds[3]);
            }
            entry.put("biomes", terrain.store().biomeCounts(dimension));

            List<Map<String, Object>> tiles = new ArrayList<>();
            for (int[] tile : terrain.occupiedTiles(dimension)) {
                Map<String, Object> tileEntry = new LinkedHashMap<>();
                tileEntry.put("x", tile[0]);
                tileEntry.put("z", tile[1]);
                // Content revision for this tile. Without it the browser can only see that a tile
                // exists, not that its contents changed, so a tile whose chunks were re-read or
                // extended looks identical and is never refetched — the map appears frozen.
                tileEntry.put("rev", terrain.tileRevision(dimension, tile[0], tile[1]));
                tiles.add(tileEntry);
            }
            entry.put("tiles", tiles);
            dimensions.add(entry);
        }
        out.put("dimensions", dimensions);
        return out;
    }

    @Override
    public byte[] terrainTile(String dimension, int tileX, int tileZ) {
        return TerrainService.get().world().tile(dimension, tileX, tileZ);
    }

    @Override
    public List<Map<String, Object>> waypoints(String dimension) {
        List<Map<String, Object>> out = new ArrayList<>();
        var profile = WorldService.get().intelligence().current();
        if (profile == null) return out;
        for (WorldPoint point : profile.locations().all()) {
            if (dimension != null && !dimension.isBlank() && !dimension.equals(point.dimension())) continue;
            out.add(pointToMap(point));
        }
        return out;
    }

    @Override
    public Map<String, Object> addWaypoint(String name, String category, Integer x, Integer y, Integer z) {
        WorldService worlds = WorldService.get();
        WorldPoint point;
        if (x != null && y != null && z != null) {
            var mc = com.logvex.addon.framework.LogClientContext.mc;
            if (mc == null || mc.world == null) return null;
            point = worlds.intelligence().recordLocation(
                mc.world.getRegistryKey().getValue().toString(), x, y, z, name,
                parseCategory(category), System.currentTimeMillis());
        } else {
            // No coordinate given: mark where the player is standing, which is the common case.
            point = worlds.markHere(name, parseCategory(category));
        }
        return point == null ? null : pointToMap(point);
    }

    @Override
    public boolean removeWaypoint(String key) {
        var profile = WorldService.get().intelligence().current();
        if (profile == null) return false;
        boolean removed = profile.locations().remove(key);
        if (removed) WorldService.get().saveAsync();
        return removed;
    }

    @Override
    public Map<String, Object> telemetry() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series", TelemetryService.get().seriesForApi());
        out.put("recording", TelemetryService.get().isRecording());
        return out;
    }

    @Override
    public String telemetryCsv() {
        return TelemetryService.get().toCsv();
    }

    /** The stored size of the terrain on disk, for the storage panel. */
    public long terrainBytesOnDisk() {
        return TerrainPersistence.storedBytes(TerrainService.dataDir());
    }

    private static Map<String, Object> pointToMap(WorldPoint point) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", point.key());
        entry.put("name", point.name());
        entry.put("dimension", point.dimension());
        entry.put("x", point.x());
        entry.put("y", point.y());
        entry.put("z", point.z());
        entry.put("category", point.category().name().toLowerCase());
        entry.put("note", point.note());
        entry.put("firstSeen", point.firstSeen());
        entry.put("lastSeen", point.lastSeen());
        entry.put("visits", point.visits());
        return entry;
    }

    private static LocationCategory parseCategory(String value) {
        if (value == null || value.isBlank()) return LocationCategory.OTHER;
        try {
            return LocationCategory.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LocationCategory.OTHER;
        }
    }
}
