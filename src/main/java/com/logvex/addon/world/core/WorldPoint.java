package com.logvex.addon.world.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One remembered place in the world: a coordinate, a name, a category and when it was seen.
 *
 * <p>Minecraft-free so the database and the map logic can be tested without a client. A point is
 * identified by its dimension and rounded block coordinate, so the same spot recorded twice updates
 * one entry rather than piling up duplicates.
 */
public final class WorldPoint {
    private final String dimension;
    private final int x;
    private final int y;
    private final int z;
    private String name;
    private LocationCategory category;
    private String note;
    private long firstSeen;
    private long lastSeen;
    private int visits;

    public WorldPoint(String dimension, int x, int y, int z, String name, LocationCategory category) {
        this.dimension = dimension == null ? "" : dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.name = name == null ? "" : name;
        this.category = category == null ? LocationCategory.OTHER : category;
        this.note = "";
    }

    public String dimension() {
        return dimension;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public String name() {
        return name;
    }

    public WorldPoint name(String value) {
        this.name = value == null ? "" : value;
        return this;
    }

    public LocationCategory category() {
        return category;
    }

    public WorldPoint category(LocationCategory value) {
        if (value != null) this.category = value;
        return this;
    }

    public String note() {
        return note;
    }

    public WorldPoint note(String value) {
        this.note = value == null ? "" : value;
        return this;
    }

    public long firstSeen() {
        return firstSeen;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public int visits() {
        return visits;
    }

    /** Records an observation: sets first/last seen and counts a new visit. */
    public void observe(long now) {
        if (firstSeen == 0) firstSeen = now;
        lastSeen = now;
        visits++;
    }

    public void setSeen(long first, long last, int visits) {
        this.firstSeen = first;
        this.lastSeen = last;
        this.visits = Math.max(0, visits);
    }

    /** A stable key for this coordinate inside a dimension. */
    public String key() {
        return dimension + "@" + x + "," + y + "," + z;
    }

    /** True when {@code other} is within {@code radius} blocks on the same dimension. */
    public boolean within(WorldPoint other, double radius) {
        if (other == null || !dimension.equals(other.dimension)) return false;
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dimension", dimension);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("name", name);
        map.put("category", category.name());
        map.put("note", note);
        map.put("firstSeen", firstSeen);
        map.put("lastSeen", lastSeen);
        map.put("visits", visits);
        return map;
    }

    /** Reads a point from stored data, or {@code null} when the record is unusable. */
    public static WorldPoint fromMap(Map<?, ?> map) {
        if (map == null) return null;
        Object dimension = map.get("dimension");
        Integer x = intOrNull(map.get("x"));
        Integer y = intOrNull(map.get("y"));
        Integer z = intOrNull(map.get("z"));
        if (dimension == null || x == null || y == null || z == null) return null;
        WorldPoint point = new WorldPoint(String.valueOf(dimension), x, y, z,
            stringOr(map.get("name"), ""), LocationCategory.fromName(stringOr(map.get("category"), null)));
        point.note(stringOr(map.get("note"), ""));
        point.setSeen(longOr(map.get("firstSeen"), 0), longOr(map.get("lastSeen"), 0),
            intOr(map.get("visits"), 0));
        return point;
    }

    @Override
    public String toString() {
        String label = name.isBlank() ? category.toString() : name;
        return label + " [" + x + ", " + y + ", " + z + "]";
    }

    private static String stringOr(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int intOr(Object value, int fallback) {
        Integer parsed = intOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    private static long longOr(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
