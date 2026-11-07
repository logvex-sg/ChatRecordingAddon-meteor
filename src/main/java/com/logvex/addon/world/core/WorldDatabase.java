package com.logvex.addon.world.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The set of remembered places, bounded so a long-lived session cannot exhaust memory.
 *
 * <p>Minecraft-free and deterministic: recording the same coordinate twice updates the existing
 * entry (bumping its visit count) rather than adding a duplicate, and a new point that lands within
 * {@link #DEDUPE_RADIUS} of an existing one with the same category and name is merged into it, so a
 * repeatedly-visited spot stays a single entry. When the database is full the oldest-seen point is
 * evicted, which keeps the most recently relevant places.
 */
public final class WorldDatabase {
    /** Hard upper bound on stored points. */
    public static final int MAX_POINTS = 20_000;
    /** Points closer than this with the same name and category are treated as one place. */
    public static final double DEDUPE_RADIUS = 4.0;

    private final Map<String, WorldPoint> points = new LinkedHashMap<>();

    /**
     * Records an observation of a place, merging into an existing nearby entry when appropriate.
     *
     * @return the point that was created or updated
     */
    public WorldPoint record(String dimension, int x, int y, int z, String name,
                             LocationCategory category, long now) {
        String key = key(dimension, x, y, z);
        WorldPoint exact = points.get(key);
        if (exact != null) {
            exact.observe(now);
            return exact;
        }

        WorldPoint candidate = new WorldPoint(dimension, x, y, z, name, category);
        if (!name.isEmpty()) {
            WorldPoint nearby = findNearby(dimension, x, y, z, name, category, DEDUPE_RADIUS);
            if (nearby != null) {
                nearby.observe(now);
                return nearby;
            }
        }

        candidate.observe(now);
        if (points.size() >= MAX_POINTS) evictOldest();
        points.put(key, candidate);
        return candidate;
    }

    private WorldPoint findNearby(String dimension, int x, int y, int z, String name,
                                  LocationCategory category, double radius) {
        WorldPoint probe = new WorldPoint(dimension, x, y, z, name, category);
        for (WorldPoint point : points.values()) {
            if (point.category() != category) continue;
            if (!point.name().equalsIgnoreCase(name)) continue;
            if (point.within(probe, radius)) return point;
        }
        return null;
    }

    private void evictOldest() {
        WorldPoint oldest = null;
        for (WorldPoint point : points.values()) {
            if (oldest == null || point.lastSeen() < oldest.lastSeen()) oldest = point;
        }
        if (oldest != null) points.remove(oldest.key());
    }

    public WorldPoint get(String dimension, int x, int y, int z) {
        return points.get(key(dimension, x, y, z));
    }

    public boolean remove(String key) {
        return points.remove(key) != null;
    }

    public boolean remove(WorldPoint point) {
        return point != null && points.remove(point.key()) != null;
    }

    public int size() {
        return points.size();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public void clear() {
        points.clear();
    }

    public Collection<WorldPoint> all() {
        return new ArrayList<>(points.values());
    }

    /** The most recently seen points first, capped at {@code limit}. */
    public List<WorldPoint> recent(int limit) {
        List<WorldPoint> out = new ArrayList<>(points.values());
        out.sort(Comparator.comparingLong(WorldPoint::lastSeen).reversed());
        if (limit > 0 && out.size() > limit) return out.subList(0, limit);
        return out;
    }

    /** The points nearest to a coordinate on the same dimension, nearest first. */
    public List<WorldPoint> nearest(String dimension, int x, int y, int z, int limit) {
        WorldPoint probe = new WorldPoint(dimension, x, y, z, "", LocationCategory.OTHER);
        List<WorldPoint> out = new ArrayList<>();
        for (WorldPoint point : points.values()) {
            if (point.dimension().equals(dimension)) out.add(point);
        }
        out.sort(Comparator.comparingDouble(p -> distanceSq(p, probe)));
        if (limit > 0 && out.size() > limit) return out.subList(0, limit);
        return out;
    }

    /**
     * Free-text search over name, category and note, optionally restricted to a category and/or a
     * dimension. An empty query matches everything.
     */
    public List<WorldPoint> search(String query, LocationCategory category, String dimension) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<WorldPoint> out = new ArrayList<>();
        for (WorldPoint point : points.values()) {
            if (category != null && point.category() != category) continue;
            if (dimension != null && !dimension.isEmpty() && !point.dimension().equals(dimension)) continue;
            if (needle.isEmpty() || matches(point, needle)) out.add(point);
        }
        out.sort(Comparator.comparingLong(WorldPoint::lastSeen).reversed());
        return out;
    }

    private static boolean matches(WorldPoint point, String needle) {
        return point.name().toLowerCase(Locale.ROOT).contains(needle)
            || point.category().name().toLowerCase(Locale.ROOT).contains(needle)
            || point.note().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Replaces the whole database from stored maps; unusable records are skipped. */
    public void load(List<?> maps) {
        points.clear();
        if (maps == null) return;
        for (Object raw : maps) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            WorldPoint point = WorldPoint.fromMap(map);
            if (point == null) continue;
            points.put(point.key(), point);
        }
    }

    public List<Map<String, Object>> toMaps() {
        List<Map<String, Object>> out = new ArrayList<>(points.size());
        for (WorldPoint point : points.values()) out.add(point.toMap());
        return out;
    }

    /** A stable key for a coordinate. */
    public static String key(String dimension, int x, int y, int z) {
        return (dimension == null ? "" : dimension) + "@" + x + "," + y + "," + z;
    }

    private static double distanceSq(WorldPoint a, WorldPoint b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
