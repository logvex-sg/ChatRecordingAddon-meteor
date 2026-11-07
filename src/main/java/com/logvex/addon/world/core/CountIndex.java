package com.logvex.addon.world.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded tally of things seen in a world — biomes visited or resource blocks found.
 *
 * <p>Keeps a count per key and the last coordinate each was seen at, capped at {@link #MAX_KEYS} so a
 * world with hundreds of biome or block variants cannot grow without limit. Minecraft-free, so it is
 * unit testable.
 */
public final class CountIndex {
    public static final int MAX_KEYS = 512;

    /** One key's tally. */
    public record Entry(String key, int count, int x, int y, int z, long lastSeen) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** Records one observation of {@code key} at a coordinate. */
    public void observe(String key, int x, int y, int z, long now) {
        if (key == null || key.isBlank()) return;
        Entry existing = entries.get(key);
        if (existing != null) {
            entries.put(key, new Entry(key, existing.count() + 1, x, y, z, now));
            return;
        }
        if (entries.size() >= MAX_KEYS) evictLeastSeen();
        entries.put(key, new Entry(key, 1, x, y, z, now));
    }

    private void evictLeastSeen() {
        String least = null;
        int lowest = Integer.MAX_VALUE;
        for (Entry entry : entries.values()) {
            if (entry.count() < lowest) {
                lowest = entry.count();
                least = entry.key();
            }
        }
        if (least != null) entries.remove(least);
    }

    public int count(String key) {
        Entry entry = entries.get(key);
        return entry == null ? 0 : entry.count();
    }

    public Entry get(String key) {
        return entries.get(key);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    /** Every entry, highest count first. */
    public List<Entry> top() {
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort(Comparator.comparingInt(Entry::count).reversed());
        return out;
    }

    public void load(List<?> raw) {
        entries.clear();
        if (raw == null) return;
        for (Object element : raw) {
            if (!(element instanceof Map<?, ?> map)) continue;
            Entry entry = fromMap(map);
            if (entry != null) entries.put(entry.key(), entry);
        }
    }

    public List<Map<String, Object>> toMaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry entry : entries.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", entry.key());
            map.put("count", entry.count());
            map.put("x", entry.x());
            map.put("y", entry.y());
            map.put("z", entry.z());
            map.put("lastSeen", entry.lastSeen());
            out.add(map);
        }
        return out;
    }

    private static Entry fromMap(Map<?, ?> map) {
        Object key = map.get("key");
        if (key == null) return null;
        return new Entry(String.valueOf(key),
            intOf(map.get("count"), 0), intOf(map.get("x"), 0), intOf(map.get("y"), 0), intOf(map.get("z"), 0),
            longOf(map.get("lastSeen"), 0));
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static long longOf(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }
}
