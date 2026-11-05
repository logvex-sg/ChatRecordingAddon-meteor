package com.logvex.addon.bot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A cached picture of the world around the player, refreshed on a schedule rather than every tick.
 *
 * <p>Rescanning the world every tick is the fastest way to make an addon unusable. This tracker
 * keeps the last scan, records when it was taken, and only rescans when the caller asks and the
 * cache is older than the configured interval. Block lookups fall through to the live world when
 * the cache is stale, so a decision is never made on data that is arbitrarily old.
 */
public final class WorldStateTracker {
    /** How long a cached scan stays usable, in milliseconds. */
    private static final long CACHE_TTL_MS = 1000;

    /** One scan: where the player was, and what was found. */
    private record Scan(long at, BlockPos3 origin, String dimension,
                        Map<String, List<BlockPos3>> blocks,
                        List<BlockPos3> containers,
                        List<String> entityTypes) {
    }

    private Scan cache;
    private long lastScanAt;

    /** Refreshes the cache if it is stale. Safe to call every tick. */
    public void refresh(WorldPort world, BotConfig config, long nowMs) {
        if (world == null || !world.canRun()) {
            cache = null;
            return;
        }
        if (cache != null && nowMs - lastScanAt < CACHE_TTL_MS) return;

        BlockPos3 origin = world.playerPos();
        if (origin == null) return;

        Map<String, List<BlockPos3>> blocks = new LinkedHashMap<>();
        List<BlockPos3> containers = new ArrayList<>(world.findContainers(config.containerRadius()));
        List<String> entities = new ArrayList<>(world.nearbyEntityTypes(config.entityRadius()));
        cache = new Scan(nowMs, origin, world.dimension(), blocks, containers, entities);
        lastScanAt = nowMs;
    }

    /** Forces the next {@link #refresh} to rescan. */
    public void invalidate() {
        cache = null;
    }

    public boolean hasData() {
        return cache != null;
    }

    public BlockPos3 origin() {
        return cache == null ? null : cache.origin();
    }

    public String dimension() {
        return cache == null ? "" : cache.dimension();
    }

    public long lastScanAt() {
        return lastScanAt;
    }

    /**
     * Blocks of the requested ids near the player, limited by the config's scan cap. Results are
     * de-duplicated and sorted by distance so the nearest is always first.
     */
    public List<BlockPos3> findBlocks(WorldPort world, List<String> blockIds, int radius) {
        if (world == null || !world.canRun() || blockIds == null || blockIds.isEmpty()) return List.of();
        List<BlockPos3> found = world.findBlocks(blockIds, radius);
        Set<BlockPos3> unique = new LinkedHashSet<>(found);
        List<BlockPos3> out = new ArrayList<>(unique);
        BlockPos3 origin = world.playerPos();
        if (origin != null) out.sort(java.util.Comparator.comparingInt(origin::manhattan));
        return out;
    }

    public List<BlockPos3> containers() {
        return cache == null ? List.of() : new ArrayList<>(cache.containers());
    }

    public List<String> entityTypes() {
        return cache == null ? List.of() : new ArrayList<>(cache.entityTypes());
    }

    /** True when any of the given hostile entity ids is present in the cached scan. */
    public boolean anyEntityMatching(List<String> hostileIds) {
        if (cache == null || hostileIds == null) return false;
        for (String type : cache.entityTypes()) {
            if (hostileIds.contains(type)) return true;
        }
        return false;
    }

    public void clear() {
        cache = null;
        lastScanAt = 0;
    }
}
