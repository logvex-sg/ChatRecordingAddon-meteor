package com.logvex.addon.world.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded record of which chunks have been visited, keyed by dimension and chunk coordinate.
 *
 * <p>A chunk is recorded once; revisiting it only refreshes the timestamp. When the cap is reached
 * the least-recently-visited chunk is dropped, so the set stays a bounded window over the explored
 * area rather than growing with every step the player takes. Minecraft-free.
 */
public final class VisitedChunks {
    /** Hard cap on tracked chunks. */
    public static final int MAX_CHUNKS = 40_000;

    private final Map<String, long[]> chunks = new LinkedHashMap<>();

    /** Records a visit to a chunk; returns true when this was the first time. */
    public boolean visit(String dimension, int chunkX, int chunkZ, long now) {
        String key = key(dimension, chunkX, chunkZ);
        long[] existing = chunks.get(key);
        if (existing != null) {
            existing[0] = now;
            return false;
        }
        if (chunks.size() >= MAX_CHUNKS) evictOldest();
        chunks.put(key, new long[]{now});
        return true;
    }

    private void evictOldest() {
        String oldest = null;
        long lowest = Long.MAX_VALUE;
        for (Map.Entry<String, long[]> entry : chunks.entrySet()) {
            if (entry.getValue()[0] < lowest) {
                lowest = entry.getValue()[0];
                oldest = entry.getKey();
            }
        }
        if (oldest != null) chunks.remove(oldest);
    }

    public boolean visited(String dimension, int chunkX, int chunkZ) {
        return chunks.containsKey(key(dimension, chunkX, chunkZ));
    }

    public int size() {
        return chunks.size();
    }

    public void clear() {
        chunks.clear();
    }

    public void load(List<?> raw) {
        chunks.clear();
        if (raw == null) return;
        for (Object element : raw) {
            if (!(element instanceof Map<?, ?> map)) continue;
            Object key = map.get("key");
            Object seen = map.get("seen");
            if (key == null) continue;
            long value = seen instanceof Number n ? n.longValue() : 0;
            chunks.put(String.valueOf(key), new long[]{value});
        }
    }

    public List<Map<String, Object>> toMaps() {
        List<Map<String, Object>> out = new ArrayList<>(chunks.size());
        for (Map.Entry<String, long[]> entry : chunks.entrySet()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", entry.getKey());
            map.put("seen", entry.getValue()[0]);
            out.add(map);
        }
        return out;
    }

    public static String key(String dimension, int chunkX, int chunkZ) {
        return (dimension == null ? "" : dimension) + ":" + chunkX + ":" + chunkZ;
    }
}
