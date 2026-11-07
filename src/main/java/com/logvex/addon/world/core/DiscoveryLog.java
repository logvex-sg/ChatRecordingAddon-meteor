package com.logvex.addon.world.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A bounded, newest-first history of discoveries, so the map has a "recently found" view without
 * growing without limit.
 *
 * <p>Minecraft-free: it stores plain records and never touches the world, so it is unit testable and
 * cannot itself cause lag.
 */
public final class DiscoveryLog {
    /** Hard cap on remembered discoveries. */
    public static final int MAX_EVENTS = 500;

    /** What kind of thing was discovered. */
    public enum Kind {
        LOCATION,
        STRUCTURE,
        BIOME,
        RESOURCE,
        CHUNK;

        @Override
        public String toString() {
            String lower = name().toLowerCase(java.util.Locale.ROOT);
            return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
    }

    /** One discovery. */
    public record Event(long time, Kind kind, String dimension, int x, int y, int z, String label) {
    }

    private final java.util.Deque<Event> events = new java.util.ArrayDeque<>();

    /** Records a discovery, dropping the oldest once the cap is reached. */
    public void add(Kind kind, String dimension, int x, int y, int z, String label, long now) {
        events.addFirst(new Event(now, kind == null ? Kind.LOCATION : kind,
            dimension == null ? "" : dimension, x, y, z, label == null ? "" : label));
        while (events.size() > MAX_EVENTS) events.removeLast();
    }

    /** The newest {@code limit} discoveries (all of them when {@code limit <= 0}). */
    public List<Event> recent(int limit) {
        List<Event> out = new ArrayList<>(events);
        if (limit > 0 && out.size() > limit) return out.subList(0, limit);
        return out;
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }

    public List<Event> all() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public void load(List<?> raw) {
        events.clear();
        if (raw == null) return;
        // Stored oldest-first so reloading preserves newest-first order after addFirst.
        List<Event> parsed = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof java.util.Map<?, ?> map)) continue;
            Event event = fromMap(map);
            if (event != null) parsed.add(event);
        }
        for (int i = parsed.size() - 1; i >= 0; i--) {
            Event event = parsed.get(i);
            events.addLast(event);
        }
        while (events.size() > MAX_EVENTS) events.removeLast();
    }

    public List<java.util.Map<String, Object>> toMaps() {
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        // Emit oldest-first so a reload rebuilds the same order.
        List<Event> ordered = new ArrayList<>(events);
        Collections.reverse(ordered);
        for (Event event : ordered) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("time", event.time());
            map.put("kind", event.kind().name());
            map.put("dimension", event.dimension());
            map.put("x", event.x());
            map.put("y", event.y());
            map.put("z", event.z());
            map.put("label", event.label());
            out.add(map);
        }
        return out;
    }

    private static Event fromMap(java.util.Map<?, ?> map) {
        Object time = map.get("time");
        if (!(time instanceof Number)) return null;
        return new Event(((Number) time).longValue(),
            kindOf(map.get("kind")),
            stringOf(map.get("dimension")),
            intOf(map.get("x")), intOf(map.get("y")), intOf(map.get("z")),
            stringOf(map.get("label")));
    }

    private static Kind kindOf(Object value) {
        if (value == null) return Kind.LOCATION;
        try {
            return Kind.valueOf(String.valueOf(value).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Kind.LOCATION;
        }
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
