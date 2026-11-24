package com.logvex.addon.world.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A record of which chunks have been visited, keyed by dimension and chunk coordinate.
 *
 * <p>A chunk is recorded once; revisiting it only refreshes the timestamp. Minecraft-free.
 *
 * <p>Uncapped by default: the visited set is saved to disk and drives the map's "where have I been"
 * view, so dropping a chunk to stay under a number would erase part of the map. {@link #setCapacity}
 * is there for anyone who wants a bound; {@code 0} means no cap.
 */
public final class VisitedChunks {
    /** The cap this store used before it became configurable, kept as the setting's suggestion. */
    public static final int MAX_CHUNKS = 40_000;

    private final Map<String, long[]> chunks = new LinkedHashMap<>();
    private int capacity;

    /** Records a visit to a chunk; returns true when this was the first time. */
    public boolean visit(String dimension, int chunkX, int chunkZ, long now) {
        String key = key(dimension, chunkX, chunkZ);
        long[] existing = chunks.get(key);
        if (existing != null) {
            existing[0] = now;
            return false;
        }
        if (capacity > 0 && chunks.size() >= capacity) evictOldest();
        chunks.put(key, new long[]{now});
        return true;
    }

    /** Sets the cap on tracked chunks; {@code 0} keeps every chunk. */
    public void setCapacity(int capacity) {
        this.capacity = Math.max(0, capacity);
        while (capacity > 0 && chunks.size() > capacity) evictOldest();
    }

    public int capacity() {
        return capacity;
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
